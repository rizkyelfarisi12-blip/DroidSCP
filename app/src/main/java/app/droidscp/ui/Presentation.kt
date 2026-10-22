package app.droidscp.ui

import java.time.Instant
import java.time.ZoneId

/** Jenis berkas untuk memilih ikon dan warna di daftar. */
enum class FileKind { FOLDER, LINK, IMAGE, VIDEO, AUDIO, ARCHIVE, PDF, CODE, TEXT, OTHER }

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "avif", "ico", "tiff")
private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "3gp")
private val AUDIO_EXT = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "wma")
private val ARCHIVE_EXT = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst", "jar", "apk", "deb", "rpm")
private val CODE_EXT = setOf(
    "kt", "java", "js", "ts", "tsx", "jsx", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "sh",
    "bash", "html", "css", "json", "xml", "yml", "yaml", "toml", "sql", "gradle", "kts", "swift", "lua",
)
private val TEXT_EXT = setOf("txt", "md", "log", "conf", "cfg", "ini", "csv", "env", "properties", "doc", "docx", "odt", "rtf")

fun fileKind(name: String, isDirectory: Boolean, isSymlink: Boolean): FileKind {
    if (isDirectory) return FileKind.FOLDER
    if (isSymlink) return FileKind.LINK
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext.isEmpty() || name.startsWith(".") && name.count { it == '.' } == 1 -> FileKind.OTHER
        ext in IMAGE_EXT -> FileKind.IMAGE
        ext in VIDEO_EXT -> FileKind.VIDEO
        ext in AUDIO_EXT -> FileKind.AUDIO
        ext in ARCHIVE_EXT -> FileKind.ARCHIVE
        ext == "pdf" -> FileKind.PDF
        ext in CODE_EXT -> FileKind.CODE
        ext in TEXT_EXT -> FileKind.TEXT
        else -> FileKind.OTHER
    }
}

private val MONTHS_ID = listOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")

/**
 * Tanggal yang mudah dibaca: "Hari ini 14:30", "Kemarin 09:05", "12 Okt" (tahun ini), "12 Okt 2024".
 * [epochSec] <= 0 berarti waktu tidak diketahui (mis. server SCP tanpa SFTP) dan menghasilkan string kosong.
 */
fun friendlyDate(epochSec: Long, nowSec: Long, zone: ZoneId): String {
    if (epochSec <= 0) return ""
    val t = Instant.ofEpochSecond(epochSec).atZone(zone)
    val now = Instant.ofEpochSecond(nowSec).atZone(zone)
    val hm = "%02d:%02d".format(t.hour, t.minute)
    val days = java.time.temporal.ChronoUnit.DAYS.between(t.toLocalDate(), now.toLocalDate())
    return when {
        days == 0L -> "Hari ini $hm"
        days == 1L -> "Kemarin $hm"
        t.year == now.year -> "${t.dayOfMonth} ${MONTHS_ID[t.monthValue - 1]}"
        else -> "${t.dayOfMonth} ${MONTHS_ID[t.monthValue - 1]} ${t.year}"
    }
}

/** "42%" untuk transfer berukuran diketahui; null bila total tidak diketahui. */
fun percentText(done: Long, total: Long): String? =
    if (total > 0) "${(done.toDouble() / total * 100).toInt().coerceIn(0, 100)}%" else null

/** Satu kalimat awam tentang protokol, ditampilkan di form situs. */
fun protocolHint(name: String, implicitTls: Boolean = false): String = when (name) {
    "SFTP" -> "Paling aman dan paling disarankan. Hampir semua server Linux mendukungnya lewat port 22."
    "SCP" -> "Alternatif untuk server SSH yang tidak menyediakan SFTP. Tidak bisa melanjutkan unduhan yang terputus."
    "FTP" -> "Tidak terenkripsi: password dan data bisa dibaca orang lain di jaringan. Pakai hanya bila tidak ada pilihan lain."
    "FTPS" -> if (implicitTls) "FTP dengan enkripsi sejak awal koneksi (port 990). Untuk server lama."
    else "FTP dengan enkripsi TLS. Sertifikat yang tidak dikenal akan ditanyakan sekali, lalu diingat."
    else -> ""
}
