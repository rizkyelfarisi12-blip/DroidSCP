package app.droidscp.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import app.droidscp.core.RemoteEntry
import app.droidscp.core.RemoteFs
import app.droidscp.core.friendlyError
import app.droidscp.core.baseName
import app.droidscp.core.joinPath
import app.droidscp.core.skipFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class Direction { UPLOAD, DOWNLOAD, SYNC }
enum class JobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class TransferJob(
    val id: Long,
    val direction: Direction,
    val name: String,
    /** Total byte; -1 bila belum diketahui. */
    val total: Long,
    val done: Long = 0,
    val state: JobState = JobState.QUEUED,
    val error: String? = null,
    val note: String? = null,
) {
    val isActive: Boolean get() = state == JobState.QUEUED || state == JobState.RUNNING
    val canRetry: Boolean get() = state == JobState.FAILED || state == JobState.CANCELLED
}

/** Pilihan pengguna saat berkas tujuan sudah ada. */
enum class ConflictAction { OVERWRITE, SKIP, RENAME, RESUME }

data class ConflictRequest(
    val jobId: Long,
    val direction: Direction,
    val fileName: String,
    val existingSize: Long,
    val newSize: Long,
    val canResume: Boolean,
)

/** Konteks yang diterima pekerjaan transfer. */
class JobCtx(
    val id: Long,
    val job: Job,
    val onBytes: (Long) -> Unit,
    val setTotal: (Long) -> Unit,
) {
    var skipped = 0
    val check: () -> Unit = { job.ensureActive() }
}

