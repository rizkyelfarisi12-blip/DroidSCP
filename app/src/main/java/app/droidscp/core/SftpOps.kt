package app.droidscp.core

import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import java.io.IOException
import java.util.EnumSet

/** Operasi berkas lewat subsistem SFTP. */
class SftpOps(private val sftp: SFTPClient) : SshFileOps {
    override fun home(): String = sftp.canonicalize(".")

    override fun canonical(path: String): String = sftp.canonicalize(path)

    override fun list(path: String): List<RemoteEntry> =
        sftp.ls(path)
            .filter { it.name != "." && it.name != ".." }
            .map { toEntry(it) }
            .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })

    private fun toEntry(info: RemoteResourceInfo): RemoteEntry {
        val attrs = info.attributes
        val isLink = attrs.type == FileMode.Type.SYMLINK
        var isDir = attrs.type == FileMode.Type.DIRECTORY
        var size = attrs.size
        if (isLink) {
            // Ikuti symlink agar tautan ke folder bisa dibuka.
            runCatching { sftp.stat(info.path) }.getOrNull()?.let {
                isDir = it.type == FileMode.Type.DIRECTORY
                size = it.size
            }
        }
        return RemoteEntry(
            name = info.name,
            path = info.path,
            isDirectory = isDir,
            isSymlink = isLink,
            size = size,
            modifiedEpochSec = attrs.mtime,
            permissionMask = attrs.mode.permissionsMask,
        )
    }

    override fun stat(path: String): RemoteEntry? {
        val a = sftp.statExistence(path) ?: return null
        return RemoteEntry(
            name = baseName(path),
            path = path,
            isDirectory = a.type == FileMode.Type.DIRECTORY,
            isSymlink = a.type == FileMode.Type.SYMLINK,
            size = a.size,
            modifiedEpochSec = a.mtime,
            permissionMask = a.mode.permissionsMask,
        )
    }

    override fun mkdir(path: String) = sftp.mkdir(path)

    override fun mkdirs(path: String) = sftp.mkdirs(path)

    override fun rename(from: String, to: String) = sftp.rename(from, to)

    override fun chmod(path: String, mask: Int) = sftp.chmod(path, mask)

    override fun delete(entry: RemoteEntry) {
        if (entry.isDirectory && !entry.isSymlink) {
            list(entry.path).forEach { delete(it) }
            sftp.rmdir(entry.path)
        } else {
            sftp.rm(entry.path)
        }
    }

    override fun exists(path: String): Boolean = sftp.statExistence(path) != null

    // ---------------------------------------------------------------- editor teks

    override fun readBytes(path: String, maxBytes: Int): ByteArray {
        sftp.open(path).use { rf ->
            val len = rf.length()
            if (len > maxBytes) throw IOException("Berkas terlalu besar untuk diedit (${formatSize(len)})")
            val buf = ByteArray(len.toInt())
            var off = 0
            while (off < buf.size) {
                val n = rf.read(off.toLong(), buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            return if (off == buf.size) buf else buf.copyOf(off)
        }
    }

    override fun writeBytes(path: String, data: ByteArray) {
        sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
            var off = 0
            while (off < data.size) {
                val n = minOf(32 * 1024, data.size - off)
                rf.write(off.toLong(), data, off, n)
                off += n
            }
        }
    }
}
