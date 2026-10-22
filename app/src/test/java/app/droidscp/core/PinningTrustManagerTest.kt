package app.droidscp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class PinningTrustManagerTest {
    private fun cert(name: String): X509Certificate =
        javaClass.getResourceAsStream("/$name.pem")!!.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    private class MemStore(val map: MutableMap<String, String> = mutableMapOf()) : KnownHostsStore {
        override fun get(host: String, port: Int) = map["$host:$port"]
        override fun put(host: String, port: Int, fingerprint: String) { map["$host:$port"] = fingerprint }
    }

    private object Trusting : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private object Distrusting : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("tidak dikenal CA") }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val leaf = cert("selfsigned")

    @Test fun fingerprintFormat() {
        val fp = certFingerprint(leaf)
        assertTrue(fp, Regex("""SHA256:[A-Za-z0-9+/]{43}""").matches(fp)) // 32 byte -> 43 karakter base64 tanpa padding
        assertEquals(fp, certFingerprint(cert("selfsigned")))
        assertTrue(fp != certFingerprint(cert("other")))
    }

    @Test fun systemTrustedAndHostMatchesNeedsNoPin() {
        val store = MemStore()
        val tm = PinningTrustManager("ftp.test.local", 21, store, Trusting)
        tm.checkServerTrusted(arrayOf(leaf), "ECDHE_ECDSA")
        assertNull(tm.failure)
        assertTrue("tidak boleh menulis pin sendiri", store.map.isEmpty())
    }

    @Test fun systemTrustedButWrongHostFallsBackToPinAndAsks() {
        // CA valid untuk host lain tidak boleh dipakai untuk host ini.
        val tm = PinningTrustManager("evil.example", 21, MemStore(), Trusting)
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(leaf), "x") }
        val f = tm.failure as HostKeyUnknownException
        assertEquals(certFingerprint(leaf), f.fingerprint)
        assertEquals("evil.example", f.host)
    }

    @Test fun unknownSelfSignedAsksUser() {
        val tm = PinningTrustManager("ftp.test.local", 21, MemStore(), Distrusting)
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(leaf), "x") }
        val f = tm.failure as HostKeyUnknownException
        assertEquals(certFingerprint(leaf), f.fingerprint)
        assertEquals(21, f.port)
    }

    @Test fun pinnedCertificateIsAcceptedEvenIfSystemDistrustsIt() {
        val store = MemStore(mutableMapOf("ftp.test.local:21" to certFingerprint(leaf)))
        val tm = PinningTrustManager("ftp.test.local", 21, store, Distrusting)
        tm.checkServerTrusted(arrayOf(leaf), "x")
        assertNull(tm.failure)
    }

    @Test fun pinnedCertificateIsAcceptedEvenForUnmatchedHostName() {
        // Pengguna sengaja mempercayai sertifikat ini untuk alamat (mis. IP) yang tidak tercantum di dalamnya.
        val store = MemStore(mutableMapOf("10.0.0.5:21" to certFingerprint(leaf)))
        PinningTrustManager("10.0.0.5", 21, store, Distrusting).checkServerTrusted(arrayOf(leaf), "x")
    }

    @Test fun changedCertificateIsReportedWithBothFingerprints() {
        val store = MemStore(mutableMapOf("ftp.test.local:21" to certFingerprint(cert("other"))))
        val tm = PinningTrustManager("ftp.test.local", 21, store, Distrusting)
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(leaf), "x") }
        val f = tm.failure as HostKeyChangedException
        assertEquals(certFingerprint(cert("other")), f.oldFingerprint)
        assertEquals(certFingerprint(leaf), f.newFingerprint)
    }

    @Test fun pinIsPerPort() {
        val store = MemStore(mutableMapOf("ftp.test.local:21" to certFingerprint(leaf)))
        val tm = PinningTrustManager("ftp.test.local", 990, store, Distrusting)
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(leaf), "x") }
        assertTrue(tm.failure is HostKeyUnknownException)
    }

    @Test fun withoutSystemTrustManagerOnlyPinsCount() {
        val tm = PinningTrustManager("ftp.test.local", 21, MemStore(), null)
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(leaf), "x") }
        assertTrue(tm.failure is HostKeyUnknownException)
        assertEquals(0, tm.acceptedIssuers.size)
    }

    @Test fun emptyChainIsRejected() {
        assertThrows(CertificateException::class.java) {
            PinningTrustManager("h", 21, MemStore(), Trusting).checkServerTrusted(emptyArray(), "x")
        }
    }

    @Test fun clientAuthIsNotSupported() {
        assertThrows(CertificateException::class.java) {
            PinningTrustManager("h", 21, MemStore(), Trusting).checkClientTrusted(arrayOf(leaf), "x")
        }
    }
}