/** Antrean transfer di level aplikasi (bertahan saat Activity dibuat ulang). */
class TransferManager {
    private class Spec(
        val direction: Direction,
        val name: String,
        val total: Long,
        /** Pemilik pekerjaan (sesi); dipakai untuk membatalkan transfer satu sesi saja. */
        val owner: Any? = null,
        val work: suspend (JobCtx) -> Unit,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(MAX_PARALLEL)
    private val running = ConcurrentHashMap<Long, Job>()
    private val specs = ConcurrentHashMap<Long, Spec>()
    private val ids = AtomicLong(1)

    private val _jobs = MutableStateFlow<List<TransferJob>>(emptyList())
    val jobs: StateFlow<List<TransferJob>> = _jobs.asStateFlow()

    private val _completed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emit setiap ada transfer selesai (sukses/gagal/batal) agar UI bisa menyegarkan daftar. */
    val completed: SharedFlow<Unit> = _completed.asSharedFlow()

    // ---------------------------------------------------------------- konflik

    private val conflictMutex = Mutex()
    private var pendingAnswer: CompletableDeferred<Pair<ConflictAction, Boolean>>? = null
    @Volatile
    private var sticky: ConflictAction? = null

    private val _conflict = MutableStateFlow<ConflictRequest?>(null)
    /** Permintaan konflik yang sedang menunggu jawaban (UI menampilkan dialog). */
    val conflict: StateFlow<ConflictRequest?> = _conflict.asStateFlow()

    fun answerConflict(action: ConflictAction, applyToAll: Boolean) {
        pendingAnswer?.complete(action to applyToAll)
    }

    private suspend fun resolve(
        jobId: Long,
        direction: Direction,
        name: String,
        existingSize: Long,
        newSize: Long,
        canResume: Boolean,
    ): ConflictAction {
        fun adjust(a: ConflictAction) = if (a == ConflictAction.RESUME && !canResume) ConflictAction.OVERWRITE else a
        sticky?.let { return adjust(it) }
        return conflictMutex.withLock {
            sticky?.let { return@withLock adjust(it) }
            val answer = CompletableDeferred<Pair<ConflictAction, Boolean>>()
            pendingAnswer = answer
            _conflict.value = ConflictRequest(jobId, direction, name, existingSize, newSize, canResume)
            try {
                val (action, all) = answer.await()
                if (all) sticky = action
                adjust(action)
            } finally {
                pendingAnswer = null
                _conflict.value = null
            }
        }
    }

    /** Jawaban "terapkan ke semua" hanya berlaku untuk satu gelombang transfer. */
    private fun beginBatch() {
        if (_jobs.value.none { it.isActive }) sticky = null
    }

    // ---------------------------------------------------------------- inti antrean

    private fun patch(id: Long, f: (TransferJob) -> TransferJob) =
        _jobs.update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun submit(spec: Spec) {
        val id = ids.getAndIncrement()
        specs[id] = spec
        _jobs.update { it + TransferJob(id, spec.direction, spec.name, spec.total) }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            gate.withPermit {
                val self = coroutineContext[Job]!!
                self.ensureActive()
                patch(id) { it.copy(state = JobState.RUNNING) }
                var done = 0L
                var lastEmit = 0L
                val ctx = JobCtx(
                    id = id,
                    job = self,
                    onBytes = { n ->
                        done += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmit >= 200) {
                            lastEmit = now
                            val snapshot = done
                            patch(id) { it.copy(done = snapshot) }
                        }
                    },
                    setTotal = { t -> patch(id) { it.copy(total = t) } },
                )
                try {
                    spec.work(ctx)
                    val finalDone = done
                    val note = if (ctx.skipped > 0) "${ctx.skipped} berkas dilewati" else null
                    patch(id) {
                        it.copy(state = JobState.DONE, done = if (it.total >= 0) it.total else finalDone, note = note)
                    }
                } catch (e: CancellationException) {
                    patch(id) { it.copy(state = JobState.CANCELLED) }
                    throw e
                } catch (e: Throwable) {
                    patch(id) { it.copy(state = JobState.FAILED, error = friendlyError(e)) }
                }
            }
        }
        running[id] = job
        job.invokeOnCompletion {
            running.remove(id)
            _completed.tryEmit(Unit)
        }
        job.start()
    }

    fun cancel(id: Long) {
        patch(id) { if (it.isActive) it.copy(state = JobState.CANCELLED) else it }
        running[id]?.cancel()
    }

    fun cancelAll() = _jobs.value.filter { it.isActive }.forEach { cancel(it.id) }

    private fun activeIdsOf(owner: Any): List<Long> {
        val mine = specs.entries.filter { it.value.owner === owner }.map { it.key }.toSet()
        return _jobs.value.filter { it.isActive && it.id in mine }.map { it.id }
    }

    /** Batalkan hanya transfer milik satu sesi (mis. saat sesi itu diputus). */
    fun cancelFor(owner: Any) = activeIdsOf(owner).forEach { cancel(it) }

    fun activeCountFor(owner: Any): Int = activeIdsOf(owner).size

    /** Ulangi pekerjaan gagal/dibatalkan. Berkas parsial akan menawarkan "Lanjutkan" lewat dialog konflik. */
    fun retry(id: Long) {
        val spec = specs[id] ?: return
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (!job.canRetry) return
        _jobs.update { list -> list.filter { it.id != id } }
        specs.remove(id)
        beginBatch()
        submit(spec)
    }

    fun clearFinished() {
        val finished = _jobs.value.filter { !it.isActive }.map { it.id }.toSet()
        finished.forEach { specs.remove(it) }
        _jobs.update { list -> list.filter { it.id !in finished } }
    }

    // ---------------------------------------------------------------- unggah

    fun enqueueUploads(context: Context, fs: RemoteFs, uris: List<Uri>, remoteDir: String) {
        val resolver = context.applicationContext.contentResolver
        beginBatch()
        uris.forEach { uri ->
            val (name, size) = queryMeta(resolver, uri)
            submit(Spec(Direction.UPLOAD, name, size, owner = fs) { ctx ->
                if (size < 0) throw IOException("Ukuran berkas tidak diketahui")
                uploadOne(ctx, resolver, fs, uri, name, size, remoteDir, null)
            })
        }
    }

    /** Unggah seluruh isi [tree] (folder SAF) ke [remoteDir]/<nama folder>. */
    fun enqueueFolderUpload(context: Context, fs: RemoteFs, tree: Uri, remoteDir: String) {
        val ctx0 = context.applicationContext
        val root = DocumentFile.fromTreeUri(ctx0, tree)
        beginBatch()
        submit(Spec(Direction.UPLOAD, root?.name ?: "folder", -1, owner = fs) { ctx ->
            if (root == null || !root.isDirectory) throw IOException("Folder tidak dapat dibaca")
            ctx.setTotal(sumSize(root, ctx.check))
            uploadTree(ctx, ctx0.contentResolver, fs, root, remoteDir, null)
        })
    }

    private suspend fun uploadTree(
        ctx: JobCtx,
        resolver: ContentResolver,
        fs: RemoteFs,
        dir: DocumentFile,
        remoteParent: String,
        forced: ConflictAction?,
    ) {
        val remoteDir = joinPath(remoteParent, safeName(dir.name ?: "folder"))
        val existing = fs.stat(remoteDir)
        when {
            existing == null -> fs.mkdir(remoteDir)
            !existing.isDirectory -> throw IOException("\"${baseName(remoteDir)}\" sudah ada sebagai berkas di server")
        }
        for (child in dir.listFiles()) {
            ctx.check()
            val name = child.name ?: continue
            if (child.isDirectory) uploadTree(ctx, resolver, fs, child, remoteDir, forced)
            else uploadOne(ctx, resolver, fs, child.uri, safeName(name), child.length(), remoteDir, forced)
        }
    }

    private fun sumSize(dir: DocumentFile, check: () -> Unit): Long {
        var total = 0L
        for (c in dir.listFiles()) {
            check()
            total += if (c.isDirectory) sumSize(c, check) else c.length()
        }
        return total
    }

    private suspend fun uploadOne(
        ctx: JobCtx,
        resolver: ContentResolver,
        fs: RemoteFs,
        uri: Uri,
        name: String,
        size: Long,
        remoteDir: String,
        forced: ConflictAction?,
    ) {
        ctx.check()
        var target = joinPath(remoteDir, name)
        var offset = 0L
        val existing = fs.stat(target)
        if (existing != null) {
            if (existing.isDirectory) throw IOException("\"$name\" sudah ada sebagai folder di server")
            val canResume = fs.supportsResume && existing.size in 1 until size
            when (forced ?: resolve(ctx.id, Direction.UPLOAD, name, existing.size, size, canResume)) {
                ConflictAction.SKIP -> { ctx.skipped++; return }
                ConflictAction.RENAME -> target = joinPath(remoteDir, freeRemoteName(fs, remoteDir, name))
                ConflictAction.RESUME -> offset = existing.size
                ConflictAction.OVERWRITE -> Unit
            }
        }
        val input = resolver.openInputStream(uri) ?: throw IOException("Tidak bisa membuka $name")
        input.use {
            if (offset > 0) {
                skipFully(it, offset)
                ctx.onBytes(offset)
            }
            fs.upload(target, size - offset, it, offset, ctx.onBytes, ctx.check)
        }
        if (offset > 0 && fs.stat(target)?.size != size) {
            throw IOException("Ukuran di server tidak cocok setelah resume; unggah ulang dengan opsi Timpa")
        }
    }

    private fun freeRemoteName(fs: RemoteFs, dir: String, name: String): String {
        for (i in 1..999) {
            val candidate = numbered(name, i)
            if (!fs.exists(joinPath(dir, candidate))) return candidate
        }
        throw IOException("Tidak bisa membuat nama unik untuk $name")
    }

    private fun queryMeta(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (size < 0) {
            size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        }
        return safeName(name ?: uri.lastPathSegment ?: "berkas") to size
    }

    // ---------------------------------------------------------------- unduh

    fun enqueueDownloads(context: Context, fs: RemoteFs, entries: List<RemoteEntry>, tree: Uri) {
        val ctx0 = context.applicationContext
        val root = DocumentFile.fromTreeUri(ctx0, tree)
        beginBatch()
        entries.forEach { entry ->
            submit(Spec(Direction.DOWNLOAD, entry.name, if (entry.isDirectory) -1 else entry.size, owner = fs) { ctx ->
                if (root == null || !root.canWrite()) throw IOException("Folder unduhan tidak dapat ditulis; pilih ulang folder")
                downloadEntry(ctx, ctx0, fs, entry, root, null)
            })
        }
    }

    private suspend fun downloadEntry(
        ctx: JobCtx,
        appCtx: Context,
        fs: RemoteFs,
        entry: RemoteEntry,
        parent: DocumentFile,
        forced: ConflictAction?,
    ) {
        ctx.check()
        val name = entry.name
        // Server tidak dipercaya: nama berisi pemisah path atau titik-titik tidak boleh dipakai.
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\u0000' in name) {
            throw IOException("Nama berkas tidak aman dari server: \"$name\"")
        }
        if (entry.isDirectory) {
            if (entry.isSymlink) return // hindari putaran tak berujung lewat symlink
            val dir = parent.findFile(name)?.takeIf { it.isDirectory }
                ?: parent.createDirectory(name)
                ?: throw IOException("Tidak bisa membuat folder $name")
            for (child in fs.list(entry.path)) downloadEntry(ctx, appCtx, fs, child, dir, forced)
            return
        }
        downloadFile(ctx, appCtx, fs, entry, parent, forced)
    }

    private suspend fun downloadFile(
        ctx: JobCtx,
        appCtx: Context,
        fs: RemoteFs,
        entry: RemoteEntry,
        parent: DocumentFile,
        forced: ConflictAction?,
    ) {
        var targetName = entry.name
        var existing = parent.findFile(targetName)
        var offset = 0L
        if (existing != null) {
            if (existing.isDirectory) throw IOException("\"${entry.name}\" sudah ada sebagai folder di perangkat")
            val have = existing.length()
            val canResume = fs.supportsResume && have in 1 until entry.size
            when (forced ?: resolve(ctx.id, Direction.DOWNLOAD, entry.name, have, entry.size, canResume)) {
                ConflictAction.SKIP -> { ctx.skipped++; return }
                ConflictAction.RENAME -> {
                    targetName = freeLocalName(parent, entry.name)
                    existing = null
                }
                ConflictAction.RESUME -> offset = have
                ConflictAction.OVERWRITE -> Unit
            }
        }
        val file = existing
            ?: parent.createFile(mimeFor(targetName), targetName)
            ?: throw IOException("Tidak bisa membuat berkas $targetName")
        // Tidak semua penyedia berkas mendukung mode append; bila gagal, unduh ulang dari awal.
        val appendOut = if (offset > 0) runCatching { appCtx.contentResolver.openOutputStream(file.uri, "wa") }.getOrNull() else null
        if (appendOut == null) offset = 0
        val out = appendOut
            ?: appCtx.contentResolver.openOutputStream(file.uri, "wt")
            ?: throw IOException("Tidak bisa menulis $targetName")
        if (offset > 0) ctx.onBytes(offset)
        // Berkas parsial sengaja dibiarkan bila gagal/dibatalkan agar bisa dilanjutkan lewat "Lanjutkan".
        out.buffered(64 * 1024).use { fs.download(entry.path, it, offset, ctx.onBytes, ctx.check) }
        if (offset > 0 && file.length() != entry.size) {
            // Penyedia yang diam-diam memotong berkas saat "wa" akan menghasilkan berkas rusak; jangan dilaporkan sukses.
            throw IOException("Ukuran hasil resume tidak cocok dengan server; ulangi dengan opsi Timpa")
        }
    }

    private fun freeLocalName(parent: DocumentFile, name: String): String {
        for (i in 1..999) {
            val candidate = numbered(name, i)
            if (parent.findFile(candidate) == null) return candidate
        }
        throw IOException("Tidak bisa membuat nama unik untuk $name")
    }

    // ---------------------------------------------------------------- sinkronisasi

    fun enqueueSync(context: Context, fs: RemoteFs, remoteRoot: String, localTree: Uri, plan: SyncPlan) {
        val ctx0 = context.applicationContext
        val root = DocumentFile.fromTreeUri(ctx0, localTree)
        val resolver = ctx0.contentResolver
        beginBatch()
        val title = "Sinkronisasi " + baseName(remoteRoot).ifEmpty { "/" }
        submit(Spec(Direction.SYNC, title, plan.bytesToCopy, owner = fs) { ctx ->
            if (root == null || !root.canWrite()) throw IOException("Folder lokal tidak dapat ditulis")
            val dirs = HashMap<String, DocumentFile>(plan.localDirs).also { it[""] = root }
            val l2r = plan.direction == SyncDirection.LOCAL_TO_REMOTE

            for (item in plan.items) {
                ctx.check()
                val parentRel = item.rel.substringBeforeLast('/', "")
                val name = item.rel.substringAfterLast('/')
                when (item.op) {
                    SyncOp.DELETE -> if (l2r) {
                        item.remote?.let { fs.delete(it) }
                    } else {
                        item.local?.delete()
                    }
                    SyncOp.COPY, SyncOp.UPDATE -> if (l2r) {
                        val remoteParent = if (parentRel.isEmpty()) remoteRoot else joinPath(remoteRoot, parentRel)
                        if (item.isDir) {
                            fs.mkdirs(joinPath(remoteParent, name))
                        } else {
                            val doc = item.local ?: throw IOException("Berkas lokal hilang: ${item.rel}")
                            uploadOne(ctx, resolver, fs, doc.uri, safeName(name), item.size, remoteParent, ConflictAction.OVERWRITE)
                        }
                    } else {
                        val parent = dirs[parentRel] ?: throw IOException("Folder lokal tidak ditemukan: $parentRel")
                        val entry = item.remote ?: throw IOException("Entri remote hilang: ${item.rel}")
                        if (item.isDir) {
                            val dir = parent.findFile(name)?.takeIf { it.isDirectory }
                                ?: parent.createDirectory(name)
                                ?: throw IOException("Tidak bisa membuat folder $name")
                            dirs[item.rel] = dir
                        } else {
                            downloadFile(ctx, ctx0, fs, entry, parent, ConflictAction.OVERWRITE)
                        }
                    }
                }
            }
        })
    }

    // ---------------------------------------------------------------- util

    /** Nama dari penyedia berkas/pengguna tidak dipercaya: buang pemisah path dan karakter kontrol. */
    private fun safeName(raw: String): String =
        raw.substringAfterLast('/').replace('\u0000', '_').trim().ifBlank { "berkas" }

    private fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private companion object {
        const val MAX_PARALLEL = 2
    }
}
