package app.droidscp.core

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

fun fingerprintOf(key: PublicKey): String {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

/**
 * Verifier TOFU. sshj hanya menerima true/false, jadi alasan penolakan disimpan di [failure]
 * dan dilempar ulang oleh pemanggil setelah koneksi gagal.
 */
class TofuVerifier(private val store: KnownHostsStore) : HostKeyVerifier {
    @Volatile
    var failure: Exception? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fp = fingerprintOf(key)
        val known = store.get(hostname, port)
        return when {
            known == null -> {
                failure = HostKeyUnknownException(hostname, port, fp, KeyType.fromKey(key).toString())
                false
            }
            known == fp -> true
            else -> {
                failure = HostKeyChangedException(hostname, port, known, fp)
                false
            }
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}
