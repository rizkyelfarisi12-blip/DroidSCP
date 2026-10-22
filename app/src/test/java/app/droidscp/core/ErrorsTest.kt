package app.droidscp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ErrorsTest {
    private fun starts(e: Throwable, prefix: String) = assertTrue("${friendlyError(e)} !~ $prefix", friendlyError(e).startsWith(prefix))

    @Test fun network() {
        starts(UnknownHostException("foo.example"), "Alamat server tidak ditemukan")
        starts(ConnectException("Connection refused"), "Server menolak koneksi")
        starts(IOException("failed: Connection refused (ECONNREFUSED)"), "Server menolak koneksi")
        starts(SocketTimeoutException("connect timed out"), "Server tidak menjawab")
        starts(IOException("Network is unreachable"), "Server tidak terjangkau")
    }

    @Test fun walksCauseChainOutsideIn() {
        starts(IOException("bungkus", UnknownHostException("x")), "Alamat server tidak ditemukan")
        starts(RuntimeException("a", RuntimeException("b", SocketTimeoutException("t"))), "Server tidak menjawab")
    }

    @Test fun authAndTls() {
        class UserAuthException(m: String) : Exception(m)
        starts(UserAuthException("Exhausted available authentication methods"), "Login ditolak")
        starts(IOException("Login gagal: 530 Login incorrect"), "Login ditolak")
        starts(SSLHandshakeException("PKIX path building failed"), "Koneksi aman gagal")
    }

    @Test fun hostKeyExceptionsAreNotMisreadAsCertificateErrors() {
        val e = HostKeyChangedException("h", 22, "a", "b")
        // pesan "Kunci host ... berubah" tidak boleh diubah menjadi pesan sertifikat
        assertEquals(e.message, friendlyError(e))
    }

    @Test fun serverSideProblems() {
        starts(IOException("Permission denied"), "Akses ditolak")
        starts(IOException("ls: cannot access '/x': No such file or directory"), "Berkas atau folder tidak ditemukan")
        starts(IOException("No space left on device"), "Ruang penyimpanan penuh")
        starts(IOException("Connection reset"), "Koneksi terputus")
        starts(EOFException("Server menutup koneksi SCP sebelum berkas diterima"), "Koneksi terputus")
    }

    @Test fun unknownFallsBackToOriginalMessageOrClassName() {
        assertEquals("Berkas lokal lebih besar dari berkas di server", friendlyError(IOException("Berkas lokal lebih besar dari berkas di server")))
        assertEquals("IllegalStateException", friendlyError(IllegalStateException()))
    }
}
