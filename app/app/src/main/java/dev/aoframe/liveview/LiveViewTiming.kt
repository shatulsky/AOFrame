package dev.aoframe.liveview

/**
 * Pure rules for the live camera view, kept apart from the player so
 * plain JVM tests can cover them.
 */
object LiveViewTiming {
    /** Live view closes by itself after this long, so a forgotten view cannot run all night. */
    const val MAX_DURATION_MS = 5 * 60 * 1000L

    /** Only RTSP addresses are accepted: the player is built for RTSP, and other schemes would fail late. */
    fun isUsableUrl(url: String?): Boolean =
        url != null && url.startsWith("rtsp://", ignoreCase = true) && url.length > "rtsp://".length
}
