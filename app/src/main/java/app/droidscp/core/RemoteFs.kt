package app.droidscp.core

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Abstraksi sistem berkas jauh (SFTP/SCP/FTP/FTPS). Semua metode memblokir; panggil dari thread background.
 *
 * Kontrak transfer:
 * - [download] menulis byte mulai posisi [offset] di server ke [out] (yang sudah diposisikan pemanggil).
 * - [upload] membaca tepat [length] byte dari [input] (sudah diposisikan pemanggil) dan menulisnya ke server
 *   mulai posisi [offset]. Offset > 0 hanya sah jika [supportsResume].
 * - [onBytes] dilaporkan per potongan; [check] boleh melempar untuk membatalkan.
 */
interface RemoteFs : Closeable {
    val site: Site
    val isAlive: Boolean
    val supportsResume: Boolean
    val supportsChmod: Boolean

    fun home(): String
    fun canonical(path: String): String
    fun list(path: String): List<RemoteEntry>
    /** Metadata satu path, atau null bila tidak ada. */
    fun stat(path: String): RemoteEntry?
    fun mkdir(path: String)
    fun mkdirs(path: String)
    fun rename(from: String, to: String)
    fun chmod(path: String, mask: Int)
    fun delete(entry: RemoteEntry)
    fun exists(path: String): Boolean = stat(path) != null

    fun readBytes(path: String, maxBytes: Int): ByteArray
    fun writeBytes(path: String, data: ByteArray)

    fun download(remotePath: String, out: OutputStream, offset: Long, onBytes: (Long) -> Unit, check: () -> Unit)
    fun upload(remotePath: String, length: Long, input: InputStream, offset: Long, onBytes: (Long) -> Unit, check: () -> Unit)
}

/** Buka sesi sesuai protokol pada [site]. Lihat [RemoteSession.connect] dan [FtpSession.connect] untuk exception-nya. */
fun connectRemote(site: Site, keys: KeySource, knownHosts: KnownHostsStore): RemoteFs = when (site.protocol) {
    Protocol.SFTP, Protocol.SCP -> RemoteSession.connect(site, keys, knownHosts)
    Protocol.FTP, Protocol.FTPS -> FtpSession.connect(site, knownHosts)
}

/** Salin tepat [length] byte; melempar EOFException jika sumber habis lebih awal. */
fun copyExact(
    input: InputStream,
    out: OutputStream,
    length: Long,
    onBytes: (Long) -> Unit,
    check: () -> Unit,
) {
    val buf = ByteArray(64 * 1024)
    var remaining = length
    while (remaining > 0) {
        check()
        val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
        if (n < 0) throw EOFException("Data berakhir lebih awal (kurang $remaining byte)")
        out.write(buf, 0, n)
        remaining -= n
        onBytes(n.toLong())
    }
}

/** Salin sampai sumber habis (untuk FTP yang tidak memberi panjang pasti). */
fun copyUntilEof(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit, check: () -> Unit) {
    val buf = ByteArray(64 * 1024)
    while (true) {
        check()
        val n = input.read(buf)
        if (n < 0) return
        out.write(buf, 0, n)
        onBytes(n.toLong())
    }
}

/** Lompati tepat [count] byte pada [input] (skip() boleh mengembalikan kurang dari yang diminta). */
fun skipFully(input: InputStream, count: Long) {
    var left = count
    val sink = ByteArray(8192)
    while (left > 0) {
        val s = input.skip(left)
        if (s > 0) {
            left -= s
        } else {
            val n = input.read(sink, 0, minOf(sink.size.toLong(), left).toInt())
            if (n < 0) throw IOException("Berkas lebih pendek dari offset resume")
            left -= n
        }
    }
}
