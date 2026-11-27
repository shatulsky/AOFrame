package dev.aoframe.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class SyncRetryTest {
    @Test
    fun firstSuccessReturnsAtOnceWithoutWaiting() {
        val waits = mutableListOf<Long>()
        val result = runBlocking { retryUpTo(3, 5_000, { waits += it }) { "ok" } }
        assertEquals("ok", result)
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun succeedsOnTheLastAttemptAfterTwoWaits() {
        val waits = mutableListOf<Long>()
        var calls = 0
        val result = runBlocking {
            retryUpTo(3, 5_000, { waits += it }) {
                if (++calls < 3) throw IOException("down") else "ok"
            }
        }
        assertEquals("ok", result)
        assertEquals(listOf(5_000L, 5_000L), waits)
    }

    @Test
    fun allFailuresReturnNullWithNoWaitAfterTheLastTry() {
        val waits = mutableListOf<Long>()
        val failed = mutableListOf<Int>()
        val result = runBlocking {
            retryUpTo<String>(3, 5_000, { waits += it }, { n, _ -> failed += n }) { throw IOException("down") }
        }
        assertNull(result)
        assertEquals(listOf(5_000L, 5_000L), waits)
        assertEquals(listOf(1, 2, 3), failed)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrown() {
        runBlocking { retryUpTo<String>(3, 1, {}) { throw CancellationException("stop") } }
    }
}
