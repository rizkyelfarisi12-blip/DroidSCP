package app.droidscp.data

import androidx.documentfile.provider.DocumentFile
import app.droidscp.core.RemoteEntry
import app.droidscp.core.RemoteFs
import java.io.IOException

enum class SyncDirection { LOCAL_TO_REMOTE, REMOTE_TO_LOCAL }
enum class SyncOp { COPY, UPDATE, DELETE }

/** Satu simpul pohon (berkas/folder) di salah satu sisi. [rel] memakai pemisah "/" dan tanpa awalan. */
class SyncNode(
    val rel: String,
    val isDir: Boolean,
    val size: Long,
    val mtimeSec: Long,
    val local: DocumentFile? = null,
    val remote: RemoteEntry? = null,
)

/**
 * Satu langkah rencana. COPY/UPDATE: sisi sumber terisi. DELETE: sisi tujuan terisi.
 * (Sumber = lokal bila LOCAL_TO_REMOTE, remote bila REMOTE_TO_LOCAL.)
 */
class SyncItem(
    val rel: String,
    val op: SyncOp,
    val isDir: Boolean,
    val size: Long,
    val local: DocumentFile?,
    val remote: RemoteEntry?,
)

class SyncPlan(
    val direction: SyncDirection,
    val items: List<SyncItem>,
    /** Seluruh folder lokal yang ada (rel → folder), dipakai saat mengunduh ke struktur yang sudah ada. */
    val localDirs: Map<String, DocumentFile>,
    /** Berkas yang lebih baru di tujuan sehingga tidak ditimpa. */
    val newerAtTarget: Int,
    /** Path yang sama tetapi tipenya berbeda (berkas vs folder); dilewati. */
    val typeMismatch: Int,
) {
    val copies: Int get() = items.count { it.op == SyncOp.COPY && !it.isDir }
    val updates: Int get() = items.count { it.op == SyncOp.UPDATE }
    val newDirs: Int get() = items.count { it.op == SyncOp.COPY && it.isDir }
    val deletes: Int get() = items.count { it.op == SyncOp.DELETE }
    val bytesToCopy: Long get() = items.filter { it.op != SyncOp.DELETE && !it.isDir }.sumOf { it.size }
    val isEmpty: Boolean get() = items.isEmpty()
}

object SyncPlanner {
    private const val MAX_NODES = 50_000
    /** Toleransi selisih waktu (detik) agar beda presisi/jam antar sisi tidak memicu salinan sia-sia. */
    private const val MTIME_TOLERANCE_SEC = 2

    fun scanRemote(fs: RemoteFs, root: String, check: () -> Unit): Map<String, SyncNode> {
        val out = LinkedHashMap<String, SyncNode>()
        fun walk(dir: String, rel: String) {
            for (e in fs.list(dir)) {
                check()
                if ('/' in e.name || e.name == ".." || e.name == ".") continue // server tidak dipercaya
                if (e.isDirectory && e.isSymlink) continue // hindari putaran
                val r = if (rel.isEmpty()) e.name else "$rel/${e.name}"
                out[r] = SyncNode(r, e.isDirectory, e.size, e.modifiedEpochSec, remote = e)
                if (out.size > MAX_NODES) throw IOException("Terlalu banyak berkas (>$MAX_NODES); pilih folder yang lebih kecil")
                if (e.isDirectory) walk(e.path, r)
            }
        }
        walk(root, "")
        return out
    }

    fun scanLocal(root: DocumentFile, check: () -> Unit): Map<String, SyncNode> {
        val out = LinkedHashMap<String, SyncNode>()
        fun walk(dir: DocumentFile, rel: String) {
            for (f in dir.listFiles()) {
                check()
                val name = f.name ?: continue
                if ('/' in name) continue
                val r = if (rel.isEmpty()) name else "$rel/$name"
                out[r] = SyncNode(r, f.isDirectory, f.length(), f.lastModified() / 1000, local = f)
                if (out.size > MAX_NODES) throw IOException("Terlalu banyak berkas (>$MAX_NODES); pilih folder yang lebih kecil")
                if (f.isDirectory) walk(f, r)
            }
        }
        walk(root, "")
        return out
    }

    fun plan(
        direction: SyncDirection,
        remote: Map<String, SyncNode>,
        local: Map<String, SyncNode>,
        deleteExtra: Boolean,
    ): SyncPlan {
        val l2r = direction == SyncDirection.LOCAL_TO_REMOTE
        val src = if (l2r) local else remote
        val dst = if (l2r) remote else local

        val items = ArrayList<SyncItem>()
        var newer = 0
        var mismatch = 0

        // Folder induk harus diproses sebelum isinya: urut menurut kedalaman.
        for (s in src.values.sortedBy { it.rel.count { c -> c == '/' } }) {
            val d = dst[s.rel]
            when {
                d == null -> items += SyncItem(s.rel, SyncOp.COPY, s.isDir, s.size, s.local, s.remote)
                d.isDir != s.isDir -> mismatch++
                s.isDir -> Unit
                s.mtimeSec > d.mtimeSec + MTIME_TOLERANCE_SEC ->
                    items += SyncItem(s.rel, SyncOp.UPDATE, false, s.size, s.local, s.remote)
                s.size != d.size -> newer++ // tujuan lebih baru tetapi beda ukuran: jangan timpa diam-diam
            }
        }

        if (deleteExtra) {
            // Isi dihapus sebelum foldernya: urut menurut kedalaman menurun.
            for (d in dst.values.filter { it.rel !in src }.sortedByDescending { it.rel.count { c -> c == '/' } }) {
                items += SyncItem(d.rel, SyncOp.DELETE, d.isDir, d.size, d.local, d.remote)
            }
        }

        val localDirs = HashMap<String, DocumentFile>()
        for (n in local.values) if (n.isDir) n.local?.let { localDirs[n.rel] = it }
        return SyncPlan(direction, items, localDirs, newer, mismatch)
    }
}
