package dev.aoframe.render

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.aoframe.cache.AssetCacheDatabase
import dev.aoframe.cache.assetCacheDir
import dev.aoframe.immich.ImmichAsset
import dev.aoframe.immich.ImmichAssetType
import dev.aoframe.slideshowsettings.SlideshowSettingsConfig
import dev.aoframe.slideshowsettings.SlideshowSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// Already tuned against real playback issues on this class of hardware,
// reused rather than re-derived from scratch.
//
// Photo duration itself is no longer a constant here - it's configurable
// (along with the Ken Burns end-zoom) via SlideshowSettingsStore,
// defaulting to 6s. See applyKenBurns()/showPhoto()/showVideo() for where
// SlideshowSettingsStore.load() reads it, and SlideshowSettingsConfig's
// own doc comment for the full feature-request history.
// Widened from an initial 2_000L - a library encoded well above the
// Level <=3.1/~2-4Mbps range this was originally tuned against (e.g.
// 1080p, High profile, Level 4.0, ~8.4Mbps) means decode can genuinely
// take longer to produce a frame than a short lead time assumes.
private const val LATE_PRELOAD_LEAD_MS = 5_000L
private const val VIDEO_STALL_TIMEOUT_MS = 8_000L
private const val VIDEO_MAX_DURATION_MS = 3 * 60 * 1_000L
private const val WATCHDOG_POLL_INTERVAL_MS = 1_000L
// A real blur (ScriptIntrinsicBlur, max radius is a hard
// Android-enforced 25f) rather than the "decode tiny, let the ImageView
// stretch it" pseudo-blur approach. Decode target sized generously since
// the blur itself does the smoothing - a bigger source keeps more real
// color/shape instead of just being a stretched postage stamp.
private const val BACKDROP_DECODE_TARGET_PX = 200
private const val BACKDROP_BLUR_RADIUS = 25f
// Not one of AssetCacheSync's downloaded CacheKind values (THUMBNAIL/
// BACKDROP/VIDEO) - this one's computed locally, not fetched from Immich
// - but stored via the same cached_files table/kind mechanism so it
// shares that table's eviction path. See decodeBackdrop()'s comment.
private const val BACKDROP_BLURRED_CACHE_KIND = "BACKDROP_BLURRED"
// Ken Burns fps cap: API 27 has no Surface.setFrameRate(), and this
// pan/zoom is visually tolerant of far less than the display's native
// vsync (60Hz on typical hardware here). A SoC that thermally throttles
// under sustained load benefits from trading an imperceptible amount of
// motion smoothness for render-thread/compositor work every slide.
//
// Originally implemented via ValueAnimator.setFrameDelay(), which turned
// out to never actually work - its own docs say the requested delay "may
// be ignored when the animation system uses an external timing source,
// such as vsync", and a direct measurement confirmed the real rate stayed
// at ~55-62/sec regardless (see applyKenBurns()'s own comment for the
// actual fix - a manually elapsed-time-gated ValueAnimator update
// listener instead).
private const val KEN_BURNS_TARGET_FPS = 30

private const val TAG = "SlideshowRenderer"

// A Live Photo's motion clip is a different, otherwise-hidden asset
// referenced via videoId - a standalone video is its own asset id:
// `isLivePhoto ? asset.videoId : asset.id`.
private fun ImmichAsset.videoKey(): String =
    if (type == ImmichAssetType.LIVE_PHOTO) (videoId ?: id) else id

/**
 * Renders the cached asset rotation: ImageView + Ken Burns (a fps-gated
 * ValueAnimator on scale/pivot, not raw Matrix or Compose - see
 * applyKenBurns()'s own comment) for photos/Live Photo stills, Media3
 * ExoPlayer for video/Live Photo clips - two player/view instances
 * (front/back), see the "black flash" comment on the layout's PlayerView
 * elements. Media3 rather than plain MediaPlayer/VideoView for its better
 * buffering/format support; only current+next are ever decoded into
 * memory to keep a bounded resource footprint.
 */
