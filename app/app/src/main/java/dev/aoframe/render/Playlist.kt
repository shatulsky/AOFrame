package dev.aoframe.render

import dev.aoframe.immich.ImmichAsset
import dev.aoframe.immich.ImmichAssetType

/**
 * The slideshow's rotation order and position, without any view or player
 * (Humble Object: SlideshowRenderer only renders [current]). Plain JVM, so
 * the order rules are unit tested:
 *
 * - [start] shuffles (when enabled) and begins at the first item.
 * - [update] replaces the list without moving off the item on screen: it
 *   keeps the current item's position by id, else goes to the first item.
 * - [next] wraps past the end to the first item and reshuffles for the new
 *   loop; [previous] wraps to the last item without a reshuffle.
 *
 * [shuffle] is a parameter so tests can use a fixed order.
 */
class Playlist<T>(
    private val idOf: (T) -> String,
    private val shuffle: (List<T>) -> List<T> = { it.shuffled() }
) {
    /** Off for the webcam-only mode: its few clips read better in a fixed order. */
    var shuffleEnabled = true

    var items: List<T> = emptyList()
        private set
    var index = 0
        private set

    val current: T? get() = items.getOrNull(index)

    /** Index of the item after the current one (wraps), or null for an empty list. */
    val nextIndex: Int? get() = if (items.isEmpty()) null else (index + 1) % items.size

    val upNext: T? get() = nextIndex?.let { items.getOrNull(it) }

    private fun ordered(list: List<T>) = if (shuffleEnabled) shuffle(list) else list

    /** Starts a new rotation at its first item. Returns false when there is nothing to show. */
    fun start(newItems: List<T>): Boolean {
        items = ordered(newItems)
        index = 0
        return items.isNotEmpty()
    }

    /**
     * Replaces the list and keeps showing the current item if it is still in
     * it. An empty [newItems] changes nothing and returns false.
     */
    fun update(newItems: List<T>): Boolean {
        if (newItems.isEmpty()) return false
        val currentId = current?.let(idOf)
        items = ordered(newItems)
        val kept = currentId?.let { id -> items.indexOfFirst { idOf(it) == id } } ?: -1
        index = if (kept >= 0) kept else 0
        return true
    }

    /** Moves to the next item; past the end, reshuffles and starts at the first. False when empty. */
    fun next(): Boolean {
        if (items.isEmpty()) return false
        if (index + 1 >= items.size) {
            items = ordered(items)
            index = 0
        } else {
            index++
        }
        return true
    }

    /** Moves to the previous item; before the first, wraps to the last. False when empty. */
    fun previous(): Boolean {
        if (items.isEmpty()) return false
        index = (index - 1 + items.size) % items.size
        return true
    }
}

/**
 * The asset id whose video file is played: a Live Photo's motion clip is a
 * separate, hidden asset ([ImmichAsset.videoId]); a plain video is its own asset.
 */
fun ImmichAsset.videoKey(): String =
    if (type == ImmichAssetType.LIVE_PHOTO) (videoId ?: id) else id

/** True for the asset types that play in a video player. */
val ImmichAsset.isPlayedAsVideo: Boolean
    get() = type == ImmichAssetType.VIDEO || type == ImmichAssetType.LIVE_PHOTO
