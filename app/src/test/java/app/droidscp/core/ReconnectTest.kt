package app.droidscp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private class FakeFs(val tag: String, override val site: Site = Site(host = "h")) : RemoteFs {
    var closed = false
    var closeFails = false
    override val isAlive get() = !closed
    override val supportsResume = true
    override val supportsChmod = true
    override fun home() = "/home/$tag"
    override fun canonical(path: String) = path
    override fun list(path: String): List<RemoteEntry> = emptyList()
    override fun stat(path: String): RemoteEntry? = null
    override fun mkdir(path: String) {}
    override fun mkdirs(path: String) {}
    override fun rename(from: String, to: String) {}
    override fun chmod(path: String, mask: Int) {}
    override fun delete(entry: RemoteEntry) {}
    override fun readBytes(path: String, maxBytes: Int) = tag.toByteArray()
    override fun writeBytes(path: String, data: ByteArray) {}
    override fun download(remotePath: String, out: OutputStream, offset: Long, onBytes: (Long) -> Unit, check: () -> Unit) {}
    override fun upload(remotePath: String, length: Long, input: InputStream, offset: Long, onBytes: (Long) -> Unit, check: () -> Unit) {}
    override fun close() {
        closed = true
        if (closeFails) throw IOException("gagal menutup")
    }
}

class ReconnectTest {
    @Test
    fun retrySucceedsAfterFailuresAndSleepsBetweenAttempts() {
        val slept = mutableListOf<Long>()
        var calls = 0
        val r = retryWithBackoff(listOf(0L, 10L, 20L), { false }, { slept += it }) { i ->
            calls++
            if (i < 2) throw IOException("gagal $i")
            "ok"
        }
        assertEquals("ok", r)
        assertEquals(3, calls)
        assertEquals(listOf(10L, 20L), slept) // jeda 0 tidak tidur
    }

    @Test
    fun retryThrowsLastErrorAfterAllAttempts() {
        val e = assertThrows(IOException::class.java) {
            retryWithBackoff(listOf(0L, 0L, 0L), { false }, { }) { i -> throw IOException("gagal $i") }
        }
        assertEquals("gagal 2", e.message)
    }

    @Test
    fun fatalErrorStopsImmediately() {
        var calls = 0
        assertThrows(HostKeyChangedException::class.java) {
            retryWithBackoff(listOf(0L, 5L, 5L), ::isFatalConnectError, { }) {
                calls++
                throw HostKeyChangedException("h", 22, "a", "b")
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun interruptedIsNeverSwallowed() {
        var calls = 0
        assertThrows(InterruptedException::class.java) {
            retryWithBackoff(listOf(0L, 0L), { false }, { }) { calls++; throw InterruptedException() }
        }
        assertEquals(1, calls)
    }

    @Test
    fun emptyDelaysRejected() {
        assertThrows(IllegalArgumentException::class.java) { retryWithBackoff(emptyList<Long>(), { false }) { 1 } }
    }

    @Test
    fun fatalClassification() {
        assertTrue(isFatalConnectError(HostKeyUnknownException("h", 22, "fp", "ssh-ed25519")))
        assertTrue(isFatalConnectError(HostKeyChangedException("h", 22, "a", "b")))
        assertTrue(isFatalConnectError(IOException("bungkus", HostKeyChangedException("h", 22, "a", "b"))))
        assertTrue(isFatalConnectError(IOException("Login gagal: password salah")))
        assertFalse(isFatalConnectError(IOException("Connection reset")))
        assertFalse(isFatalConnectError(java.net.SocketTimeoutException("timeout")))
    }

    @Test
    fun fatalClassificationSurvivesCauseCycle() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(isFatalConnectError(a)) // tidak boleh berputar tanpa henti
    }

    @Test
    fun swapRoutesCallsToNewSessionAndClosesOldOne() {
        val a = FakeFs("a")
        val b = FakeFs("b")
        val r = ReconnectableFs(a)
        assertEquals("/home/a", r.home())
        r.swap(b)
        assertEquals("/home/b", r.home())
        assertTrue(a.closed)
        assertFalse(b.closed)
        assertTrue(r.isAlive)
    }

    @Test
    fun swapIgnoresCloseFailureAndSameInstance() {
        val a = FakeFs("a").apply { closeFails = true }
        val r = ReconnectableFs(a)
        r.swap(FakeFs("b")) // close() lama melempar, tidak boleh merambat
        assertEquals("/home/b", r.home())
        val c = FakeFs("c")
        val r2 = ReconnectableFs(c)
        r2.swap(c)
        assertFalse(c.closed) // mengganti dengan dirinya sendiri tidak boleh menutupnya
        assertSame(c.site, r2.site)
    }

    @Test
    fun delaysAreBounded() {
        assertEquals(0L, RECONNECT_DELAYS_MS.first())
        assertTrue(RECONNECT_DELAYS_MS.sum() <= 15_000)
    }
}

class BookmarkAndHostKeyTest {
    @Test
    fun toggleBookmarkAddsNormalizesAndRemoves() {
        val one = toggleBookmark(emptyList(), "/var/www/")
        assertEquals(listOf("/var/www"), one)
        assertEquals(emptyList<String>(), toggleBookmark(one, "/var/www"))
        assertEquals(listOf("/var/www", "/"), toggleBookmark(one, "/"))
    }

    @Test
    fun splitHostKeyHandlesIpv6AndGarbage() {
        assertEquals("example.com" to 22, splitHostKey("example.com:22"))
        assertEquals("::1" to 2222, splitHostKey("::1:2222"))
        assertEquals(null, splitHostKey("nohost"))
        assertEquals(null, splitHostKey(":22"))
        assertEquals(null, splitHostKey("host:abc"))
    }

    @Test
    fun formatOctalPadsAndMasks() {
        assertEquals("0755", formatOctal(493))
        assertEquals("0000", formatOctal(0))
        assertEquals("0644", formatOctal(0x8000 or 420)) // bit tipe berkas dibuang
    }
}
