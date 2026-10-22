package app.droidscp.core

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import net.schmizz.sshj.sftp.RemoteFile as SshRemoteFile

/**
 * Satu sesi terhubung ke server.
 *
 * - Operasi berkas (list, mkdir, rename, chmod, delete, baca/tulis teks) lewat subsistem SFTP ([SftpOps]).
 *   Pada mode SCP, bila server tidak menyediakan SFTP, dipakai perintah shell Unix ([ShellOps]).
 * - Transfer berkas memakai protokol yang dipilih pada [Site]: SFTP, atau SCP lewat exec channel
 *   (`scp -t` / `scp -f`).
 *
 * Semua metode memblokir; panggil dari thread background.
 */
class RemoteSession private constructor(
    override val site: Site,
    private val ssh: SSHClient,
    private val ops: SshFileOps,
    /** Null hanya pada mode SCP bila server tidak menyediakan SFTP; operasi berkas lalu memakai perintah shell. */
    private val sftp: SFTPClient?,
) : RemoteFs {

    private fun sftpOrFail(): SFTPClient = sftp ?: throw IOException("Server tidak menyediakan SFTP")

    /** True bila penelusuran memakai perintah shell (server SCP tanpa SFTP). */
    val shellOnly: Boolean get() = sftp == null

    override val isAlive: Boolean get() = ssh.isConnected && ssh.isAuthenticated

    /** Resume hanya untuk SFTP; SCP selalu mulai dari awal. */
    override val supportsResume: Boolean get() = site.protocol == Protocol.SFTP
    override val supportsChmod: Boolean get() = true

    // ---------------------------------------------------------------- navigasi & operasi berkas (didelegasikan ke [ops])

    override fun home(): String = ops.home()
    override fun canonical(path: String): String = ops.canonical(path)
    override fun list(path: String): List<RemoteEntry> = ops.list(path)
    override fun stat(path: String): RemoteEntry? = ops.stat(path)
    override fun mkdir(path: String) = ops.mkdir(path)
    override fun mkdirs(path: String) = ops.mkdirs(path)
    override fun rename(from: String, to: String) = ops.rename(from, to)
    override fun chmod(path: String, mask: Int) = ops.chmod(path, mask)
    override fun delete(entry: RemoteEntry) = ops.delete(entry)
    override fun exists(path: String): Boolean = ops.exists(path)
    override fun readBytes(path: String, maxBytes: Int): ByteArray = ops.readBytes(path, maxBytes)
    override fun writeBytes(path: String, data: ByteArray) = ops.writeBytes(path, data)

    // ---------------------------------------------------------------- transfer

    override fun download(
        remotePath: String,
        out: OutputStream,
        offset: Long,
        onBytes: (Long) -> Unit,
        check: () -> Unit,
    ) {
        when (site.protocol) {
            Protocol.SFTP -> {
                val rf = sftpOrFail().open(remotePath)
                try {
                    val remaining = rf.length() - offset
                    if (remaining < 0) throw IOException("Berkas lokal lebih besar dari berkas di server")
                    val input: InputStream =
                        if (offset == 0L) rf.ReadAheadRemoteFileInputStream(16) else SftpRangeInput(rf, offset)
                    input.use { copyExact(it, out, remaining, onBytes, check) }
                } finally {
                    rf.close()
                }
            }
            Protocol.SCP -> {
                require(offset == 0L) { "SCP tidak mendukung resume" }
                scpDownload(remotePath, out, onBytes, check)
            }
            else -> throw IllegalStateException("Bukan protokol SSH")
        }
        out.flush()
    }

    override fun upload(
        remotePath: String,
        length: Long,
        input: InputStream,
        offset: Long,
        onBytes: (Long) -> Unit,
        check: () -> Unit,
    ) {
        when (site.protocol) {
            Protocol.SFTP -> {
                val mode = if (offset > 0) EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)
                else EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
                val rf = sftpOrFail().open(remotePath, mode)
                try {
                    rf.RemoteFileOutputStream(offset, 16).use { out ->
                        copyExact(input, out, length, onBytes, check)
                    }
                } finally {
                    rf.close()
                }
            }
            Protocol.SCP -> {
                require(offset == 0L) { "SCP tidak mendukung resume" }
                scpUpload(parentPath(remotePath), baseName(remotePath), length, input, onBytes, check)
            }
            else -> throw IllegalStateException("Bukan protokol SSH")
        }
    }

    // ---------------------------------------------------------------- SCP (protokol rcp lewat exec channel)

    private fun scpUpload(
        remoteDir: String,
        name: String,
        size: Long,
        input: InputStream,
        onBytes: (Long) -> Unit,
        check: () -> Unit,
    ) {
        require('\n' !in name) { "Nama berkas tidak valid" }
        ssh.startSession().use { session ->
            val cmd = session.exec("scp -t ${shellQuote(remoteDir)}")
            val toRemote = cmd.outputStream
            val fromRemote = cmd.inputStream
            scpAck(fromRemote)
            toRemote.write("C0644 $size $name\n".toByteArray(Charsets.UTF_8))
            toRemote.flush()
            scpAck(fromRemote)
            copyExact(input, toRemote, size, onBytes, check)
            toRemote.write(0)
            toRemote.flush()
            scpAck(fromRemote)
            toRemote.close()
            cmd.join(10, TimeUnit.SECONDS)
        }
    }

    private fun scpDownload(
        remotePath: String,
        out: OutputStream,
        onBytes: (Long) -> Unit,
        check: () -> Unit,
    ) {
        ssh.startSession().use { session ->
            val cmd = session.exec("scp -f ${shellQuote(remotePath)}")
            val toRemote = cmd.outputStream
            val fromRemote = cmd.inputStream
            toRemote.write(0)
            toRemote.flush()
            while (true) {
                val c = fromRemote.read()
                when (c) {
                    -1 -> throw EOFException("Server menutup koneksi SCP sebelum berkas diterima")
                    1, 2 -> throw IOException("SCP: " + readLine(fromRemote))
                    'T'.code -> { // baris waktu: "T mtime 0 atime 0"
                        readLine(fromRemote)
                        toRemote.write(0); toRemote.flush()
                    }
                    'C'.code -> {
                        val header = readLine(fromRemote) // "0644 1234 nama"
                        val parts = header.split(' ', limit = 3)
                        if (parts.size < 3) throw IOException("Header SCP tidak valid: $header")
                        val size = parts[1].toLongOrNull() ?: throw IOException("Ukuran SCP tidak valid: $header")
                        toRemote.write(0); toRemote.flush()
                        copyExact(fromRemote, out, size, onBytes, check)
                        scpAck(fromRemote)
                        toRemote.write(0); toRemote.flush()
                        break
                    }
                    'D'.code -> throw IOException("Berkas ini folder; unduh per berkas atau pilih folder lewat SFTP")
                    else -> throw IOException("Respons SCP tidak dikenal: $c")
                }
            }
            toRemote.close()
            cmd.join(10, TimeUnit.SECONDS)
        }
    }

    private fun scpAck(input: InputStream) {
        when (val b = input.read()) {
            0 -> return
            -1 -> throw EOFException("Koneksi SCP terputus")
            1, 2 -> throw IOException("SCP: " + readLine(input))
            else -> throw IOException("Respons SCP tidak dikenal: $b")
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- util

    override fun close() {
        runCatching { sftp?.close() }
        runCatching { ssh.disconnect() }
        runCatching { ssh.close() }
    }

    companion object {
        /**
         * Hubungkan dan autentikasi. Dapat melempar [HostKeyUnknownException] atau
         * [HostKeyChangedException] (ditangani UI), atau exception sshj lain untuk kegagalan jaringan/auth.
         */
        fun connect(
            site: Site,
            keys: KeySource,
            knownHosts: KnownHostsStore,
            timeoutMs: Int = 15_000,
        ): RemoteSession {
            val verifier = TofuVerifier(knownHosts)
            val ssh = SSHClient()
            ssh.connectTimeout = timeoutMs
            ssh.timeout = timeoutMs * 4
            ssh.addHostKeyVerifier(verifier)
            try {
                try {
                    ssh.connect(site.host, site.port)
                } catch (e: Exception) {
                    throw verifier.failure ?: e
                }

                when (site.authType) {
                    AuthType.PASSWORD -> {
                        val finder = fixedPassword(site.password)
                        ssh.auth(
                            site.username,
                            AuthPassword(finder),
                            AuthKeyboardInteractive(PasswordResponseProvider(finder)),
                        )
                    }
                    AuthType.PRIVATE_KEY -> {
                        val name = site.keyFileName ?: throw IOException("Kunci privat belum dipilih")
                        val bytes = keys.load(name)
                        val pem = try {
                            String(bytes, Charsets.UTF_8)
                        } finally {
                            bytes.fill(0)
                        }
                        val finder: PasswordFinder? = if (site.keyPassphrase.isEmpty()) null else fixedPassword(site.keyPassphrase)
                        val noPublicKey: String? = null
                        // loadKeys(privateKey, publicKey, finder) menerima ISI kunci, jadi tidak ada berkas sementara di disk.
                        val provider = ssh.loadKeys(pem, noPublicKey, finder)
                        ssh.authPublickey(site.username, provider)
                    }
                }
                ssh.connection.keepAlive.keepAliveInterval = 30
                // Mode SCP boleh tanpa SFTP (server yang hanya mengizinkan scp); mode SFTP wajib.
                val sftp: SFTPClient? =
                    if (site.protocol == Protocol.SCP) runCatching { ssh.newSFTPClient() }.getOrNull() else ssh.newSFTPClient()
                val ops: SshFileOps = if (sftp != null) SftpOps(sftp) else ShellOps(SshShellRunner(ssh))
                return RemoteSession(site, ssh, ops, sftp)
            } catch (e: Throwable) {
                runCatching { ssh.close() }
                throw e
            }
        }

        private fun fixedPassword(pw: String) = object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray = pw.toCharArray()
            override fun shouldRetry(resource: Resource<*>?): Boolean = false
        }
    }
}