class SlideshowRenderer(
    private val context: Context,
    private val photoView: ImageView,
    private val photoBackdropView: ImageView,
    private val playerViewA: PlayerView,
    private val playerViewB: PlayerView,
    private val db: AssetCacheDatabase,
    private val scope: CoroutineScope
) {
    // Created once, reused for every backdrop decode - RenderScript.create()
    // is expensive enough that doing it per-photo would be wasteful.
    // Deprecated as of API 31 but still functional (and there's no
    // GPU-shader-based replacement risk-free on this old/budget device -
    // see the "GPU blur was too janky" reasoning this whole feature
    // already carries forward from the original JS).
    private val renderScript = RenderScript.create(context)
    private val playerA = ExoPlayer.Builder(context).build().also { playerViewA.player = it }
    private val playerB = ExoPlayer.Builder(context).build().also { playerViewB.player = it }
    private var frontIsA = true
    private var backPreloadKey: String? = null

    private var assets: List<ImmichAsset> = emptyList()
    private var currentIndex = 0
    // Set while paused (see pause()/resumeIfPaused() below) - guards
    // against a stray timer/callback that was already in flight right as
    // pause() cancelled its job from touching the view afterward, and lets
    // resumeIfPaused() no-op if called when nothing's actually paused
    // (e.g. a spurious onResume() with no matching onPause()).
    private var isPaused = false
    private var preloadedBitmap: Bitmap? = null
    private var preloadedForIndex = -1
    private var preloadedBackdropBitmap: Bitmap? = null
    private var preloadedBackdropForIndex = -1
    private var advanceJob: Job? = null
    private var latePreloadJob: Job? = null
    private var watchdogJob: Job? = null
    private var renderJob: Job? = null
    private var kenBurnsAnimator: ValueAnimator? = null
    private var onFirstFrameReady: (() -> Unit)? = null

    // Off only for the on-screen webcam-only button/toggle - a
    // small, deliberately curated set of clips reads better in a fixed,
    // predictable order than reshuffled every loop like the full photo
    // library does. Gates every .shuffled() call below; MainActivity sets
    // this alongside the webcam-only mode toggle itself (see
    // WebcamTestModeStore/syncAssets()), not owned by this class.
    private var shuffleEnabled = true

    fun setShuffleEnabled(enabled: Boolean) {
        shuffleEnabled = enabled
    }

    private fun <T> List<T>.shuffledIfEnabled(): List<T> = if (shuffleEnabled) shuffled() else this

    // Bumped by every showCurrent() call, captured by showPhoto()/
    // showVideo() as their own "generation". renderJob cancellation alone
    // stops a stale coroutine at its
    // next suspend point, but Kotlin cancellation isn't instant: a job
    // already past its last suspend call (e.g. mid-way through the
    // synchronous setImageBitmap()/applyKenBurns() sequence) keeps
    // running to completion regardless. Checking the generation right
    // after the one genuinely slow step (the bitmap decode) closes that
    // last window - a stale render bails out before touching the view
    // instead of racing the current one to the screen.
    private var renderGeneration = 0

    private fun frontPlayer() = if (frontIsA) playerA else playerB
    private fun backPlayer() = if (frontIsA) playerB else playerA
    private fun frontPlayerView() = if (frontIsA) playerViewA else playerViewB
    private fun backPlayerView() = if (frontIsA) playerViewB else playerViewA

    // onFirstFrameReady - the cold-start loading screen's cue to dismiss
    // itself (see activity_main.xml's loadingOverlay comment). Fires once,
    // the first time a real photo/video actually appears on screen - not
    // on every start() call's caller-provided callback being reused across
    // asset-list refreshes, since only the very first cold start has
    // anything to hide.
    // Shuffled here, not in AssetRepository/ImmichClient - those stay
    // order-preserving on purpose, and AssetCacheSync downloads in that
    // same order. The library's own chronological order should stay
    // stable and untouched; randomization is purely a display-rotation
    // concern, scoped to this class: shuffle once per loop/restart, keep
    // that order until the rotation finishes, then reshuffle for the
    // next loop, rather than preserving a random order between launches.
    fun start(assets: List<ImmichAsset>, onFirstFrameReady: (() -> Unit)? = null) {
        this.assets = assets.shuffledIfEnabled()
        this.onFirstFrameReady = onFirstFrameReady
        if (assets.isEmpty()) return
        currentIndex = 0
        showCurrent()
    }

    // Background/webhook-triggered updates - refreshes the
    // asset list WITHOUT interrupting whatever's currently showing, unlike
    // start() which always jumps to index 0 immediately. Used by the 30-min
    // safety-net sync and the Immich webhook - neither should yank the
    // display away from whatever a person happens to be looking at just
    // because a background sync happened to land at that moment. Tries to
    // keep showing the same asset if it's still present in the new list
    // (matched by id, since a diff/re-sync can shift indices around);
    // falls back to index 0 only if it isn't (e.g. trashed/archived since).
    // The manual "refresh photos" action (POST /action/refresh-cache) is
    // deliberately different - that one's user-triggered and expected to
    // visibly restart the rotation, so it keeps calling start() instead.
    fun updateAssets(newAssets: List<ImmichAsset>) {
        if (newAssets.isEmpty()) return
        val currentAssetId = assets.getOrNull(currentIndex)?.id
        // Shuffled here too, not just in start()/goToNext()'s loop-wrap -
        // there's no need to preserve a random order between launches/
        // loops, so a background sync landing mid-loop is just as free to
        // reshuffle the upcoming rotation as a fresh loop boundary is.
        // What must NOT happen is interrupting
        // what's already on screen, so the current asset's new index is
        // located within the shuffled list, same as before.
        val shuffled = newAssets.shuffledIfEnabled()
        assets = shuffled
        val preservedIndex = currentAssetId?.let { id -> shuffled.indexOfFirst { it.id == id } } ?: -1
        currentIndex = if (preservedIndex >= 0) preservedIndex else 0
        // The "next" asset's identity may have shifted along with the
        // list - invalidate whatever was preloaded against the old
        // indices/ids so the next advance re-decodes/re-preloads fresh
        // instead of showing something for the wrong asset.
        preloadedBitmap = null
        preloadedForIndex = -1
        preloadedBackdropBitmap = null
        preloadedBackdropForIndex = -1
        backPreloadKey = null
    }

    // Manual "shuffle photos" action (/frameo/settings)
    // - reorders the same asset list updateAssets() already knows how to
    // apply without interrupting whatever's currently showing, so this
    // just re-feeds it the current list rather than duplicating that
    // logic. Deliberately not start() - a manual reshuffle isn't asking
    // to jump back to the first slide, just to freshen the order.
    fun reshuffle() = updateAssets(assets)

    // Read-only snapshot of the current rotation - backs the admin panel's
    // "force update on frame" webcam-only refresh (MainActivity.refreshWebcamOnly()),
    // which needs to splice fresh webcam assets into the list currently
    // playing without touching the Immich portion or interrupting playback.
    fun currentAssets(): List<ImmichAsset> = assets

    private fun notifyFirstFrameReady() {
        onFirstFrameReady?.invoke()
        onFirstFrameReady = null
    }

    // Manual on-screen prev/next controls - same "jump to
    // this index, cancel whatever timer was running, render immediately"
    // shape as advanceToNext()'s own natural-advance path, just
    // user-triggered and going either direction.
    fun goToNext() {
        if (assets.isEmpty()) return
        val nextIndex = currentIndex + 1
        if (nextIndex >= assets.size) {
            // Wrapped past the end - one full loop just finished (via
            // the natural advance timer or the manual "next" button,
            // both funnel through here). Reshuffle for the next pass
            // rather than replaying the same order forever - see
            // start()'s own comment on why this lives here, not in
            // AssetRepository.
            assets = assets.shuffledIfEnabled()
            currentIndex = 0
        } else {
            currentIndex = nextIndex
        }
        showCurrent()
    }

    fun goToPrevious() {
        if (assets.isEmpty()) return
        currentIndex = (currentIndex - 1 + assets.size) % assets.size
        showCurrent()
    }

    // Stops all background work while the screen's actually off - called
    // from MainActivity.onPause() (see that override's own comment).
    // Measured live on a real device: with the display
    // genuinely asleep via the same root keyevent NightModeController
    // uses, the app kept costing ~27% CPU and the hardware video decoder
    // (`ROCKCHIP_VIDEO_DEC`) kept actively producing frames - `lifecycleScope`
    // only cancels on ON_DESTROY, not ON_STOP/ON_PAUSE, so none of
    // advanceJob/watchdogJob/the Ken Burns animator/ExoPlayer's own decode
    // loop ever noticed the screen going dark. On hardware that's
    // CPU-hard-capped and thermally throttles under sustained load,
    // decoding/animating a
    // full rotation for the ~9h/night nobody's watching is pure waste.
    // Reacts to the Activity's actual lifecycle state (tied to real
    // display power), not the night-mode schedule - so a manual wake
    // during the window (the scenario that surfaced this whole
    // investigation) correctly resumes rendering too, the same way it
    // already resumes the local control server/etc.
    fun pause() {
        if (isPaused) return
        isPaused = true
        cancelSlideTimers()
        kenBurnsAnimator?.cancel()
        playerA.pause()
        playerB.pause()
    }

    // Re-renders the current slide fresh rather than trying to resume a
    // paused video's exact position/Ken Burns fraction - simplest correct
    // behavior for "the screen was off, nobody was watching partway
    // through anything" (same reasoning refreshSlideshow() already uses
    // for its own disruptive restart), and reuses showCurrent()'s own
    // timer/preload setup instead of duplicating it.
    fun resumeIfPaused() {
        if (!isPaused) return
        isPaused = false
        showCurrent()
    }

    fun release() {
        cancelSlideTimers()
        kenBurnsAnimator?.cancel()
        playerA.release()
        playerB.release()
        renderScript.destroy()
    }

    private fun cancelSlideTimers() {
        advanceJob?.cancel()
        latePreloadJob?.cancel()
        watchdogJob?.cancel()
        renderJob?.cancel()
    }

    private fun showCurrent() {
        // Any call reaching here (start()/goToNext()/goToPrevious()/
        // resumeIfPaused(), or a remote refresh-cache landing mid-sleep)
        // means something is actively about to render - keeps isPaused
        // accurate even for a path that bypassed pause()/resumeIfPaused(),
        // so a later real onResume() doesn't re-render on top of one that
        // already happened for another reason.
        isPaused = false
        cancelSlideTimers()
        val generation = ++renderGeneration
        val asset = assets.getOrNull(currentIndex) ?: return
        when (asset.type) {
            ImmichAssetType.VIDEO, ImmichAssetType.LIVE_PHOTO -> showVideo(asset)
            ImmichAssetType.IMAGE -> showPhoto(asset, generation)
        }
    }

    private fun showPhoto(asset: ImmichAsset, generation: Int) {
        playerA.pause()
        playerB.pause()
        // INVISIBLE, not GONE - see activity_main.xml's PlayerView
        // comment: GONE tears down the SurfaceView's native surface.
        playerViewA.visibility = View.INVISIBLE
        playerViewB.visibility = View.INVISIBLE
        photoView.visibility = View.VISIBLE

        renderJob = scope.launch {
            val bitmap = if (preloadedForIndex == currentIndex) preloadedBitmap else decodeThumbnail(asset)
            // A newer showCurrent() call already superseded this one while
            // the decode above was running - drop it rather than stomp the
            // view/timers for a photo that's no longer current (see
            // renderGeneration's own comment).
            if (generation != renderGeneration) {
                Log.d(TAG, "Dropped stale render (generation $generation, current $renderGeneration)")
                return@launch
            }
            // Ken Burns reset + the advance timer both fire right here,
            // synchronously, immediately after the bitmap is set - not
            // after applyPhotoFit()/preloadNextBitmap() below, which can
            // each suspend for a real decode. With
            // those two after applyKenBurns() (an earlier ordering), the
            // photo held at the *previous* photo's leftover zoom level
            // until applyPhotoFit()'s backdrop decode caught up (a brief
            // wrong-zoom flash), and the advance timer's actual 6s clock
            // started only once preloadNextBitmap() finished, so the
            // photo visibly sat at full zoom for that gap before
            // switching. Neither depends on photoView's scaleType/crop or
            // the next photo being preloaded, so both are safe to run
            // first; the slower decodes just run after, no longer
            // blocking anything visible.
            // Loaded once here, not separately in applyKenBurns() and
            // below - both need the same duration for this exact slide,
            // and SlideshowSettingsStore does a plain (uncached) disk
            // read each call, same cost profile as NightModeStore's own
            // no-cache pattern.
            val settings = SlideshowSettingsStore.load(context)
            photoView.setImageBitmap(bitmap)
            applyKenBurns(asset, settings)
            scheduleAdvance(settings.durationMs)
            scheduleLatePreload(settings.durationMs)
            notifyFirstFrameReady()
            applyPhotoFit(asset)
            preloadNextBitmap()
        }
    }

    // Bug #10: crop-to-fill only when the asset's own aspect ratio is
    // close enough to the panel's (see PhotoFit.shouldCover()) -
    // otherwise letterbox it (no crop) and fill the gap with a blurred
    // backdrop, instead of the old landscape-only / "portrait always
    // fits" assumption.
    private suspend fun applyPhotoFit(asset: ImmichAsset) {
        val panelWidth = photoView.width.takeIf { it > 0 } ?: photoView.resources.displayMetrics.widthPixels
        val panelHeight = photoView.height.takeIf { it > 0 } ?: photoView.resources.displayMetrics.heightPixels
        val cover = PhotoFit.shouldCover(asset.width, asset.height, panelWidth, panelHeight)
        photoView.scaleType = if (cover) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        if (cover) {
            photoBackdropView.visibility = View.GONE
        } else {
            // If visibility were flipped to VISIBLE only
            // after decodeBackdrop() finished, a letterboxed photo
            // (photoView doesn't fill the panel) would show nothing behind it
            // - a black flash - for the whole decode. Flipping it on
            // first, before the decode, means the *previous* photo's
            // backdrop bitmap (still sitting on this same ImageView)
            // stays behind the new photo for that gap instead - a soft
            // blur-to-blur swap, not a hard cut to black.
            //
            // Still a visible ~50ms swap from the old blur to the new one
            // even with that in place - same
            // decode-takes-real-time issue preloadedBitmap already solves
            // for the main photo. Mirrored here: preloadNextBitmap()
            // decodes this photo's backdrop ahead of time while the
            // *previous* photo was still showing, so by the time we get
            // here it's usually already in hand.
            photoBackdropView.visibility = View.VISIBLE
            val backdrop = if (preloadedBackdropForIndex == currentIndex) preloadedBackdropBitmap else decodeBackdrop(asset)
            photoBackdropView.setImageBitmap(backdrop)
        }
    }

    private fun showVideo(asset: ImmichAsset) {
        photoView.visibility = View.GONE
        photoBackdropView.visibility = View.GONE
        val key = asset.videoKey()

        if (backPreloadKey == key) {
            // Already warmed by a previous slide's late preload - just
            // swap which player/view is "front", no setMediaItem()/
            // prepare() on anything visible, no black flash.
            //
            // Gating this on an onRenderedFirstFrame()
            // callback (requiring proof of an actual rendered frame,
            // not just "prepare() was called") to fix an intermittent
            // flash - made it worse (100% of transitions, longer flash),
            // most likely because that callback never fires while the
            // back PlayerView is INVISIBLE (a TextureView needs an
            // actual onDraw()/updateTexImage() pass to consume a
            // decoded frame, which Android skips for invisible views -
            // unlike a SurfaceView, whose surface is composited
            // independent of the normal view-draw traversal). Reverted -
            // the real fix is more preload lead time, not a readiness
            // gate (see LATE_PRELOAD_LEAD_MS above).
            val oldFrontView = frontPlayerView()
            val oldFront = frontPlayer()
            frontIsA = !frontIsA
            oldFrontView.visibility = View.INVISIBLE
            oldFront.pause()
            frontPlayerView().visibility = View.VISIBLE
            frontPlayer().play()
        } else {
            // Not preloaded in time (first video ever, or a manual skip)
            // - fall back to loading directly on the front player.
            val path = db.cachedFilePath(asset.id, "VIDEO")
            if (path == null || !File(path).exists()) {
                advanceToNext()
                return
            }
            frontPlayer().setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            frontPlayer().prepare()
            frontPlayerView().visibility = View.VISIBLE
            frontPlayer().play()
        }
        backPreloadKey = null
        notifyFirstFrameReady()
        // preloadNextBitmap() used to only be called from
        // showPhoto(), so a video-to-photo transition never warmed the
        // next photo's bitmap ahead of time - the photo would flash to
        // black while decodeThumbnail() ran. Mirror showPhoto()'s
        // earliest-possible preload here too (no-ops if the next asset
        // isn't an image). No generation guard needed here (unlike
        // showPhoto()'s own render job) - it reads currentIndex live
        // rather than a captured asset, and showPhoto() already checks
        // preloadedForIndex == currentIndex before trusting the result,
        // so a stale/overwritten preload is at worst a wasted decode,
        // never a wrong-photo display.
        scope.launch { preloadNextBitmap() }

        val isLivePhoto = asset.type == ImmichAssetType.LIVE_PHOTO
        frontPlayer().repeatMode = if (isLivePhoto) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

        if (isLivePhoto) {
            // Loops for the slide's normal fixed dwell time, same
            // scheduling as a plain photo - not advance-on-"ended" (a
            // looping clip never naturally ends).
            val durationMs = SlideshowSettingsStore.load(context).durationMs
            scheduleAdvance(durationMs)
            scheduleLatePreload(durationMs)
        } else {
            startVideoWatchdog()
        }
    }

    // Polls the front player for: natural end (advance), a stall (never
    // started playing within VIDEO_STALL_TIMEOUT_MS - advance rather
    // than sit on a frozen/black frame forever), a hard max-duration
    // ceiling in case "ended" never fires, and the late-preload trigger
    // once within LATE_PRELOAD_LEAD_MS of finishing. One unified poll
    // loop, not separate listeners/timers - see VideoTiming for the pure
    // threshold logic this evaluates each tick.
    private fun startVideoWatchdog() {
        watchdogJob?.cancel()
        val player = frontPlayer()
        val startedAt = SystemClock.elapsedRealtime()
        var latePreloadTriggered = false

        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_POLL_INTERVAL_MS)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val position = player.currentPosition
                val duration = player.duration

                if (player.playbackState == Player.STATE_ENDED ||
                    VideoTiming.hasStalled(elapsed, position, VIDEO_STALL_TIMEOUT_MS) ||
                    VideoTiming.hasExceededMaxDuration(elapsed, VIDEO_MAX_DURATION_MS)
                ) {
                    advanceToNext()
                    return@launch
                }

                if (!latePreloadTriggered && VideoTiming.shouldTriggerLatePreload(position, duration, LATE_PRELOAD_LEAD_MS)) {
                    latePreloadTriggered = true
                    preloadNextVideoIfNeeded()
                }
            }
        }
    }

    private fun scheduleLatePreload(slideDurationMs: Long) {
        latePreloadJob = scope.launch {
            delay((slideDurationMs - LATE_PRELOAD_LEAD_MS).coerceAtLeast(0))
            preloadNextVideoIfNeeded()
        }
    }

    // Only overlaps the back player with the front one for the last
    // ~2s before a transition, not the whole slide duration - a
    // whole-clip-duration overlap causes real playback lag (two active
    // decode sessions at once on constrained hardware).
    private fun preloadNextVideoIfNeeded() {
        if (assets.isEmpty()) return
        val nextAsset = assets.getOrNull((currentIndex + 1) % assets.size) ?: return
        if (nextAsset.type != ImmichAssetType.VIDEO && nextAsset.type != ImmichAssetType.LIVE_PHOTO) return

        val key = nextAsset.videoKey()
        if (backPreloadKey == key) return

        val path = db.cachedFilePath(nextAsset.id, "VIDEO") ?: return
        if (!File(path).exists()) return

        val back = backPlayer()
        back.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        back.prepare()
        back.repeatMode = if (nextAsset.type == ImmichAssetType.LIVE_PHOTO) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        // Buffered, not playing - autoplay-on-prepare would waste decode
        // on a clip nobody's watching yet.
        back.pause()
        backPreloadKey = key
    }

    // RGB_565, not the BitmapFactory default ARGB_8888 - this is the one
    // bitmap held twice at a time (current + next, see
    // preloadNextBitmap()), at up to panel resolution, so it's the single
    // largest decoded-memory lever in the app. Halves pixel storage vs.
    // ARGB_8888. Safe here specifically
    // because these are opaque JPEG thumbnails from Immich (no alpha to
    // lose) - unlike decodeBackdrop() below, which must stay ARGB_8888
    // for ScriptIntrinsicBlur. Real tradeoff is potential gradient
    // banding on subtle skies/skin tones - visual call, not a
    // measurable one, left to a real look on the actual panel rather
    // than assumed from here.
    private suspend fun decodeThumbnail(asset: ImmichAsset): Bitmap? {
        val targetWidth = photoView.width.takeIf { it > 0 } ?: photoView.resources.displayMetrics.widthPixels
        val targetHeight = photoView.height.takeIf { it > 0 } ?: photoView.resources.displayMetrics.heightPixels
        return decodeCachedImage(asset, "THUMBNAIL", targetWidth, targetHeight, preferredConfig = Bitmap.Config.RGB_565)
    }

    // The blurred backdrop shown behind a letterboxed photo. A "downscale
    // is the blur" trick (just decoding small and letting the ImageView
    // stretch it) reads as blocky/like a stretched thumbnail rather than a
    // real blur on close inspection, so this uses an actual RenderScript
    // Gaussian blur on top of a still-small (but generously sized) decode
    // - see renderScript's comment.
    // The blur itself (decode + ScriptIntrinsicBlur) would otherwise be
    // redone from scratch every single time a photo came back around in
    // the rotation - cheap per-call, but real,
    // recurring CPU work on a device that loops the same library
    // 24/7. Persisted here the same way thumbnail/backdrop/video bytes
    // already are - one more `cached_files` row/kind, same table - so it
    // rides that table's existing staleness eviction for free
    // (AssetCacheSync.sync() deletes every file cachedFilesForAsset()
    // returns, no kind filter, before an asset's DB rows go away) rather
    // than needing its own cleanup path.
    private suspend fun decodeBackdrop(asset: ImmichAsset): Bitmap? {
        val cachedPath = db.cachedFilePath(asset.id, BACKDROP_BLURRED_CACHE_KIND)
        if (cachedPath != null && File(cachedPath).exists()) {
            return withContext(Dispatchers.IO) { BitmapFactory.decodeFile(cachedPath) }
        }

        val bitmap = decodeCachedImage(
            asset, "BACKDROP", BACKDROP_DECODE_TARGET_PX, BACKDROP_DECODE_TARGET_PX,
            preferredConfig = Bitmap.Config.ARGB_8888 // required by ScriptIntrinsicBlur's Element.U8_4
        ) ?: return null
        val blurred = withContext(Dispatchers.IO) { blur(bitmap, BACKDROP_BLUR_RADIUS) }
        withContext(Dispatchers.IO) { persistBlurredBackdrop(asset.id, blurred) }
        return blurred
    }

    // Best-effort - a failed write just means this photo's blur gets
    // recomputed next time it comes around, same as before this cache
    // existed, not a reason to fail the render.
    private fun persistBlurredBackdrop(assetId: String, bitmap: Bitmap) {
        try {
            val destination = File(assetCacheDir(context), "$assetId-backdrop-blurred")
            FileOutputStream(destination).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            db.recordCachedFile(assetId, BACKDROP_BLURRED_CACHE_KIND, destination.absolutePath, destination.length())
        } catch (error: Exception) {
            Log.w(TAG, "Failed to persist blurred backdrop for $assetId - will recompute next time", error)
        }
    }

    private fun blur(bitmap: Bitmap, radius: Float): Bitmap {
        val input = Allocation.createFromBitmap(renderScript, bitmap)
        val output = Allocation.createTyped(renderScript, input.type)
        val script = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript))
        script.setRadius(radius)
        script.setInput(input)
        script.forEach(output)
        output.copyTo(bitmap)
        script.destroy()
        input.destroy()
        output.destroy()
        return bitmap
    }

    private suspend fun decodeCachedImage(
        asset: ImmichAsset,
        kind: String,
        targetWidth: Int,
        targetHeight: Int,
        preferredConfig: Bitmap.Config? = null
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            val path = db.cachedFilePath(asset.id, kind) ?: return@withContext null
            if (!File(path).exists()) return@withContext null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val sampleSize = BitmapSampling.calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                if (preferredConfig != null) inPreferredConfig = preferredConfig
            }
            BitmapFactory.decodeFile(path, options)
        }

    // Replaces an earlier 4-way ObjectAnimator/AnimatorSet approach and
    // its "fps cap" via ValueAnimator.setFrameDelay() - that API's own
    // docs say the requested delay "may be ignored when the animation
    // system uses an external timing source, such as vsync", and a
    // direct measurement (an addUpdateListener counting real calls/sec,
    // logged to logcat) confirmed it: actual rate was ~55-62/sec, not 30,
    // regardless of the requested delay. This
    // replaces the property-animator approach with a single 0f..1f
    // ValueAnimator whose update listener gates the actual
    // photoView.scaleX/scaleY/pivotX/pivotY writes (and the invalidate/
    // layout/draw pass each one triggers) to real elapsed time, not
    // requested frame delay - the listener itself still ticks at
    // whatever rate vsync drives it, but only a fraction of those ticks
    // actually touch the view now. The interpolated position is computed
    // from the animator's own fraction (driven by real elapsed time
    // against `duration`), not by counting applied frames, so a skipped
    // tick never causes drift - the zoom still finishes exactly on time
    // regardless of how many ticks were skipped to get there.
    private fun applyKenBurns(asset: ImmichAsset, settings: SlideshowSettingsConfig) {
        kenBurnsAnimator?.cancel()

        val start = KenBurnsMath.startFrame()
        val end = KenBurnsMath.endFrame(asset.faceX, asset.faceY, settings.endZoom)

        photoView.scaleX = start.scale
        photoView.scaleY = start.scale
        photoView.pivotX = photoView.width * start.focusX
        photoView.pivotY = photoView.height * start.focusY

        var lastAppliedMs = 0L
        val frameIntervalMs = 1_000L / KEN_BURNS_TARGET_FPS

        kenBurnsAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = settings.durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val now = SystemClock.elapsedRealtime()
                val fraction = animator.animatedValue as Float
                // Always apply the final frame (fraction >= 1f) even if
                // it lands inside the same interval as the last one - a
                // skipped last frame would leave the zoom visibly short
                // of KenBurnsMath's end scale/focus.
                if (fraction < 1f && now - lastAppliedMs < frameIntervalMs) return@addUpdateListener
                lastAppliedMs = now

                photoView.scaleX = start.scale + (end.scale - start.scale) * fraction
                photoView.scaleY = photoView.scaleX
                photoView.pivotX = photoView.width * (start.focusX + (end.focusX - start.focusX) * fraction)
                photoView.pivotY = photoView.height * (start.focusY + (end.focusY - start.focusY) * fraction)
            }
            start()
        }
    }

    private suspend fun preloadNextBitmap() {
        if (assets.isEmpty()) return
        val nextIndex = (currentIndex + 1) % assets.size
        val nextAsset = assets.getOrNull(nextIndex) ?: return
        if (nextAsset.type != ImmichAssetType.IMAGE) return
        preloadedBitmap = decodeThumbnail(nextAsset)
        preloadedForIndex = nextIndex

        // Same preload treatment for the backdrop as the main thumbnail
        // above (see applyPhotoFit()'s comment on why) - only decode one
        // if the next photo will actually need it (letterboxed), same
        // shouldCover() check applyPhotoFit() itself makes.
        val panelWidth = photoView.width.takeIf { it > 0 } ?: photoView.resources.displayMetrics.widthPixels
        val panelHeight = photoView.height.takeIf { it > 0 } ?: photoView.resources.displayMetrics.heightPixels
        if (PhotoFit.shouldCover(nextAsset.width, nextAsset.height, panelWidth, panelHeight)) {
            preloadedBackdropBitmap = null
            preloadedBackdropForIndex = -1
        } else {
            preloadedBackdropBitmap = decodeBackdrop(nextAsset)
            preloadedBackdropForIndex = nextIndex
        }
    }

    private fun scheduleAdvance(delayMs: Long) {
        advanceJob = scope.launch {
            delay(delayMs)
            advanceToNext()
        }
    }

    private fun advanceToNext() = goToNext()
}
