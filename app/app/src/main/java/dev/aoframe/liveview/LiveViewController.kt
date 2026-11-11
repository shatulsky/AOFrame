package dev.aoframe.liveview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

private const val TAG = "LiveViewController"

/**
 * Shows one live RTSP stream full screen over the slideshow.
 *
 * Main thread only: the control server posts its calls to the main
 * handler before it touches this class. The player is created on start
 * and released on stop, so an idle frame holds no decoder (the device
 * has about 1 GB of RAM). Audio plays, because a camera view is for
 * hearing the room as well as seeing it.
 *
 * `onStarted` and `onStopped` let the activity pause and resume the
 * slideshow around a live session.
 */
@OptIn(UnstableApi::class)
class LiveViewController(
    private val context: Context,
    private val playerView: PlayerView,
    private val urlProvider: () -> String?,
    private val onStarted: () -> Unit,
    private val onStopped: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var player: ExoPlayer? = null
    private val autoStop = Runnable { stop() }

    val isActive: Boolean get() = player != null

    val isConfigured: Boolean get() = LiveViewTiming.isUsableUrl(urlProvider())

    /** Returns false when no usable address is configured. A start while active only restarts the timer. */
    fun start(): Boolean {
        val url = urlProvider()
        if (!LiveViewTiming.isUsableUrl(url)) return false
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, LiveViewTiming.MAX_DURATION_MS)
        if (player != null) return true

        onStarted()
        // Small buffers keep the delay low. RTP over TCP, so the stream
        // survives Wi-Fi packet loss without picture damage.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 5_000, 500, 500)
            .build()
        val newPlayer = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
        newPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "live view failed", error)
                stop()
            }
        })
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(url!!))
        newPlayer.setMediaSource(source)
        newPlayer.playWhenReady = true
        newPlayer.prepare()
        playerView.player = newPlayer
        playerView.visibility = View.VISIBLE
        player = newPlayer
        return true
    }

    fun stop() {
        handler.removeCallbacks(autoStop)
        val current = player ?: return
        player = null
        playerView.visibility = View.INVISIBLE
        playerView.player = null
        current.release()
        onStopped()
    }
}
