package xyz.nekobyte.nekobox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CloseOnceHolderTest {
    private class Client : Closeable {
        var closes = 0
        override fun close() {
            closes++
        }
    }

    private var created = 0
    private val holder = CloseOnceHolder {
        created++
        Client()
    }

    @Test
    fun closeBeforeFirstGetNeverCreates() {
        holder.close()
        assertNull(holder.get())
        assertEquals(0, created)
    }

    @Test
    fun getCreatesOnceAndCloseClosesItOnce() {
        val client = holder.get()!!
        assertSame(client, holder.get())
        holder.close()
        holder.close()
        assertEquals(1, created)
        assertEquals(1, client.closes)
        assertNull(holder.get())
        assertEquals(1, created)
    }

    @Test(timeout = 20_000)
    fun closeDuringCreationClosesTheNewClient() {
        val creating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clients = mutableListOf<Client>()
        val slow = CloseOnceHolder {
            creating.countDown()
            release.await(5, TimeUnit.SECONDS)
            Client().also { clients += it }
        }
        val getter = thread { slow.get() }
        assertTrue(creating.await(5, TimeUnit.SECONDS))
        val closer = thread { slow.close() }
        // close() must wait on the lock get() holds during creation; without it, close would finish here.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val state = closer.state
            if (state != Thread.State.NEW && state != Thread.State.RUNNABLE) break
            Thread.sleep(1)
        }
        try {
            assertEquals(Thread.State.BLOCKED, closer.state)
        } finally {
            release.countDown()
        }
        getter.join(5_000)
        closer.join(5_000)
        assertFalse(getter.isAlive || closer.isAlive)
        assertEquals(1, clients.size)
        assertEquals(1, clients[0].closes)
        assertNull(slow.get())
    }
}
