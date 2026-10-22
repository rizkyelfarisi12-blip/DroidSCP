package app.droidscp.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class HostnameMatcherTest {
    private fun m(host: String, dns: List<String> = emptyList(), ips: List<String> = emptyList(), cn: String? = null) =
        HostnameMatcher.matches(host, dns, ips, cn)

    @Test fun exactMatchIgnoresCaseAndTrailingDot() {
        assertTrue(m("FTP.Example.com.", dns = listOf("ftp.example.com")))
        assertFalse(m("ftp.example.com", dns = listOf("ftp2.example.com")))
    }

    @Test fun wildcardMatchesExactlyOneLabel() {
        val dns = listOf("*.example.com")
        assertTrue(m("ftp.example.com", dns))
        assertFalse("apex tidak cocok", m("example.com", dns))
        assertFalse("dua label tidak cocok", m("a.b.example.com", dns))
        assertFalse("label kosong", m(".example.com", dns))
    }

    @Test fun overBroadOrOddWildcardsAreRejected() {
        assertFalse(m("example.com", listOf("*.com")))
        assertFalse(m("foo.example.com", listOf("f*.example.com")))
        assertFalse(m("foo.example.com", listOf("foo.*.com")))
        assertFalse(m("foo.example.com", listOf("*")))
        assertFalse(m("a.b.example.com", listOf("*.*.example.com")))
    }

    @Test fun suffixTrickDoesNotMatch() {
        // "evilexample.com" bukan di bawah "example.com".
        assertFalse(m("evilexample.com", listOf("*.example.com")))
        assertFalse(m("example.com.evil.net", listOf("example.com")))
    }

    @Test fun ipLiteralsMatchOnlyIpSans() {
        assertTrue(m("192.0.2.7", ips = listOf("192.0.2.7")))
        assertFalse("IP tidak boleh cocok dengan entri DNS", m("192.0.2.7", dns = listOf("192.0.2.7")))
        assertFalse(m("192.0.2.8", ips = listOf("192.0.2.7")))
    }

    @Test fun ipv6IsNormalized() {
        assertTrue(m("2001:db8:0:0:0:0:0:1", ips = listOf("2001:db8::1")))
        assertTrue(m("::1", ips = listOf("0:0:0:0:0:0:0:1")))
    }

    @Test fun emptyHostNeverMatches() {
        assertFalse(m("", dns = listOf("")))
        assertFalse(m(".", dns = listOf("*.example.com")))
    }

    // ---- terhadap sertifikat sungguhan (dibuat dengan openssl; kunci privat dibuang)
    private fun cert(name: String): X509Certificate =
        javaClass.getResourceAsStream("/$name.pem")!!.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    @Test fun realCertificateWithSan() {
        val c = cert("selfsigned")
        assertTrue(HostnameMatcher.matches("ftp.test.local", c))
        assertTrue(HostnameMatcher.matches("x.wild.test", c))
        assertFalse(HostnameMatcher.matches("wild.test", c))
        assertTrue(HostnameMatcher.matches("192.0.2.7", c))
        assertTrue(HostnameMatcher.matches("2001:db8::1", c))
        assertFalse(HostnameMatcher.matches("other.test.local", c))
    }

    @Test fun commonNameIsIgnoredWhenSanPresent() {
        // selfsigned: CN=ftp.test.local ada juga di SAN; other: CN=other.test.local. Host yang hanya ada di CN, bukan SAN, ditolak.
        assertFalse(HostnameMatcher.matches("cn.only.test", cert("other")))
    }

    @Test fun commonNameIsFallbackWhenNoSan() {
        val c = cert("cnonly")
        assertTrue(HostnameMatcher.matches("cn.only.test", c))
        assertFalse(HostnameMatcher.matches("other.test", c))
    }
}
