package app.droidscp.core

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration

/**
 * Sesi FTP / FTPS (TLS eksplisit, atau implisit bila [Site.implicitTls]). Sertifikat FTPS diterima bila valid menurut trust store sistem dan cocok
 * dengan nama host; selain itu dipakai pin fingerprint TOFU seperti SSH ([PinningTrustManager]).
 *
 * Koneksi [main] dipakai untuk penelusuran dan operasi kecil, dikunci oleh [lock] karena klien FTP tidak
 * aman lintas-thread. Setiap transfer membuka koneksi sendiri supaya penelusuran tidak terblokir.
 */
class FtpSession private constructor(
    override val site: Site,
    private val main: FTPClient,
    private val knownHosts: KnownHostsStore,
) : RemoteFs {

    private val lock = Any()
    private var mlstSupported: Boolean? = null

    /** Kirim NOOP: koneksi kontrol yang sudah ditutup server karena idle baru ketahuan saat dipakai. */
    override val isAlive: Boolean
        get() = main.isConnected && runCatching { synchronized(lock) { main.sendNoOp() } }.getOrDefault(false)
    override val supportsResume: Boolean get() = true
    override val supportsChmod: Boolean get() = true

    // ---------------------------------------------------------------- navigasi

    override fun home(): String = synchronized(lock) { main.printWorkingDirectory() ?: "/" }

    override fun canonical(path: String): String = synchronized(lock) {
        val abs = if (path.startsWith("/")) path else joinPath(main.printWorkingDirectory() ?: "/", path)
        normalizePath(abs)
    }

    override fun list(path: String): List<RemoteEntry> = synchronized(lock) {
        val files: Array<FTPFile> = if (hasMlst()) main.mlistDir(path) else main.listFiles(path)
        // listFiles/mlistDir mengembalikan array kosong saat gagal; balasan server yang membedakannya dari folder kosong.
        if (!FTPReply.isPositiveCompletion(main.replyCode)) {
            throw IOException("Tidak bisa membuka $path: ${main.replyString?.trim().orEmpty()}")
        }
        files.mapNotNull { it.toEntry(path) }
            .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override fun stat(path: String): RemoteEntry? = synchronized(lock) {
        val norm = normalizePath(path)
        if (norm == "/") return@synchronized null
        val viaMlst = if (hasMlst()) runCatching { main.mlistFile(norm) }.getOrNull() else null
        if (viaMlst != null) {
            viaMlst.toEntry(parentPath(norm), forcedName = baseName(norm))
        } else {
            list(parentPath(norm)).firstOrNull { it.name == baseName(norm) }
        }
    }

    private fun hasMlst(): Boolean {
        mlstSupported?.let { return it }
        val v = runCatching { main.hasFeature("MLST") }.getOrDefault(false)
        mlstSupported = v
        return v
    }

    private fun FTPFile.toEntry(dir: String, forcedName: String? = null): RemoteEntry? {
        val raw = forcedName ?: name ?: return null
        val nm = raw.trimEnd('/').substringAfterLast('/')
        if (nm.isEmpty() || nm == "." || nm == "..") return null
        var mask = 0
        val classes = intArrayOf(FTPFile.USER_ACCESS, FTPFile.GROUP_ACCESS, FTPFile.WORLD_ACCESS)
        val perms = intArrayOf(FTPFile.READ_PERMISSION, FTPFile.WRITE_PERMISSION, FTPFile.EXECUTE_PERMISSION)
        for (c in 0..2) for (p in 0..2) {
            if (hasPermission(classes[c], perms[p])) mask = mask or (1 shl (8 - (c * 3 + p)))
        }
        return RemoteEntry(
            name = nm,
            path = joinPath(dir, nm),
            isDirectory = isDirectory,
            isSymlink = isSymbolicLink,
            size = if (size < 0) 0 else size,
            modifiedEpochSec = timestamp?.timeInMillis?.div(1000) ?: 0L,
            permissionMask = mask,
        )
    }

    // ---------------------------------------------------------------- mutasi

    private fun ok(success: Boolean, what: String) {
        if (!success) throw IOException("$what gagal: ${main.replyString?.trim().orEmpty()}")
    }

    override fun mkdir(path: String) = synchronized(lock) { ok(main.makeDirectory(path), "Buat folder") }

    override fun mkdirs(path: String) {
        var acc = ""
        for (seg in normalizePath(path).split('/').filter { it.isNotEmpty() }) {
            acc += "/$seg"
            if (stat(acc) == null) mkdir(acc)
        }
    }

    override fun rename(from: String, to: String) = synchronized(lock) { ok(main.rename(from, to), "Ganti nama") }

    override fun chmod(path: String, mask: Int) = synchronized(lock) {
        val cmd = "CHMOD ${Integer.toOctalString(mask and 0xFFF)} $path"
        if (!main.sendSiteCommand(cmd)) throw IOException("Server tidak mendukung CHMOD: ${main.replyString?.trim().orEmpty()}")
    }

    override fun delete(entry: RemoteEntry) {
        if (entry.isDirectory && !entry.isSymlink) {
            list(entry.path).forEach { delete(it) }
            synchronized(lock) { ok(main.removeDirectory(entry.path), "Hapus folder") }
        } else {
            synchronized(lock) { ok(main.deleteFile(entry.path), "Hapus berkas") }
        }
    }

    // ---------------------------------------------------------------- editor teks

    override fun readBytes(path: String, maxBytes: Int): ByteArray = synchronized(lock) {
        val size = stat(path)?.size ?: 0L
        if (size > maxBytes) throw IOException("Berkas terlalu besar untuk diedit (${formatSize(size)})")
        val input = main.retrieveFileStream(path) ?: throw IOException("Tidak bisa membuka: ${main.replyString?.trim().orEmpty()}")
        val buf = ByteArrayOutputStream()
        try {
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > maxBytes) throw IOException("Berkas terlalu besar untuk diedit")
            }
        } finally {
            runCatching { input.close() }
        }
        ok(main.completePendingCommand(), "Baca berkas")
        buf.toByteArray()
    }

    override fun writeBytes(path: String, data: ByteArray) = synchronized(lock) {
        val out = main.storeFileStream(path) ?: throw IOException("Tidak bisa menulis: ${main.replyString?.trim().orEmpty()}")
        try {
            out.write(data)
        } finally {
            runCatching { out.close() }
        }
        ok(main.completePendingCommand(), "Simpan berkas")
    }

    // ---------------------------------------------------------------- transfer (koneksi sendiri per transfer)

    override fun download(remotePath: String, out: OutputStream, offset: Long, onBytes: (Long) -> Unit, check: () -> Unit) {
        val c = open(site, knownHosts)
        try {
            if (offset > 0) c.setRestartOffset(offset)
            val input: InputStream = c.retrieveFileStream(remotePath)
                ?: throw IOException("Tidak bisa mengunduh: ${c.replyString?.trim().orEmpty()}")
            try {
                copyUntilEof(input, out, onBytes, check)
            } finally {
                runCatching { input.close() }
            }
            if (!c.completePendingCommand()) throw IOException("Unduhan gagal: ${c.replyString?.trim().orEmpty()}")
            out.flush()
        } finally {
            runCatching { c.disconnect() }
        }
    }

    override fun upload(remotePath: String, length: Long, input: InputStream, offset: Long, onBytes: (Long) -> Unit, check: () -> Unit) {
        val c = open(site, knownHosts)
        try {
            val out: OutputStream = (if (offset > 0) c.appendFileStream(remotePath) else c.storeFileStream(remotePath))
                ?: throw IOException("Tidak bisa mengunggah: ${c.replyString?.trim().orEmpty()}")
            try {
                copyExact(input, out, length, onBytes, check)
            } finally {
                runCatching { out.close() }
            }
            if (!c.completePendingCommand()) throw IOException("Unggahan gagal: ${c.replyString?.trim().orEmpty()}")
        } finally {
            runCatching { c.disconnect() }
        }
    }

    override fun close() {
        runCatching { if (main.isConnected) main.logout() }
        runCatching { main.disconnect() }
    }

    companion object {
        private const val TIMEOUT_MS = 15_000

        /** Hubungkan dan login. Melempar IOException bila gagal. */
        fun connect(site: Site, knownHosts: KnownHostsStore): FtpSession = FtpSession(site, open(site, knownHosts), knownHosts)

        private fun open(site: Site, knownHosts: KnownHostsStore): FTPClient {
            val c: FTPClient = if (site.protocol == Protocol.FTPS) FTPSClient(site.implicitTls) else FTPClient()
            var pin: PinningTrustManager? = null
            try {
                if (c is FTPSClient) {
                    // Bawaan commons-net hanya memeriksa masa berlaku sertifikat, bukan rantai kepercayaannya.
                    // Ganti dengan trust store sistem + pencocokan nama host + pin TOFU. Pemeriksaan nama host
                    // bawaan JSSE dimatikan karena sertifikat yang di-pin pengguna boleh tidak cocok namanya.
                    pin = PinningTrustManager(site.host, site.port, knownHosts, TrustManagerUtils.getDefaultTrustManager(null))
                    c.setTrustManager(pin)
                    c.setEndpointCheckingEnabled(false)
                }
                c.connectTimeout = TIMEOUT_MS
                c.defaultTimeout = TIMEOUT_MS * 4
                c.controlEncoding = "UTF-8"
                try {
                    c.connect(site.host, site.port)
                } catch (e: Exception) {
                    throw pin?.failure ?: e // alasan penolakan sertifikat (belum dikenal / berubah) untuk dialog UI
                }
                if (!FTPReply.isPositiveCompletion(c.replyCode)) {
                    throw IOException("Server menolak koneksi: ${c.replyString?.trim().orEmpty()}")
                }
                val user = site.username.ifBlank { "anonymous" }
                val pass = if (site.username.isBlank()) "anonymous@" else site.password
                if (!c.login(user, pass)) throw IOException("Login gagal: ${c.replyString?.trim().orEmpty()}")
                if (c is FTPSClient) {
                    c.execPBSZ(0)
                    c.execPROT("P") // enkripsi juga kanal data
                }
                c.enterLocalPassiveMode()
                c.setFileType(FTP.BINARY_FILE_TYPE)
                c.setAutodetectUTF8(true)
                c.setDataTimeout(Duration.ofMillis(TIMEOUT_MS * 4L))
                c.setControlKeepAliveTimeout(Duration.ofSeconds(30))
                c.setControlKeepAliveReplyTimeout(Duration.ofSeconds(5))
                return c
            } catch (e: Throwable) {
                runCatching { c.disconnect() }
                throw e
            }
        }
    }
}