/** Pembaca berurutan mulai [pos] memakai read() SFTP biasa; dipakai untuk melanjutkan unduhan (resume). */
private class SftpRangeInput(private val rf: SshRemoteFile, private var pos: Long) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = rf.read(pos, b, off, len)
        if (n > 0) pos += n
        return n
    }
}

/** Jalankan perintah shell lewat exec channel; stderr dibaca di thread terpisah agar tidak saling mengunci. */
private class SshShellRunner(private val ssh: SSHClient) : ShellRunner {
    override fun run(command: String, stdin: ByteArray?): ExecResult {
        ssh.startSession().use { session ->
            val cmd = session.exec(command)
            var err = ByteArray(0)
            val errReader = Thread { err = runCatching { cmd.errorStream.readBytes() }.getOrDefault(ByteArray(0)) }
            errReader.isDaemon = true
            errReader.start()
            if (stdin != null) cmd.outputStream.write(stdin)
            cmd.outputStream.close() // EOF pada stdin
            val out = cmd.inputStream.readBytes()
            cmd.join(30, TimeUnit.SECONDS)
            errReader.join(5_000)
            // Sebagian server tidak mengirim exit-status; anggap sukses bila tidak ada pesan galat.
            val exit = cmd.exitStatus ?: if (err.isEmpty()) 0 else 1
            return ExecResult(exit, out, err)
        }
    }
}
