package app.droidscp.core

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class Protocol(val defaultPort: Int) {
    SFTP(22), SCP(22), FTP(21), FTPS(21);

    /** Port bawaan; FTPS implisit memakai 990. */
    fun defaultPort(implicit: Boolean): Int = if (this == FTPS && implicit) 990 else defaultPort

    val isSsh: Boolean get() = this == SFTP || this == SCP
}

@Serializable
enum class AuthType { PASSWORD, PRIVATE_KEY }

/** Profil koneksi tersimpan (setara "Site" di WinSCP). */
@Serializable
data class Site(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val protocol: Protocol = Protocol.SFTP,
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",
    /** Nama file kunci privat di penyimpanan internal aplikasi. */
    val keyFileName: String? = null,
    val keyPassphrase: String = "",
    val initialRemoteDir: String = "",
    /** Folder remote yang ditandai untuk akses cepat. */
    val bookmarks: List<String> = emptyList(),
    /** Hanya untuk FTPS: TLS implisit (langsung TLS saat menyambung, biasanya port 990) alih-alih AUTH TLS. */
    val implicitTls: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { "$username@$host" }
}

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    val modifiedEpochSec: Long,
    /** Bit izin POSIX (mis. 0b111101101 = 0755). */
    val permissionMask: Int,
) {
    val permissionString: String get() = formatPermissions(permissionMask, isDirectory, isSymlink)
}

fun formatPermissions(mask: Int, isDir: Boolean, isLink: Boolean): String {
    val sb = StringBuilder()
    sb.append(if (isLink) 'l' else if (isDir) 'd' else '-')
    val chars = "rwx"
    for (i in 8 downTo 0) {
        sb.append(if (mask and (1 shl i) != 0) chars[(8 - i) % 3] else '-')
    }
    return sb.toString()
}

/** Gabungkan path remote bergaya POSIX. */
fun joinPath(dir: String, name: String): String =
    if (dir.endsWith("/")) dir + name else "$dir/$name"

fun parentPath(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return "/"
    val idx = trimmed.lastIndexOf('/')
    return if (idx <= 0) "/" else trimmed.substring(0, idx)
}

fun baseName(path: String): String = path.trimEnd('/').substringAfterLast('/')

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024; i++
    }
    return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}

/** Rapikan path POSIX: buang "." dan "..", hasil selalu absolut. */
fun normalizePath(path: String): String {
    val out = ArrayDeque<String>()
    for (seg in path.split('/')) {
        when (seg) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeLast()
            else -> out.addLast(seg)
        }
    }
    return "/" + out.joinToString("/")
}

/** Tambah [path] ke daftar bookmark bila belum ada, atau hapus bila sudah ada. Path dinormalkan (tanpa "/" akhir). */
fun toggleBookmark(list: List<String>, path: String): List<String> {
    val p = if (path.length > 1) path.trimEnd('/') else path
    return if (p in list) list - p else list + p
}

/** Izin dalam bentuk oktal 4 digit, mis. 0755. */
fun formatOctal(mask: Int): String = "%04o".format(mask and 0xFFF)
