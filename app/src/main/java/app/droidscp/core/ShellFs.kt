package app.droidscp.core

import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Operasi berkas untuk sesi SSH. Dua implementasi: SFTP ([SftpOps]) dan perintah shell ([ShellOps]) untuk server
 * yang hanya menyediakan SCP tanpa subsistem SFTP.
 */
interface SshFileOps {
    fun home(): String
    fun canonical(path: String): String
    fun list(path: String): List<RemoteEntry>
    fun stat(path: String): RemoteEntry?
    fun mkdir(path: String)
    fun mkdirs(path: String)
    fun rename(from: String, to: String)
    fun chmod(path: String, mask: Int)
    fun delete(entry: RemoteEntry)
    fun exists(path: String): Boolean
    fun readBytes(path: String, maxBytes: Int): ByteArray
    fun writeBytes(path: String, data: ByteArray)
}

class ExecResult(val exit: Int, val out: ByteArray, val err: ByteArray = ByteArray(0)) {
    val text: String get() = String(out, Charsets.UTF_8)
    val errText: String get() = String(err, Charsets.UTF_8)
}

/** Jalankan satu perintah shell di server (exec channel), opsional dengan isi stdin. */
fun interface ShellRunner {
    fun run(command: String, stdin: ByteArray?): ExecResult
}

fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

/** Argumen path untuk shell: "~" dan "~/…" diperluas ke $HOME, selain itu dikutip utuh. */
fun shellPathArg(path: String): String = when {
    path == "~" -> "\"\$HOME\""
    path.startsWith("~/") -> "\"\$HOME\"/" + shellQuote(path.substring(2))
    else -> shellQuote(path)
}

private val EPOCH_LINE = Regex("""^([-dlcbpsDCMnP?])([rwxsStT-]{9})[.+@]?\s+\d+\s+\S+\s+\S+\s+(?:\d+,\s+)?(\d+)\s+(\d+)\s+(.+)$""")
private val DATE_LINE = Regex(
    """^([-dlcbpsDCMnP?])([rwxsStT-]{9})[.+@]?\s+\d+\s+\S+\s+\S+\s+(?:\d+,\s+)?(\d+)\s+([A-Z][a-z]{2})\s+(\d{1,2})\s+(\d{2}:\d{2}|\d{4})\s+(.+)$""",
)
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/**
 * Urai satu baris keluaran `ls -l`. Mendukung `--time-style=+%s` (GNU, waktu epoch) dan format bawaan
 * ("Jan  5 12:30" atau "Jan  5  2023", dipakai busybox/BSD; zona waktu server tidak diketahui, dianggap UTC).
 * Mengembalikan null untuk baris yang bukan entri ("total N", baris kosong, format tak dikenal).
 */
fun parseLsLine(line: String, dir: String, nowEpochSec: Long): RemoteEntry? {
    val l = line.trimEnd('\r')
    if (l.isBlank() || l.startsWith("total ")) return null
    val type: Char
    val perms: String
    val size: Long
    val mtime: Long
    val rawName: String
    val m1 = EPOCH_LINE.matchEntire(l)
    if (m1 != null) {
        type = m1.groupValues[1][0]; perms = m1.groupValues[2]
        size = m1.groupValues[3].toLongOrNull() ?: 0L
        mtime = m1.groupValues[4].toLongOrNull() ?: 0L
        rawName = m1.groupValues[5]
    } else {
        val m2 = DATE_LINE.matchEntire(l) ?: return null
        type = m2.groupValues[1][0]; perms = m2.groupValues[2]
        size = m2.groupValues[3].toLongOrNull() ?: 0L
        mtime = guessEpoch(m2.groupValues[4], m2.groupValues[5], m2.groupValues[6], nowEpochSec)
        rawName = m2.groupValues[7]
    }
    val isLink = type == 'l'
    val name = (if (isLink) rawName.substringBefore(" -> ") else rawName).trimEnd('/')
    val base = name.substringAfterLast('/')
    if (base.isEmpty() || base == "." || base == "..") return null
    return RemoteEntry(
        name = base,
        path = joinPath(dir, base),
        isDirectory = type == 'd',
        isSymlink = isLink,
        size = size,
        modifiedEpochSec = mtime,
        permissionMask = permMask(perms),
    )
}

private fun permMask(p: String): Int {
    var mask = 0
    for (i in 0 until 9) {
        val on = when (p[i]) {
            'r', 'w', 'x', 's', 't' -> true // huruf kecil: bit eksekusi aktif (s/t) atau izin biasa
            else -> false                   // '-', 'S', 'T': tidak ada izin eksekusi
        }
        if (on) mask = mask or (1 shl (8 - i))
    }
    return mask
}

private fun guessEpoch(mon: String, day: String, timeOrYear: String, now: Long): Long = runCatching {
    val month = MONTHS.indexOf(mon) + 1
    require(month > 0)
    val d = day.toInt()
    if (':' in timeOrYear) {
        val (h, mi) = timeOrYear.split(':').map { it.toInt() }
        val nowYear = java.time.Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC).year
        var t = LocalDateTime.of(nowYear, month, d, h, mi).toEpochSecond(ZoneOffset.UTC)
        if (t > now + 86_400) t = LocalDateTime.of(nowYear - 1, month, d, h, mi).toEpochSecond(ZoneOffset.UTC)
        t
    } else {
        LocalDateTime.of(timeOrYear.toInt(), month, d, 0, 0).toEpochSecond(ZoneOffset.UTC)
    }
}.getOrDefault(0L)

