package dev.aoframe.render

import dev.aoframe.immich.ImmichAsset
import dev.aoframe.immich.ImmichAssetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTest {
    /** Reverses instead of a random shuffle, and counts the calls, so the order is known. */
    private var shuffles = 0
    private fun playlist() = Playlist<String>(idOf = { it }, shuffle = { shuffles++; it.reversed() })

    @Test
    fun startShufflesAndBeginsAtTheFirstItem() {
        val p = playlist()
        assertTrue(p.start(listOf("a", "b", "c")))
        assertEquals(listOf("c", "b", "a"), p.items)
        assertEquals("c", p.current)
        assertEquals(1, shuffles)
    }

    @Test
    fun startWithoutShuffleKeepsTheOrder() {
        val p = playlist().apply { shuffleEnabled = false }
        p.start(listOf("a", "b"))
        assertEquals(listOf("a", "b"), p.items)
        assertEquals(0, shuffles)
    }

    @Test
    fun startOfAnEmptyListShowsNothingEvenAfterAnEarlierRotation() {
        val p = playlist().apply { shuffleEnabled = false }
        p.start(listOf("a", "b", "c"))
        p.next()
        assertFalse(p.start(emptyList()))
        assertNull(p.current)
        assertNull(p.nextIndex)
        assertNull(p.upNext)
    }

    @Test
    fun nextWalksTheListThenWrapsAndReshufflesForTheNewLoop() {
        val p = playlist()
        p.start(listOf("a", "b", "c"))
        val seen = mutableListOf(p.current)
        repeat(2) { p.next(); seen += p.current }
        assertEquals(listOf("c", "b", "a"), seen)
        assertEquals(1, shuffles)
        assertTrue(p.next())
        assertEquals(0, p.index)
        assertEquals(listOf("a", "b", "c"), p.items)
        assertEquals(2, shuffles)
    }

    @Test
    fun previousWrapsToTheLastItemWithoutAReshuffle() {
        val p = playlist().apply { shuffleEnabled = false }
        p.start(listOf("a", "b", "c"))
        assertTrue(p.previous())
        assertEquals("c", p.current)
        assertTrue(p.previous())
        assertEquals("b", p.current)
        assertEquals(0, shuffles)
    }

    @Test
    fun nextAndPreviousOnAnEmptyListDoNothing() {
        val p = playlist()
        assertFalse(p.next())
        assertFalse(p.previous())
        assertEquals(0, p.index)
    }

    @Test
    fun aOneItemListWrapsOntoItself() {
        val p = playlist().apply { shuffleEnabled = false }
        p.start(listOf("only"))
        assertEquals(0, p.nextIndex)
        assertEquals("only", p.upNext)
        p.next()
        assertEquals("only", p.current)
    }

    @Test
    fun upNextWrapsFromTheLastItemToTheFirst() {
        val p = playlist().apply { shuffleEnabled = false }
        p.start(listOf("a", "b", "c"))
        assertEquals("b", p.upNext)
        p.previous()
        assertEquals(0, p.nextIndex)
        assertEquals("a", p.upNext)
    }

    @Test
    fun updateKeepsShowingTheCurrentItemAtItsNewPosition() {
        val p = playlist().apply { shuffleEnabled = false }
        p.start(listOf("a", "b", "c"))
        p.next()
        assertTrue(p.update(listOf("x", "y", "b", "z")))
        assertEquals("b", p.current)
        assertEquals(2, p.index)
    }

    @Test
    fun updateGoesToTheFirstItemWhenTheCurrentOneIsGone() {
        val p = playlist().apply { shuffleEnabled = false }
        p.start(listOf("a", "b"))
        p.next()
        p.update(listOf("x", "y"))
        assertEquals("x", p.current)
    }

    @Test
    fun updateMatchesByIdNotByEquality() {
        val p = Playlist<Pair<String, Int>>(idOf = { it.first }).apply { shuffleEnabled = false }
        p.start(listOf("a" to 1, "b" to 1))
        p.next()
        p.update(listOf("a" to 2, "b" to 2))
        assertEquals("b" to 2, p.current)
    }

    @Test
    fun updateWithAnEmptyListChangesNothing() {
        val p = playlist().apply { shuffleEnabled = false }
        p.start(listOf("a", "b"))
        p.next()
        assertFalse(p.update(emptyList()))
        assertEquals(listOf("a", "b"), p.items)
        assertEquals("b", p.current)
    }

    @Test
    fun updateBeforeAnyStartBeginsAtTheFirstItem() {
        val p = playlist()
        assertTrue(p.update(listOf("a", "b")))
        assertEquals(0, p.index)
        assertEquals(1, shuffles)
    }

    @Test
    fun videoKeyOfALivePhotoIsItsClipAndOfAVideoItsOwnId() {
        fun asset(type: ImmichAssetType, videoId: String?) = ImmichAsset(id = "still", type = type, videoId = videoId, width = 1, height = 1)
        assertEquals("clip", asset(ImmichAssetType.LIVE_PHOTO, "clip").videoKey())
        assertEquals("still", asset(ImmichAssetType.LIVE_PHOTO, null).videoKey())
        assertEquals("still", asset(ImmichAssetType.VIDEO, "ignored").videoKey())
        assertTrue(asset(ImmichAssetType.VIDEO, null).isPlayedAsVideo)
        assertTrue(asset(ImmichAssetType.LIVE_PHOTO, "clip").isPlayedAsVideo)
        assertFalse(asset(ImmichAssetType.IMAGE, null).isPlayedAsVideo)
    }
}
