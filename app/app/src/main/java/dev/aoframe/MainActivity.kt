package dev.aoframe

import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import dev.aoframe.cache.AssetCacheDatabase
import dev.aoframe.cache.AssetCacheSync
import dev.aoframe.control.LocalControlServer
import dev.aoframe.countdown.CountdownStore
import dev.aoframe.countdown.CountdownTiming
import dev.aoframe.immich.ImmichAsset
import dev.aoframe.immich.ImmichClient
import dev.aoframe.immich.ImmichSecretsStore
import dev.aoframe.localsource.LocalAssetSync
import dev.aoframe.localsource.LocalPhotoClient
import dev.aoframe.locale.AppLocale
import dev.aoframe.nightmode.NightModeController
import dev.aoframe.nightmode.NightModeStore
import dev.aoframe.render.SlideshowRenderer
import dev.aoframe.slideshowsettings.SlideshowSettingsStore
import dev.aoframe.weather.WeatherClient
import dev.aoframe.weather.WeatherIcons
import dev.aoframe.weather.WeatherSnapshot
import dev.aoframe.webcam.WebcamClipSync
import dev.aoframe.webcam.WebcamTestModeConfig
import dev.aoframe.webcam.WebcamTestModeStore
import dev.aoframe.wake.WakeRefreshGate
import dev.aoframe.widgets.ClockFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "MainActivity"

// One shared in-process coordinator per widget class, not a
// timer-per-subsystem, to keep wakeups coalesced. Clock ticks every
// second (cheap, no network); weather is "tens of minutes, not seconds".
private const val CLOCK_TICK_MS = 1_000L
private const val WEATHER_REFRESH_INTERVAL_MS = 30 * 60 * 1_000L
// A periodic background catch-all in case a webhook is ever missed
// (network blip, Immich restart mid-notification). Not the only way new
// assets appear - the Immich webhook (see runBackgroundSync()) is the
// fast path, this is the fallback.
private const val SLIDESHOW_SYNC_INTERVAL_MS = 30 * 60 * 1_000L
// A transient network blip (a SocketTimeoutException reaching Immich -
// unrelated to any per-asset issue, this is the metadata fetch itself)
// would otherwise fail the *entire* sync with zero retry, blanking the
// whole slideshow rather than just one asset (a much more severe gap
// than AssetCacheSync.downloadMissing()'s own per-kind try/catch already
// covers). A bounded retry here covers exactly this kind of ordinary
// transient failure without adding real complexity.
private const val SYNC_MAX_ATTEMPTS = 3
private const val SYNC_RETRY_DELAY_MS = 5_000L

// On-screen webcam-only button - polls the video-transcode gate after
// forcing a capture cycle, see toggleWebcamOnlyMode(). Fails open (just
// shows whatever's cached) rather than blocking forever if the gate
// never reports done.
private const val WEBCAM_REFRESH_POLL_INTERVAL_MS = 3_000L
private const val WEBCAM_REFRESH_POLL_MAX_ATTEMPTS = 60

