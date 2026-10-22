package app.droidscp.core

import java.io.InputStream
import java.io.OutputStream

/**
 * Pembungkus sesi yang isinya bisa diganti saat sambung ulang. Antrean transfer menyimpan pembungkus ini,
 * jadi "Ulangi" pada pekerjaan yang terputus otomatis memakai sesi baru, bukan sesi lama yang sudah mati.
 */
class ReconnectableFs(initial: RemoteFs) : RemoteFs {
    @Volatile
    private var inner: RemoteFs = initial

    /** Ganti sesi aktif. Sesi lama ditutup (kegagalan penutupan diabaikan). */
    fun swap(next: RemoteFs) {
        val old = inner
        inner = next
        if (old !== next) runCatching { old.close() }
    }

    override val site: Site get() = inner.site
    override val isAlive: Boolean get() = inner.isAlive
    override val supportsResume: Boolean get() = inner.supportsResume
    override val supportsChmod: Boolean get() = inner.supportsChmod

    override fun home() = inner.home()
    override fun canonical(path: String) = inner.canonical(path)
    override fun list(path: String) = inner.list(path)
    override fun stat(path: String) = inner.stat(path)
    override fun mkdir(path: String) = inner.mkdir(path)
    override fun mkdirs(path: String) = inner.mkdirs(path)
    override fun rename(from: String, to: String) = inner.rename(from, to)
    override fun chmod(path: String, mask: Int) = inner.chmod(path, mask)
    override fun delete(entry: RemoteEntry) = inner.delete(entry)
    override fun exists(path: String) = inner.exists(path)
    override fun readBytes(path: String, maxBytes: Int) = inner.readBytes(path, maxBytes)
    override fun writeBytes(path: String, data: ByteArray) = inner.writeBytes(path, data)

    override fun download(remotePath: String, out: OutputStream, offset: Long, onBytes: (Long) -> Unit, check: () -> Unit) =
        inner.download(remotePath, out, offset, onBytes, check)

    override fun upload(remotePath: String, length: Long, input: InputStream, offset: Long, onBytes: (Long) -> Unit, check: () -> Unit) =
        inner.upload(remotePath, length, input, offset, onBytes, check)

    override fun close() = inner.close()
}

/** Percobaan sambung ulang: jeda antar percobaan (ms). Jumlah percobaan = ukuran daftar. */
val RECONNECT_DELAYS_MS: List<Long> = listOf(0L, 2_000L, 5_000L)

/**
 * Jalankan [block] hingga berhasil, dengan jeda [delaysMs] sebelum tiap percobaan. Exception yang membuat
 * [isFatal] bernilai true (mis. kunci host berubah, login ditolak) langsung dilempar tanpa mencoba lagi.
 * Setelah semua percobaan gagal, exception terakhir dilempar. [sleep] dapat diganti pada tes.
 */
fun <T> retryWithBackoff(
    delaysMs: List<Long>,
    isFatal: (Exception) -> Boolean,
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    block: (attempt: Int) -> T,
): T {
    require(delaysMs.isNotEmpty()) { "delaysMs tidak boleh kosong" }
    var last: Exception? = null
    for ((i, d) in delaysMs.withIndex()) {
        if (d > 0) sleep(d)
        try {
            return block(i)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            if (isFatal(e)) throw e
            last = e
        }
    }
    throw last!!
}

/**
 * Kegagalan yang tidak ada gunanya dicoba ulang otomatis: host tidak dikenal/berubah (butuh keputusan pengguna)
 * dan kredensial ditolak (mencoba lagi bisa mengunci akun di server).
 */
fun isFatalConnectError(e: Throwable): Boolean {
    var t: Throwable? = e
    var depth = 0
    while (t != null && depth++ < 6) {
        if (t is HostKeyUnknownException || t is HostKeyChangedException) return true
        if (t.javaClass.simpleName == "UserAuthException") return true
        if (t.message?.startsWith("Login gagal") == true) return true
        t = t.cause
    }
    return false
}
