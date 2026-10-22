package app.droidscp.core

import java.net.InetAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/** Fingerprint sertifikat dengan format yang sama seperti fingerprint kunci host SSH. */
fun certFingerprint(cert: X509Certificate): String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.encoded))

/**
 * Pencocokan nama host terhadap sertifikat (versi ringkas RFC 6125).
 * - Bila ada SAN, hanya SAN yang dipakai; CN hanya sebagai cadangan jika SAN tidak ada.
 * - Wildcard hanya sah sebagai label paling kiri utuh ("*.contoh.com"), cocok tepat satu label,
 *   tidak cocok untuk apex ("contoh.com") maupun "*.com".
 * - Host berupa alamat IP hanya cocok dengan SAN bertipe IP.
 */
object HostnameMatcher {
    fun matches(host: String, cert: X509Certificate): Boolean {
        val dns = mutableListOf<String>()
        val ips = mutableListOf<String>()
        val sans = runCatching { cert.subjectAlternativeNames }.getOrNull()
        sans?.forEach { entry ->
            val type = entry.getOrNull(0) as? Int
            val value = entry.getOrNull(1) as? String
            if (value != null) when (type) {
                2 -> dns += value
                7 -> ips += value
            }
        }
        val cn = if (dns.isEmpty() && ips.isEmpty()) commonName(cert) else null
        return matches(host, dns, ips, cn)
    }

    fun matches(host: String, dnsNames: List<String>, ipAddresses: List<String>, commonName: String?): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return false
        if (isIpLiteral(h)) {
            val want = normalizeIp(h) ?: return false
            return ipAddresses.any { normalizeIp(it.trim()) == want }
        }
        return (dnsNames + listOfNotNull(commonName)).any { matchPattern(h, it.trim().trimEnd('.').lowercase()) }
    }

    private fun matchPattern(host: String, pattern: String): Boolean {
        if (pattern.isEmpty()) return false
        if ('*' !in pattern) return host == pattern
        if (!pattern.startsWith("*.")) return false // wildcard di tengah/akhir label tidak diterima
        val rest = pattern.substring(2)
        if ('*' in rest || '.' !in rest) return false // "*.com" terlalu luas
        if (!host.endsWith(".$rest")) return false
        val label = host.substring(0, host.length - rest.length - 1)
        return label.isNotEmpty() && '.' !in label
    }

    private fun isIpLiteral(h: String) = ':' in h || Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(h)

    /** Normalisasi supaya "::1" dan "0:0:0:0:0:0:0:1" dianggap sama. Hanya dipanggil untuk literal IP (tanpa DNS). */
    private fun normalizeIp(s: String): String? =
        if (isIpLiteral(s)) runCatching { InetAddress.getByName(s).hostAddress }.getOrNull() else null

    private fun commonName(cert: X509Certificate): String? =
        Regex("""(?:^|,)\s*CN=([^,]+)""", RegexOption.IGNORE_CASE)
            .find(cert.subjectX500Principal.getName(X500Principal.RFC2253))?.groupValues?.get(1)?.trim()
}

/**
 * Trust manager FTPS:
 * 1. Sertifikat yang valid menurut penyimpanan kepercayaan sistem *dan* cocok dengan nama host diterima langsung.
 * 2. Selain itu (self-signed, CA tidak dikenal, nama tidak cocok) dipakai pin TOFU seperti SSH:
 *    belum dikenal → [HostKeyUnknownException]; berubah → [HostKeyChangedException]; sama → diterima.
 *
 * JSSE hanya menerima true/false lewat CertificateException, jadi alasan disimpan di [failure] dan dilempar ulang
 * oleh pemanggil setelah koneksi gagal. [system] null berarti tidak ada jalur CA (hanya pin).
 */
class PinningTrustManager(
    private val host: String,
    private val port: Int,
    private val store: KnownHostsStore,
    private val system: X509TrustManager?,
) : X509TrustManager {
    @Volatile
    var failure: Exception? = null
        private set

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("Autentikasi klien tidak dipakai")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("Rantai sertifikat kosong")
        val leaf = chain[0]
        val sys = system // salinan lokal: smart cast pada properti nullable tidak berlaku di dalam lambda
        if (sys != null && runCatching { sys.checkServerTrusted(chain, authType) }.isSuccess &&
            HostnameMatcher.matches(host, leaf)
        ) return

        val fp = certFingerprint(leaf)
        val known = store.get(host, port)
        when {
            known == fp -> return
            known == null -> {
                failure = HostKeyUnknownException(host, port, fp, "Sertifikat TLS")
                throw CertificateException("Sertifikat server belum dikenal")
            }
            else -> {
                failure = HostKeyChangedException(host, port, known, fp)
                throw CertificateException("Sertifikat server berubah")
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system?.acceptedIssuers ?: emptyArray()
}