// Plain View-based, not Compose - this device can run out of RAM under a
// lighter workload than this app's, and there's no complex reactive UI
// here to justify Compose's runtime cost.
class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    // Built in onCreate() once config is loadable (needs a Context), not
    // as a field initializer - null if weatherLatitude/weatherLongitude
    // aren't configured, in which case the weather widget just never
    // updates (see startWeatherLoop()).
    private var weatherClient: WeatherClient? = null
    // Resolved once in onCreate() from config's "locale" field (see
    // AppLocale) - defaults to English if unset/unrecognized.
    private lateinit var locale: Locale
    private lateinit var assetCacheDatabase: AssetCacheDatabase
    private lateinit var slideshowRenderer: SlideshowRenderer
    private lateinit var loadingOverlay: FrameLayout
    private var loadingSpinnerAnimator: ObjectAnimator? = null
    private var localControlServer: LocalControlServer? = null
    private var lastSyncAtMs: Long? = null
    // Snapshot of whatever non-webcam assets were on screen right before
    // switching into webcam-only mode - see toggleWebcamOnlyMode()'s own
    // comment for why this exists.
    private var lastNonWebcamAssets: List<ImmichAsset>? = null
    // Debounces onScreenWoke() against rapid repeated onResume() calls -
    // see WakeRefreshGate's own comment.
    private val webcamWakeRefreshGate = WakeRefreshGate()

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, CLOCK_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableSustainedPerformanceModeIfSupported()
        setContentView(R.layout.activity_main)
        hideSystemBars()

        assetCacheDatabase = AssetCacheDatabase(this)
        val startupSecrets = ImmichSecretsStore.load(this)
        locale = AppLocale.resolve(startupSecrets?.locale)
        val weatherLat = startupSecrets?.weatherLatitude
        val weatherLon = startupSecrets?.weatherLongitude
        weatherClient = if (weatherLat != null && weatherLon != null) {
            WeatherClient(weatherLat, weatherLon, startupSecrets.weatherSeaLatitude, startupSecrets.weatherSeaLongitude)
        } else {
            null
        }
        // No coordinates configured - hide the whole widget rather than
        // show an empty pill with no icon/text ever set.
        if (weatherClient == null) {
            findViewById<View>(R.id.weatherWidgetContainer).visibility = View.GONE
        } else if (startupSecrets?.weatherSeaLatitude == null || startupSecrets.weatherSeaLongitude == null) {
            // Sea-temperature point not configured - hide just that
            // icon/text pair, not the whole widget (land forecast still
            // works fine without it).
            findViewById<View>(R.id.weatherSeaTempIcon).visibility = View.GONE
            findViewById<View>(R.id.weatherSeaTempText).visibility = View.GONE
        }
        slideshowRenderer = SlideshowRenderer(
            context = this,
            photoView = findViewById<ImageView>(R.id.photoView),
            photoBackdropView = findViewById<ImageView>(R.id.photoBackdropView),
            playerViewA = findViewById<PlayerView>(R.id.playerViewA),
            playerViewB = findViewById<PlayerView>(R.id.playerViewB),
            db = assetCacheDatabase,
            scope = lifecycleScope
        )

        loadingOverlay = findViewById(R.id.loadingOverlay)
        startLoadingSpinner()
        setUpControlButtons()

        // Best-effort, one-shot: restores countdown/night-mode config
        // from the backup server's cache if (and only if) this app's own
        // copy is missing - see each Store's own restoreFromPiIfMissing()
        // comment. Fire-and-forget - neither
        // call blocks startup or anything below; if a restore actually
        // happens, the clock tick / night-mode check loops just pick up
        // the newly-saved config on their next regular pass (within
        // 1s / 30s respectively), same as any other live edit.
        lifecycleScope.launch { CountdownStore.restoreFromPiIfMissing(this@MainActivity) }
        lifecycleScope.launch { NightModeStore.restoreFromPiIfMissing(this@MainActivity) }
        lifecycleScope.launch { SlideshowSettingsStore.restoreFromPiIfMissing(this@MainActivity) }

        handler.post(clockTick)
        startWeatherLoop()
        startSlideshowSync()

        NightModeController(this, lifecycleScope).start()
        startLocalControlServer()
    }

    // Stops the slideshow's own decode/animation work while the screen's
    // actually off - see SlideshowRenderer.pause()'s own comment for the
    // measured CPU/decoder cost this avoids. onPause()/onResume() (not
    // onStop()/onStart()) because `dumpsys activity` confirmed both fire
    // in the expected order around a real display sleep/wake on this
    // device, and pairing on the tighter one keeps the "not interactive"
    // window as accurate as possible. This is the Activity's actual
    // lifecycle state, which the framework already ties to real display
    // power for a single foreground kiosk app with nothing else able to
    // steal focus - not a duplicate of the night-mode schedule - so a
    // manual wake during the scheduled window (or a scheduled sleep
    // outside it, e.g. someone puts the frame away) both correctly
    // resume/pause rendering, not just the exact configured hours.
    // Guarded with `::slideshowRenderer.isInitialized` - onPause() can
    // fire before onCreate() finishes setting it up in rare startup
    // races, and there's nothing to pause yet in that case.
    override fun onPause() {
        super.onPause()
        if (::slideshowRenderer.isInitialized) slideshowRenderer.pause()
    }

    override fun onResume() {
        super.onResume()
        if (::slideshowRenderer.isInitialized) slideshowRenderer.resumeIfPaused()
        if (webcamWakeRefreshGate.shouldFire()) onScreenWoke()
    }

    // Prev/next jump immediately (cancelling whatever timer was running).
    // No on-screen refresh button - refreshSlideshow() is still reachable
    // via an admin panel's web form and POST /action/refresh-cache, just
    // not from the frame's own screen.
    private fun setUpControlButtons() {
        findViewById<ImageButton>(R.id.controlPreviousButton).setOnClickListener { slideshowRenderer.goToPrevious() }
        findViewById<ImageButton>(R.id.controlNextButton).setOnClickListener { slideshowRenderer.goToNext() }
        findViewById<ImageButton>(R.id.controlWebcamOnlyButton).setOnClickListener { toggleWebcamOnlyMode() }
        lifecycleScope.launch { updateWebcamOnlyButtonAppearance() }
    }

    // Green outline when webcam-only mode is on, and hidden entirely when
    // the feature's master on/off switch (an admin panel's /frameo/webcam
    // toggle) is off - lets the frame's own screen show at a glance
    // whether the mode is active, and stops offering a button for a
    // feature the admin has turned off. Called on startup (in case either
    // switch was left in a non-default state from a previous session/the
    // admin panel) and after every refreshSlideshow() - reads
    // WebcamTestModeStore/WebcamClipSync.isFeatureEnabled() directly rather
    // than tracking separate in-memory flags, same "device/Pi owns the
    // config, this just reflects it" reasoning as every other config-style
    // switch in this app. A suspend fun (isFeatureEnabled() is a network
    // call) - every caller already runs inside a coroutine.
    private suspend fun updateWebcamOnlyButtonAppearance() {
        val button = findViewById<ImageButton>(R.id.controlWebcamOnlyButton)
        val featureEnabled = try {
            WebcamClipSync(this@MainActivity, assetCacheDatabase).isFeatureEnabled()
        } catch (error: Exception) {
            true
        }
        button.visibility = if (featureEnabled) View.VISIBLE else View.GONE
        if (!featureEnabled) return
        val testModeEnabled = WebcamTestModeStore.load(this).enabled
        val background = if (testModeEnabled) R.drawable.control_button_background_active else R.drawable.control_button_background
        button.setBackgroundResource(background)
    }

    // NanoHTTPD - see LocalControlServer's own header comment for why
    // this is one shared server rather than one per feature. A bind
    // failure (e.g. the port already in use) shouldn't crash the
    // slideshow - night mode/countdown/refresh-cache/status just become
    // unreachable from the Pi until the next launch.
    private fun startLocalControlServer() {
        try {
            localControlServer = LocalControlServer(
                context = this,
                onRefreshCacheRequested = ::refreshSlideshow,
                onResetFacesRequested = ::resetFacesCache,
                onReshuffleRequested = ::reshuffleSlideshow,
                onImmichWebhook = ::triggerBackgroundSync,
                onRefreshWebcamRequested = ::refreshWebcamOnly,
                onWebcamTestModeChanged = ::refreshSlideshow,
                statusProvider = ::buildStatusJson
            ).apply { start() }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start local control server", error)
        }
    }

    // Backs GET /status - real numbers from the manifest, not estimates.
    // Called from NanoHTTPD's own worker thread, not the main thread -
    // AssetCacheDatabase's queries are safe to call from any thread
    // (plain SQLiteOpenHelper, no main-thread affinity).
    private fun buildStatusJson(): JSONObject {
        val countsByType = assetCacheDatabase.countsByType()
        val faceStats = assetCacheDatabase.faceQueryStats()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return JSONObject()
            .put("lastSyncAt", lastSyncAtMs ?: JSONObject.NULL)
            .put("assetCount", assetCacheDatabase.assetCount())
            .put("photoCount", countsByType["IMAGE"] ?: 0)
            .put("videoCount", countsByType["VIDEO"] ?: 0)
            .put("livePhotoCount", countsByType["LIVE_PHOTO"] ?: 0)
            .put("cacheBytes", assetCacheDatabase.totalCachedBytes())
            .put("facesFound", faceStats.found)
            .put("facesNone", faceStats.none)
            .put("facesPending", faceStats.pending)
            // Real display power state (android.os.PowerManager) - the
            // ground truth any external caller needs to avoid firing a
            // redundant sleep/wake action.
            .put("screenAwake", powerManager.isInteractive)
    }

    // Continuous rotation, driven in code rather than baked into the
    // drawable - a plain ObjectAnimator on ROTATION, same pattern already
    // used for Ken Burns in SlideshowRenderer. Stopped (not just hidden)
    // once the overlay is dismissed, so it doesn't keep spinning
    // invisibly forever in the background.
    private fun startLoadingSpinner() {
        loadingSpinnerAnimator = ObjectAnimator.ofFloat(
            findViewById<ImageView>(R.id.loadingSpinner), View.ROTATION, 0f, 360f
        ).apply {
            duration = 1200L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    // Dismisses the cold-start loading screen - called once the first
    // real slideshow frame is ready, or if sync fails/has nothing to show
    // (see startSlideshowSync()) so a broken sync doesn't leave the user
    // staring at a spinner forever instead of the (blank) slideshow. Also
    // reused by toggleWebcamOnlyMode() below for the same overlay, once
    // the freshly-synced assets it's waiting on are ready.
    private fun hideLoadingOverlay() {
        if (loadingOverlay.visibility == View.GONE) return
        loadingOverlay.visibility = View.GONE
        loadingSpinnerAnimator?.cancel()
        loadingSpinnerAnimator = null
    }

    // Re-shows the same cold-start overlay/spinner for toggleWebcamOnlyMode()
    // below, so switching into (or out of) webcam-only mode reads as "loading"
    // rather than silently continuing to show the old rotation while the
    // switch's own sync is still in flight. Idempotent, same reasoning as
    // hideLoadingOverlay() above.
    private fun showLoadingOverlay() {
        if (loadingOverlay.visibility == View.VISIBLE) return
        loadingOverlay.visibility = View.VISIBLE
        startLoadingSpinner()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockTick)
        loadingSpinnerAnimator?.cancel()
        localControlServer?.stop()
        slideshowRenderer.release()
        // weatherLoop/sync are started on lifecycleScope, which cancels
        // itself on destroy - no manual cleanup needed there.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun updateClock() {
        val now = LocalDateTime.now()
        findViewById<TextView>(R.id.clockWeekdayText).text = ClockFormatter.formatWeekday(now, locale)
        findViewById<TextView>(R.id.clockDateText).text = ClockFormatter.formatFullDate(now, locale)
        findViewById<TextView>(R.id.clockTimeText).text = timeWithSuperscriptSeconds(now)
        updateCountdown()
    }

    // Riding the same 1s clock tick rather than its own timer - one more
    // coalesced wakeup, and "full" precision needs per-second updates
    // anyway. CountdownStore.load() is a cheap in-memory read after the
    // first call (see that class's own comment), so this isn't disk I/O
    // every second.
    private fun updateCountdown() {
        val pill = findViewById<View>(R.id.countdownPill)
        val display = CountdownTiming.display(CountdownStore.load(this), System.currentTimeMillis(), locale)
        if (display == null) {
            pill.visibility = View.GONE
            return
        }
        pill.visibility = View.VISIBLE
        val labelView = findViewById<TextView>(R.id.countdownLabelText)
        labelView.visibility = if (display.label.isBlank()) View.GONE else View.VISIBLE
        labelView.text = display.label
        findViewById<TextView>(R.id.countdownValueText).text = display.value
    }

    // "16:06" + a small raised "58" - a large primary time with the
    // seconds as a smaller superscript. android.text spans, not
    // plain-JVM-testable - ClockFormatter's own
    // formatTime()/formatSeconds() are the testable pure-string half of
    // this.
    private fun timeWithSuperscriptSeconds(now: LocalDateTime): CharSequence {
        val time = ClockFormatter.formatTime(now)
        val seconds = ClockFormatter.formatSeconds(now)
        val combined = SpannableString(time + seconds)
        val secondsStart = time.length
        combined.setSpan(RelativeSizeSpan(0.45f), secondsStart, combined.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        combined.setSpan(SuperscriptSpan(), secondsStart, combined.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return combined
    }

    private fun startWeatherLoop() {
        val client = weatherClient ?: return
        lifecycleScope.launch {
            while (isActive) {
                try {
                    renderWeather(client.fetch())
                } catch (error: Exception) {
                    // A failed fetch just means stale weather stays on
                    // screen until the next attempt - never crashes the
                    // slideshow over a network blip.
                }
                delay(WEATHER_REFRESH_INTERVAL_MS)
            }
        }
    }

    // Icon-based rows matching the reference, not plain text - see
    // WeatherIcons.kt's header comment for the (non-emoji) icon source.
    private fun renderWeather(snapshot: WeatherSnapshot) {
        val isDaytime = LocalTime.now().let { it.hour in 6..19 }

        findViewById<ImageView>(R.id.weatherCurrentIcon)
            .setImageResource(WeatherIcons.iconFor(snapshot.weatherCode, isDaytime))
        findViewById<TextView>(R.id.weatherCurrentTempText).text =
            "${snapshot.currentTempC.roundToInt()}°"

        findViewById<TextView>(R.id.weatherFeelsLikeText).text = "${snapshot.feelsLikeTempC.roundToInt()}°"
        findViewById<TextView>(R.id.weatherPrecipText).text = "${snapshot.precipitationProbability}%"

        // Null when the marine request failed or had no coverage for
        // the point - leave the last-known reading on screen rather
        // than replacing it with a placeholder, same "stale beats blank"
        // reasoning as startWeatherLoop()'s catch block.
        snapshot.seaSurfaceTempC?.let { seaTempC ->
            findViewById<TextView>(R.id.weatherSeaTempText).text = "${seaTempC.roundToInt()}°"
        }

        val today = LocalDate.now()
        // max/min are two separate table cells, not one combined string -
        // so each column (max° across all 3 rows, min° across all 3 rows)
        // gets its own consistent width/alignment via TableLayout, same
        // reasoning as the label column already gets.
        val dayViews = listOf(
            DayForecastViews(R.id.weatherDay0Label, R.id.weatherDay0Icon, R.id.weatherDay0MaxTemp, R.id.weatherDay0MinTemp),
            DayForecastViews(R.id.weatherDay1Label, R.id.weatherDay1Icon, R.id.weatherDay1MaxTemp, R.id.weatherDay1MinTemp),
            DayForecastViews(R.id.weatherDay2Label, R.id.weatherDay2Icon, R.id.weatherDay2MaxTemp, R.id.weatherDay2MinTemp)
        )
        snapshot.daily.take(3).forEachIndexed { index, day ->
            val views = dayViews[index]
            val label = when (index) {
                0 -> AppLocale.today(locale)
                1 -> AppLocale.tomorrow(locale)
                else -> AppLocale.shortWeekdayName(today.plusDays(index.toLong()).dayOfWeek, locale)
            }
            findViewById<TextView>(views.labelId).text = label
            // Forecast icons always use the day variant - it's a daily
            // high-level summary, not tied to a specific hour.
            findViewById<ImageView>(views.iconId).setImageResource(WeatherIcons.iconFor(day.weatherCode, isDaytime = true))
            findViewById<TextView>(views.maxTempId).text = "${day.maxTempC.roundToInt()}°"
            findViewById<TextView>(views.minTempId).text = "${day.minTempC.roundToInt()}°"
        }
    }

    private data class DayForecastViews(val labelId: Int, val iconId: Int, val maxTempId: Int, val minTempId: Int)

    // Initial sync + start on launch, then a periodic background
    // catch-all alongside the Immich webhook (see triggerBackgroundSync())
    // in case a webhook notification is ever missed. One coordinator
    // loop, not a separate timer per concern - same "coalesce wakeups"
    // reasoning as clock/weather.
    private fun startSlideshowSync() {
        lifecycleScope.launch {
            runInitialSync()
            while (isActive) {
                delay(SLIDESHOW_SYNC_INTERVAL_MS)
                runBackgroundSync()
            }
        }
    }

    // The admin panel's "Оновити фотографії" action and
    // POST /action/refresh-cache both land here - reachable via the web
    // form/API, not from an on-screen button.
    // Deliberately disruptive (jumps back to the first slide) since it's
    // an explicit, visible user action - unlike runBackgroundSync() below.
    // Safe to call from any thread - the control server calls it from
    // NanoHTTPD's own worker thread.
    private fun refreshSlideshow() {
        lifecycleScope.launch {
            // Cheap, idempotent - reflects whatever WebcamTestModeStore
            // currently says regardless of why this refresh was triggered
            // (the on-screen button, the admin panel's checkbox, or a plain
            // photo refresh), see updateWebcamOnlyButtonAppearance()'s own
            // comment.
            updateWebcamOnlyButtonAppearance()
            val assets = syncAssets() ?: return@launch
            slideshowRenderer.start(assets)
        }
    }

    // POST /action/reset-faces lands here - the escape hatch from a
    // plain refresh (which deliberately reuses each asset's already-
    // resolved face target, see AssetRepository's knownFaceTargets
    // handling). Clears every asset's cached face result first so the
    // sync that follows re-queries the whole library, then behaves like
    // refreshSlideshow() (jumps back to the first slide - an explicit,
    // visible action, same reasoning as that function's own comment).
    private fun resetFacesCache() {
        assetCacheDatabase.resetFaceQueries()
        refreshSlideshow()
    }

    // POST /action/reshuffle lands here (/frameo/settings' "shuffle
    // photos" button) - no Immich round-trip needed, just
    // reorders the current rotation, but still hopped onto the main
    // thread like every other control-server callback since
    // SlideshowRenderer's mutable state is otherwise only ever touched
    // from there.
    private fun reshuffleSlideshow() {
        lifecycleScope.launch { slideshowRenderer.reshuffle() }
    }

    // On-screen webcam-only button (activity_main.xml's
    // controlWebcamOnlyButton, left of prev/next) - toggles the same
    // WebcamTestModeStore switch the admin panel's checkbox controls (see
    // LocalControlServer.handleWebcamTestMode()). shuffleEnabled itself is
    // normally set inside syncAssets() (see that function's comment), but
    // the instant-switch paths below bypass syncAssets() entirely, so they
    // set it themselves to the same value it would've picked.
    //
    // Both directions already have everything they need sitting on the
    // frame from the last real sync - there's no reason to block the
    // switch on a fresh network round trip:
    //  - Turning ON: WebcamClipSync.cachedAssets() reads back whatever
    //    clips are already downloaded (no manifest fetch, no re-download -
    //    sync() always re-downloads unconditionally, see its own comment,
    //    which is fine for a background refresh but wasteful to block a
    //    button press on when the clips likely haven't changed).
    //  - Turning OFF: lastNonWebcamAssets, captured just below right
    //    before switching away from it - the Immich/local library was
    //    already synced at most SLIDESHOW_SYNC_INTERVAL_MS ago and hasn't
    //    gone anywhere, so there's no need to re-fetch it just to show the
    //    same rotation again.
    // Either branch falls back to the cold-start loading overlay + a real
    // syncAssets() only if nothing's cached yet (e.g. right after install,
    // or the very first time this mode is used). A real refresh still
    // follows in the background either way - forceFreshWebcamCaptureInBackground()
    // (forces a new capture cycle, then re-syncs) turning on,
    // runBackgroundSync() (a plain non-disruptive re-sync) turning off -
    // so what's on screen is upgraded to the freshest data shortly after
    // instead of gating the switch on it.
    private fun toggleWebcamOnlyMode() {
        lifecycleScope.launch {
            val nowEnabled = !WebcamTestModeStore.load(this@MainActivity).enabled
            WebcamTestModeStore.save(this@MainActivity, WebcamTestModeConfig(nowEnabled))
            updateWebcamOnlyButtonAppearance()
            slideshowRenderer.setShuffleEnabled(!nowEnabled)

            val cached = if (nowEnabled) {
                lastNonWebcamAssets = slideshowRenderer.currentAssets().filterNot { it.id.startsWith("webcam-") }
                WebcamClipSync(this@MainActivity, assetCacheDatabase).cachedAssets()
            } else {
                lastNonWebcamAssets
            }

            if (!cached.isNullOrEmpty()) {
                slideshowRenderer.start(cached)
            } else {
                showLoadingOverlay()
                val assets = syncAssets()
                if (assets == null) {
                    hideLoadingOverlay()
                    return@launch
                }
                slideshowRenderer.start(assets, onFirstFrameReady = ::hideLoadingOverlay)
                // An empty result (e.g. no webcam clips available yet) never
                // fires onFirstFrameReady - dismiss immediately rather than
                // spinning forever, same as runInitialSync()'s own comment.
                if (assets.isEmpty()) hideLoadingOverlay()
            }

            if (nowEnabled) {
                // Always worth forcing a new capture cycle regardless of
                // which branch above ran - sync()/cachedAssets() only ever
                // return what the gate already had, never a genuinely new
                // capture.
                forceFreshWebcamCaptureInBackground()
            } else if (!cached.isNullOrEmpty()) {
                // Only needed after the cached fast-path above - the
                // fallback's syncAssets() call was already a live fetch,
                // so a second one here would just duplicate it.
                runBackgroundSync()
            }
        }
    }

    // Forces a fresh capture cycle on the video-transcode gate (its POST
    // /webcam/refresh, called directly - same host WebcamClipSync already
    // talks to for the manifest/clips) and polls until it's done, then
    // re-syncs - see WEBCAM_REFRESH_POLL_* above for the bound.
    // Runs independently of the immediate refreshSlideshow() in
    // toggleWebcamOnlyMode() above - this one only *upgrades* what's
    // already showing once fresher clips exist, via the same
    // non-disruptive updateAssets() path runBackgroundSync() uses (jumping
    // back to slide 0 a second time, right after the user already saw the
    // first switch, would be a visible, pointless restart). Fails open at
    // every step (a gate that's unreachable, or a cycle that never
    // finishes within the poll ceiling) by simply not re-syncing - staying
    // on whatever's already showing is never worse than what came before
    // this ran. Also bails without re-syncing if the mode's been turned
    // back off again by the time the capture finishes - no point applying
    // a webcam sync result to a display that's since moved on to normal
    // photos.
    // Extracted from forceFreshWebcamCaptureInBackground() so
    // onScreenWoke() below can reuse the same force-capture-and-wait
    // step without duplicating it. Returns false (nothing to re-sync)
    // on any failure - the gate being unreachable, or a cycle that
    // never finishes within the poll ceiling.
    private suspend fun forceFreshWebcamCaptureAndWait(): Boolean {
        val sync = WebcamClipSync(this@MainActivity, assetCacheDatabase)
        return try {
            sync.forceRefreshOnPi()
            var attempts = 0
            while (attempts < WEBCAM_REFRESH_POLL_MAX_ATTEMPTS && sync.isCycleInProgress()) {
                delay(WEBCAM_REFRESH_POLL_INTERVAL_MS)
                attempts++
            }
            true
        } catch (error: Exception) {
            Log.w(TAG, "Forced webcam-gate refresh failed - staying on whatever's already cached", error)
            false
        }
    }

    private fun forceFreshWebcamCaptureInBackground() {
        lifecycleScope.launch {
            if (!forceFreshWebcamCaptureAndWait()) return@launch
            if (WebcamTestModeStore.load(this@MainActivity).enabled) runBackgroundSync()
        }
    }

    // Extracted from refreshWebcamOnly() so onScreenWoke() below can
    // reuse the same re-sync-and-splice step. Fetches the webcam gate's
    // current clips and swaps them into whatever's currently on screen,
    // never touching Immich or interrupting playback.
    private suspend fun syncAndApplyFreshWebcamAssets() {
        val freshWebcamAssets = try {
            WebcamClipSync(this@MainActivity, assetCacheDatabase).sync()
        } catch (error: Exception) {
            Log.w(TAG, "Webcam clip sync failed", error)
            return
        }
        val withoutOldWebcamAssets = slideshowRenderer.currentAssets().filterNot { it.id.startsWith("webcam-") }
        slideshowRenderer.updateAssets(withoutOldWebcamAssets + freshWebcamAssets)
    }

    // POST /action/refresh-webcam lands here (an admin panel's
    // /frameo/webcam "force update on frame" button) - re-syncs only the
    // webcam clips, never
    // touching Immich and never interrupting what's currently on screen
    // (splices into the existing rotation via updateAssets(), same
    // non-disruptive shape as runBackgroundSync()). Reads the renderer's
    // current asset list rather than re-fetching Immich, so a normal
    // Immich resync's own cadence is untouched by this action.
    private fun refreshWebcamOnly() {
        lifecycleScope.launch { syncAndApplyFreshWebcamAssets() }
    }

    // Fires once per real wake - webcamWakeRefreshGate debounces
    // onResume(), which for this single foreground kiosk app (nothing
    // else able to steal focus) already correlates with the display
    // actually turning back on, see onResume()'s own comment -
    // regardless of what caused it: the on-device night-mode timer, a
    // manual sleep/wake action, a home-automation trigger, or anything
    // else a future caller adds. Forces the webcam gate to capture a
    // fresh clip per camera immediately rather than waiting for its own
    // periodic cycle (which skips capturing entirely while the screen's
    // off, so whatever's cached could be stale by however long the
    // screen was off), then re-syncs so what's on screen reflects the
    // current moment, not whenever the screen happened to fall asleep.
    private fun onScreenWoke() {
        lifecycleScope.launch {
            if (!forceFreshWebcamCaptureAndWait()) return@launch
            syncAndApplyFreshWebcamAssets()
        }
    }

    // Immich's AssetCreate webhook lands here via triggerBackgroundSync()
    // (wrapping this suspend function for LocalControlServer's plain
    // callback), and so does the 30-min safety-net timer above. Unlike
    // refreshSlideshow(), this never interrupts whatever's currently
    // showing - see SlideshowRenderer.updateAssets()'s own comment for why
    // a background sync landing mid-slide shouldn't yank the display away.
    private suspend fun runBackgroundSync() {
        val assets = syncAssets() ?: return
        slideshowRenderer.updateAssets(assets)
    }

    // Wraps runBackgroundSync() for callers that aren't already inside a
    // coroutine - the local control server's webhook handler runs on
    // NanoHTTPD's own worker thread, not a coroutine.
    private fun triggerBackgroundSync() {
        lifecycleScope.launch { runBackgroundSync() }
    }

    private suspend fun runInitialSync() {
        val assets = syncAssets()
        if (assets == null) {
            hideLoadingOverlay()
            return
        }
        slideshowRenderer.start(assets, onFirstFrameReady = ::hideLoadingOverlay)
        // An empty library never fires onFirstFrameReady (nothing to
        // show) - dismiss immediately rather than spinning forever over
        // zero assets.
        if (assets.isEmpty()) hideLoadingOverlay()
    }

    // Shared Immich fetch + cache-sync logic behind all three callers
    // above - null on missing secrets or a sync failure (both already
    // logged here), non-null (possibly empty) on success. Also merges in
    // webcam clips - a fully independent pipeline, see WebcamClipSync's
    // own doc comment for why it never touches AssetCacheSync/the
    // `assets` table.
    private suspend fun syncAssets(): List<ImmichAsset>? {
        // Webcam-only mode (see WebcamTestModeStore) - skips the Immich
        // fetch entirely, showing just the current webcam clips. Started
        // as a debug-only switch; also reachable now via the on-screen
        // button (toggleWebcamOnlyMode()), not just the admin panel.
        // Shuffling is deliberately disabled for this small, curated set
        // of clips - set here, not in the callers, so every path that ends
        // up calling start()/updateAssets()
        // with this function's result (the button, the admin-panel
        // checkbox, and any background sync landing while the mode is on)
        // stays consistent for free.
        val webcamOnly = WebcamTestModeStore.load(this@MainActivity).enabled
        slideshowRenderer.setShuffleEnabled(!webcamOnly)
        if (webcamOnly) {
            return WebcamClipSync(this@MainActivity, assetCacheDatabase).sync()
        }

        val secrets = ImmichSecretsStore.load(this@MainActivity)

        // `photoSource: "local"` - a fully self-hosted alternative to
        // Immich, reading from the AOFrame reference server's CRUD photo
        // API instead (see localsource/LocalAssetSync.kt). Checked before
        // the Immich-specific null/blank checks below, since this mode
        // needs neither baseUrl nor apiKey.
        if (secrets?.photoSource == "local") {
            val localServerBaseUrl = secrets.localServerBaseUrl
            if (localServerBaseUrl.isNullOrBlank()) {
                Log.w(TAG, "photoSource is 'local' but localServerBaseUrl is not configured - slideshow will stay blank")
                return null
            }
            val sync = LocalAssetSync(this@MainActivity, LocalPhotoClient(localServerBaseUrl), assetCacheDatabase)
            val localAssets = try {
                val assets = sync.sync()
                lastSyncAtMs = System.currentTimeMillis()
                assets
            } catch (error: Exception) {
                Log.e(TAG, "Local photo sync failed", error)
                return null
            }
            return localAssets + syncWebcamAssets()
        }

        if (secrets == null || secrets.baseUrl.isBlank() || secrets.apiKey.isBlank()) {
            // Show nothing until immich-secrets.json is provisioned,
            // rather than crashing.
            Log.w(TAG, "immich-secrets.json not found - slideshow will stay blank until it's provisioned")
            return null
        }
        val sync = AssetCacheSync(this@MainActivity, ImmichClient(secrets), assetCacheDatabase)
        val immichAssets = run {
            for (attempt in 1..SYNC_MAX_ATTEMPTS) {
                try {
                    val assets = sync.sync()
                    lastSyncAtMs = System.currentTimeMillis()
                    return@run assets
                } catch (error: Exception) {
                    Log.e(TAG, "Slideshow sync failed (attempt $attempt/$SYNC_MAX_ATTEMPTS)", error)
                    if (attempt < SYNC_MAX_ATTEMPTS) delay(SYNC_RETRY_DELAY_MS)
                }
            }
            return null
        }

        return immichAssets + syncWebcamAssets()
    }

    // Skip re-downloading webcam clips while the screen's actually
    // asleep - nothing's watching, so there's no point spending frame
    // bandwidth/flash writes on clips that would just get overwritten
    // again before anyone sees them. Checks the real display power
    // state (same android.os.PowerManager read buildStatusJson()
    // exposes as /status's screenAwake), not a night-mode schedule
    // window - a sleep triggered any other way (a manual action, a
    // home-automation trigger) is covered too, not just the configured
    // hours. Manual/explicit paths (webcamOnly mode above,
    // refreshWebcamOnly(), forceFreshWebcamCaptureInBackground())
    // deliberately bypass this - an explicit action means the caller
    // already knows what they're asking for. Shared by both the Immich
    // and local photo-source paths above - webcam clips are a fully
    // independent pipeline regardless of where the rest of the
    // slideshow's photos come from.
    private suspend fun syncWebcamAssets(): List<ImmichAsset> {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) return emptyList()
        // Own try/catch, independent of the photo-source sync above - a
        // webcam-side failure (manifest unreachable, pi-video-gate down)
        // must never fail the whole sync, same "degrade gracefully"
        // requirement as a single unreachable camera within it.
        return try {
            WebcamClipSync(this@MainActivity, assetCacheDatabase).sync()
        } catch (error: Exception) {
            Log.w(TAG, "Webcam clip sync failed - continuing without webcam clips this round", error)
            emptyList()
        }
    }

    // minSdk 27 predates WindowInsetsController (API 30) - the real
    // target device is API 27, so the legacy systemUiVisibility flags are
    // what actually runs, not dead code kept "just in case".
    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    // API 24+'s Sustained Performance Mode asks the platform to favor
    // consistent long-duration clocks over peak burst performance -
    // exactly this kiosk's workload (always-on, never idle), and exactly
    // the kind of thermal-throttling swing this is meant to smooth out.
    // Support is opt-in per the OEM/kernel, not guaranteed - logged
    // either way rather than a silent no-op.
    private fun enableSustainedPerformanceModeIfSupported() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isSustainedPerformanceModeSupported) {
            window.setSustainedPerformanceMode(true)
            Log.i(TAG, "Sustained Performance Mode: supported, enabled")
        } else {
            Log.i(TAG, "Sustained Performance Mode: not supported on this device")
        }
    }
}
