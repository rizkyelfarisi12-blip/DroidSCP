package app.droidscp.data

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import app.droidscp.core.KeySource
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Penyimpanan kunci privat terenkripsi (AES-256-GCM, kunci induk di Android Keystore) di folder privat aplikasi.
 * Berkas baru berakhiran [ENC_SUFFIX]; berkas tanpa akhiran itu adalah kunci polos dari versi lama dan
 * dimigrasikan oleh [migrate].
 */
class KeyVault(private val context: Context, private val masterKey: MasterKey) : KeySource {
    private val dir = File(context.filesDir, "keys").apply { mkdirs() }

    private fun encrypted(name: String) = EncryptedFile.Builder(
        context, File(dir, name), masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    /** Nama berasal dari data tersimpan; tolak yang memuat pemisah path agar tidak keluar dari [dir]. */
    private fun safeFile(name: String): File {
        if (name.isEmpty() || name != File(name).name) throw IOException("Nama berkas kunci tidak valid")
        return File(dir, name)
    }

    /** Simpan terenkripsi; kembalikan nama internal baru. */
    fun store(bytes: ByteArray): String {
        val name = UUID.randomUUID().toString() + ENC_SUFFIX
        val file = safeFile(name)
        try {
            encrypted(name).openFileOutput().use { it.write(bytes) }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return name
    }

    override fun load(name: String): ByteArray {
        val file = safeFile(name)
        if (!file.exists()) throw IOException("Berkas kunci tidak ditemukan; impor ulang kunci pada pengaturan situs")
        return if (name.endsWith(ENC_SUFFIX)) encrypted(name).openFileInput().use { it.readBytes() } else file.readBytes()
    }

    fun delete(name: String) {
        runCatching { wipe(safeFile(name)) }
    }

    /** Enkripsi kunci polos versi lama; kembalikan nama baru, atau null bila tidak perlu/ tidak bisa dimigrasikan. */
    fun migrate(name: String): String? {
        if (name.endsWith(ENC_SUFFIX)) return null
        val old = runCatching { safeFile(name) }.getOrNull() ?: return null
        if (!old.exists()) return null
        val bytes = old.readBytes()
        return try {
            store(bytes).also { wipe(old) }
        } finally {
            bytes.fill(0)
        }
    }

    /** Timpa dengan nol sebelum menghapus (upaya terbaik; penyimpanan flash tidak menjamin penimpaan fisik). */
    private fun wipe(f: File) {
        if (!f.exists()) return
        runCatching {
            RandomAccessFile(f, "rw").use { raf ->
                val zeros = ByteArray(4096)
                var left = raf.length()
                raf.seek(0)
                while (left > 0) {
                    val n = minOf(zeros.size.toLong(), left).toInt()
                    raf.write(zeros, 0, n)
                    left -= n
                }
                raf.fd.sync()
            }
        }
        f.delete()
    }

    companion object {
        const val ENC_SUFFIX = ".enc"
    }
}