/**
 * Operasi berkas lewat perintah shell Unix standar (ls, mkdir, mv, rm, chmod, cat, cd/pwd). Dipakai bila server
 * tidak menyediakan subsistem SFTP. Bergantung pada `ls -l`, sehingga nama berkas dengan baris baru tidak didukung.
 */
class ShellOps(
    private val runner: ShellRunner,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : SshFileOps {
    @Volatile
    private var gnuTime: Boolean? = null

    private fun check(r: ExecResult, what: String): ExecResult {
        if (r.exit != 0) {
            val why = r.errText.ifBlank { r.text }.trim().ifEmpty { "kode ${r.exit}" }
            throw IOException("$what gagal: $why")
        }
        return r
    }

    private fun unsupportedOption(text: String): Boolean {
        val t = text.lowercase()
        return "unrecognized option" in t || "invalid option" in t || "illegal option" in t || "time-style" in t
    }

    /** Jalankan `ls` dengan waktu epoch bila didukung (GNU), kalau tidak dengan format bawaan. */
    private fun ls(flags: String, target: String): ExecResult {
        val q = shellPathArg(target)
        if (gnuTime != false) {
            val r = runner.run("LC_ALL=C ls $flags --time-style=+%s -- $q", null)
            if (r.exit == 0) { gnuTime = true; return r }
            if (gnuTime == true || !unsupportedOption(r.errText + r.text)) return r
            gnuTime = false
        }
        return runner.run("LC_ALL=C ls $flags -- $q", null)
    }

    override fun home(): String = check(runner.run("pwd", null), "Baca folder awal").text.trim()

    override fun canonical(path: String): String {
        val r = runner.run("cd ${shellPathArg(path)} 2>/dev/null && pwd -P", null)
        if (r.exit == 0 && r.text.isNotBlank()) return r.text.trim()
        if (path.startsWith("/")) return normalizePath(path)
        throw IOException("Folder \"$path\" tidak ditemukan")
    }

    override fun list(path: String): List<RemoteEntry> {
        val dir = normalizePath(path)
        val r = check(ls("-lA", if (dir == "/") "/" else "$dir/"), "Buka folder")
        val t = now()
        val entries = r.text.lineSequence().mapNotNull { parseLsLine(it, dir, t) }.toList()
        val links = entries.filter { it.isSymlink }
        val dirs: Map<String, Boolean> = if (links.isEmpty()) emptyMap() else resolveLinkDirs(dir, links)
        return entries
            .map { e -> if (e.isSymlink && dirs[e.name] == true) e.copy(isDirectory = true) else e }
            .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /** Satu perintah untuk semua tautan: tautan ke folder harus bisa dibuka. Kegagalan dianggap "bukan folder". */
    private fun resolveLinkDirs(dir: String, links: List<RemoteEntry>): Map<String, Boolean> {
        val names = links.joinToString(" ") { shellQuote(it.name) }
        val cmd = "cd ${shellPathArg(dir)} && for f in $names; do if [ -d \"\$f\" ]; then echo D; else echo F; fi; done"
        val r = runCatching { runner.run(cmd, null) }.getOrNull() ?: return emptyMap()
        if (r.exit != 0) return emptyMap()
        val flags = r.text.lines().filter { it.isNotBlank() }
        if (flags.size != links.size) return emptyMap()
        return links.indices.associate { links[it].name to (flags[it].trim() == "D") }
    }

    override fun stat(path: String): RemoteEntry? {
        val norm = normalizePath(path)
        if (norm == "/") return RemoteEntry("/", "/", true, false, 0, 0, 0b111_101_101)
        val r = ls("-ldAL", norm)
        if (r.exit != 0) {
            val msg = r.errText + r.text
            if ("No such file" in msg || "not found" in msg.lowercase()) return null
            throw IOException("Baca info gagal: ${msg.trim().ifEmpty { "kode ${r.exit}" }}")
        }
        val line = r.text.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        return parseLsLine(line, parentPath(norm), now())?.copy(name = baseName(norm), path = norm)
    }

    override fun mkdir(path: String) { check(runner.run("mkdir -- ${shellPathArg(path)}", null), "Buat folder") }

    override fun mkdirs(path: String) { check(runner.run("mkdir -p -- ${shellPathArg(path)}", null), "Buat folder") }

    override fun rename(from: String, to: String) {
        check(runner.run("mv -- ${shellPathArg(from)} ${shellPathArg(to)}", null), "Ganti nama")
    }

    override fun chmod(path: String, mask: Int) {
        check(runner.run("chmod ${Integer.toOctalString(mask and 0xFFF)} ${shellPathArg(path)}", null), "Ubah izin")
    }

    override fun delete(entry: RemoteEntry) {
        val flags = if (entry.isDirectory && !entry.isSymlink) "-rf" else "-f"
        check(runner.run("rm $flags -- ${shellPathArg(entry.path)}", null), "Hapus")
    }

    override fun exists(path: String): Boolean = stat(path) != null

    override fun readBytes(path: String, maxBytes: Int): ByteArray {
        val st = stat(path) ?: throw IOException("Berkas tidak ditemukan: $path")
        if (st.size > maxBytes) throw IOException("Berkas terlalu besar untuk diedit (${formatSize(st.size)})")
        return check(runner.run("cat ${shellPathArg(path)}", null), "Baca berkas").out
    }

    override fun writeBytes(path: String, data: ByteArray) {
        check(runner.run("cat > ${shellPathArg(path)}", data), "Tulis berkas")
    }
}
