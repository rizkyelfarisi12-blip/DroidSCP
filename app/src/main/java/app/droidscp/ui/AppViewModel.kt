package app.droidscp.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.droidscp.DroidScpApp
import app.droidscp.core.HostKeyChangedException
import app.droidscp.core.HostKeyUnknownException
import app.droidscp.core.RECONNECT_DELAYS_MS
import app.droidscp.core.ReconnectableFs
import app.droidscp.core.RemoteEntry
import app.droidscp.core.isFatalConnectError
import app.droidscp.core.retryWithBackoff
import app.droidscp.core.splitHostKey
import app.droidscp.core.toggleBookmark
import app.droidscp.core.uniqueTitle
import app.droidscp.core.RemoteFs
import app.droidscp.core.connectRemote
import app.droidscp.core.Site
import app.droidscp.core.baseName
import app.droidscp.core.formatSize
import app.droidscp.core.friendlyError
import app.droidscp.core.joinPath
import app.droidscp.core.parentPath
import androidx.documentfile.provider.DocumentFile
import app.droidscp.data.SyncDirection
import app.droidscp.data.SyncPlan
import app.droidscp.data.SyncPlanner
import app.droidscp.data.TransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class Screen { SITES, EDIT_SITE, BROWSER, TRANSFERS, EDITOR }

/** Permintaan konfirmasi host: pertama kali (oldFingerprint == null) atau fingerprint berubah. */
data class HostPrompt(
    val site: Site,
    val fingerprint: String,
    val keyType: String,
    val oldFingerprint: String?,
)

data class EditorState(val path: String, val text: String)

enum class SortKey { NAME, SIZE, DATE }

/** Keadaan dialog sinkronisasi folder. */
data class SyncState(
    val remotePath: String,
    val localTree: Uri? = null,
    val localName: String? = null,
    val direction: SyncDirection = SyncDirection.REMOTE_TO_LOCAL,
    val deleteExtra: Boolean = false,
    val analyzing: Boolean = false,
    val plan: SyncPlan? = null,
)

data class UiState(
    val screen: Screen = Screen.SITES,
    val editingSite: Site? = null,
    val connecting: Boolean = false,
    val site: Site? = null,
    val path: String = "/",
    val entries: List<RemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val selected: Set<String> = emptySet(),
    val showHidden: Boolean = false,
    val message: String? = null,
    val hostPrompt: HostPrompt? = null,
    val editor: EditorState? = null,
    val pickDownloadDir: Boolean = false,
    val pendingDownload: List<RemoteEntry>? = null,
    val sortKey: SortKey = SortKey.NAME,
    val sortAsc: Boolean = true,
    val searching: Boolean = false,
    val filter: String = "",
    /** Item yang dipotong (menunggu ditempel di folder lain). */
    val clipboard: List<RemoteEntry> = emptyList(),
    val sync: SyncState? = null,
    /** Sedang menyambung ulang setelah koneksi putus. */
    val reconnecting: Boolean = false,
    /** Berkas hasil unduhan sementara yang siap dibuka aplikasi lain; dikonsumsi UI sekali. */
    val openFile: OpenFile? = null,
    val trustedHosts: List<TrustedHost>? = null,
    /** Semua sesi yang terbuka (tab) dan yang sedang aktif. */
    val sessions: List<SessionTab> = emptyList(),
    val activeSession: Int? = null,
    /** Daftar situs ditampilkan untuk membuka sesi tambahan; sesi yang ada tetap terbuka. */
    val addingSession: Boolean = false,
)

data class SessionTab(val id: Int, val title: String)

/** Berkas sementara untuk dibuka lewat aplikasi lain. [path] adalah path berkas di cache aplikasi. */
data class OpenFile(val path: String, val name: String, val mime: String)

data class TrustedHost(val host: String, val port: Int, val fingerprint: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as DroidScpApp
    val repo = graph.sites
    private val knownHosts = graph.knownHosts
    val transfers = graph.transfers
    val sites = repo.sites

    private val settings = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Satu sesi terbuka: sambungan, situs, dan folder terakhir yang dibuka (dipulihkan saat berpindah tab). */
    private class Slot(val id: Int, val fs: ReconnectableFs, var site: Site, val title: String, var path: String)

    private val slotLock = Any()
    private val slots = LinkedHashMap<Int, Slot>()
    private val nextSlotId = AtomicInteger(1)

    @Volatile
    private var activeId: Int? = null

    /** Sesi aktif; semua operasi jelajah dan transfer menuju sesi ini. */
    @Volatile
    private var session: ReconnectableFs? = null
    private val reconnectingFlag = AtomicBoolean(false)

    private fun tabs(): List<SessionTab> = synchronized(slotLock) { slots.values.map { SessionTab(it.id, it.title) } }

    private fun slotOf(s: ReconnectableFs): Slot? = synchronized(slotLock) { slots.values.firstOrNull { it.fs === s } }

    private fun rememberActivePath() {
        val id = activeId ?: return
        val path = _ui.value.path
        synchronized(slotLock) { slots[id]?.path = path }
    }

    init {
        // Sisa berkas sementara "buka dengan aplikasi lain" dari proses sebelumnya.
        viewModelScope.launch(Dispatchers.IO) { runCatching { openCacheDir().deleteRecursively() } }
        viewModelScope.launch {
            transfers.completed.collect { if (session != null && _ui.value.screen != Screen.EDITOR) refresh() }
        }
    }

    // ---------------------------------------------------------------- situs

    fun editSite(site: Site) = _ui.update { it.copy(editingSite = site, screen = Screen.EDIT_SITE) }

    fun saveSite(site: Site) {
        repo.save(site)
        _ui.update { it.copy(editingSite = null, screen = Screen.SITES) }
    }

    fun cancelEdit() = _ui.update { it.copy(editingSite = null, screen = Screen.SITES) }

    fun deleteSite(site: Site) = repo.delete(site)

    // ---------------------------------------------------------------- koneksi

    fun connect(site: Site) {
        if (_ui.value.connecting) return
        _ui.update { it.copy(connecting = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = connectRemote(site, repo.keys, knownHosts)
                val start = runCatching { s.canonical(site.initialRemoteDir.ifBlank { "." }) }.getOrElse { s.home() }
                val entries = try {
                    s.list(start)
                } catch (e: Exception) {
                    s.close(); throw e
                }
                val fs = ReconnectableFs(s)
                rememberActivePath()
                val slot = synchronized(slotLock) {
                    val title = uniqueTitle(slots.values.map { it.title }, site.displayName)
                    Slot(nextSlotId.getAndIncrement(), fs, site, title, start).also { slots[it.id] = it }
                }
                session = fs
                activeId = slot.id
                _ui.update {
                    it.copy(
                        connecting = false, site = site, screen = Screen.BROWSER, path = start,
                        entries = entries, selected = emptySet(), loading = false,
                        filter = "", searching = false, clipboard = emptyList(), sync = null,
                        sessions = tabs(), activeSession = slot.id, addingSession = false,
                    )
                }
            } catch (e: HostKeyUnknownException) {
                _ui.update { it.copy(connecting = false, hostPrompt = HostPrompt(site, e.fingerprint, e.keyType, null)) }
            } catch (e: HostKeyChangedException) {
                _ui.update {
                    it.copy(connecting = false, hostPrompt = HostPrompt(site, e.newFingerprint, "", e.oldFingerprint))
                }
            } catch (e: Exception) {
                _ui.update { it.copy(connecting = false, message = "Gagal terhubung. ${friendlyError(e)}") }
            }
        }
    }

    fun confirmHost() {
        val p = _ui.value.hostPrompt ?: return
        knownHosts.put(p.site.host, p.site.port, p.fingerprint)
        _ui.update { it.copy(hostPrompt = null) }
        connect(p.site)
    }

    fun dismissHost() = _ui.update { it.copy(hostPrompt = null) }

    /** Putuskan sesi aktif. Sesi lain tetap terbuka; bila tidak ada lagi, kembali ke daftar situs. */
    fun disconnect() = dropSession(activeId, null)

    /** Buka daftar situs untuk menyambung sesi tambahan tanpa memutus yang ada. */
    fun newSession() {
        rememberActivePath()
        _ui.update { it.copy(addingSession = true, screen = Screen.SITES, selected = emptySet()) }
    }

    fun cancelAddSession() {
        val id = activeId
        if (id != null) switchSession(id) else _ui.update { it.copy(addingSession = false) }
    }

    fun switchSession(id: Int) {
        if (id != activeId) rememberActivePath()
        val target = synchronized(slotLock) { slots[id] } ?: return
        if (id == activeId) {
            _ui.update { it.copy(addingSession = false, screen = Screen.BROWSER) }
            return
        }
        activate(target, null)
    }

    /** Jadikan [slot] sesi aktif dan muat ulang foldernya (bila gagal, folder awal). */
    private fun activate(slot: Slot, msg: String?) {
        session = slot.fs
        activeId = slot.id
        _ui.update {
            it.copy(
                screen = Screen.BROWSER, site = slot.site, path = slot.path, entries = emptyList(),
                selected = emptySet(), editor = null, loading = false, filter = "", searching = false,
                clipboard = emptyList(), sync = null, sessions = tabs(), activeSession = slot.id,
                addingSession = false, message = msg ?: it.message,
            )
        }
        io { s ->
            val (p, entries) = try {
                slot.path to s.list(slot.path)
            } catch (e: Exception) {
                if (!s.isAlive) throw e
                val h = s.home()
                h to s.list(h)
            }
            slot.path = p
            _ui.update { if (session === s) it.copy(path = p, entries = entries) else it }
        }
    }

    /** Tutup satu sesi dan batalkan transfernya saja. Sesi aktif yang ditutup digantikan sesi terakhir yang tersisa. */
    private fun dropSession(id: Int?, msg: String?) {
        val (removed, next) = synchronized(slotLock) { (id?.let { slots.remove(it) }) to slots.values.lastOrNull() }
        val wasActive = removed != null && removed.id == activeId
        if (wasActive) { // dulu, agar pembatalan transfer tidak memicu penyegaran pada sesi yang ditutup
            session = null
            activeId = null
        }
        if (removed != null) {
            transfers.cancelFor(removed.fs)
            viewModelScope.launch(Dispatchers.IO) { runCatching { removed.fs.close() } }
        }
        when {
            !wasActive -> _ui.update { it.copy(sessions = tabs(), message = msg ?: it.message) }
            next != null -> activate(next, msg)
            else -> _ui.update {
                it.copy(
                    screen = Screen.SITES, site = null, entries = emptyList(), selected = emptySet(),
                    editor = null, loading = false, message = msg ?: it.message,
                    filter = "", searching = false, clipboard = emptyList(), sync = null,
                    sessions = emptyList(), activeSession = null, addingSession = false,
                )
            }
        }
    }

    /** Jumlah transfer yang masih berjalan pada sesi aktif (untuk peringatan saat memutus). */
    fun activeTransfersHere(): Int = session?.let { transfers.activeCountFor(it) } ?: 0

    /**
     * Sesi mati: coba sambung ulang beberapa kali (jeda bertahap). Berhasil → sesi diganti di tempat, sehingga
     * antrean transfer dan folder yang sedang dibuka tetap berlaku. Kunci host berubah/login ditolak tidak diulang.
     * Dipanggil di thread IO.
     */
    private fun recover(s: ReconnectableFs, cause: Exception) {
        if (!reconnectingFlag.compareAndSet(false, true)) {
            message("Koneksi terputus, sedang menyambung ulang…")
            return
        }
        _ui.update { it.copy(reconnecting = true) }
        try {
            val site = s.site
            val fresh = retryWithBackoff(RECONNECT_DELAYS_MS, ::isFatalConnectError) {
                if (slotOf(s) == null) throw IOException("Dibatalkan") // pengguna sudah memutus sesi
                connectRemote(site, repo.keys, knownHosts)
            }
            if (slotOf(s) == null) {
                runCatching { fresh.close() }
                return
            }
            s.swap(fresh)
            if (session === s) { // sesi di latar belakang cukup tersambung lagi; daftar dimuat saat tab dibuka
                var path = _ui.value.path
                val entries = try {
                    fresh.list(path)
                } catch (e: Exception) {
                    path = runCatching { fresh.home() }.getOrDefault("/")
                    fresh.list(path)
                }
                _ui.update { it.copy(path = path, entries = entries, selected = emptySet()) }
            }
            message("Tersambung kembali. Transfer yang terputus bisa diulangi dari layar Transfer")
        } catch (e: Exception) {
            slotOf(s)?.let { slot ->
                val why = friendlyError(if (isFatalConnectError(e)) e else cause)
                dropSession(slot.id, "Sesi ${slot.title} terputus. $why")
            }
        } finally {
            reconnectingFlag.set(false)
            _ui.update { it.copy(reconnecting = false) }
        }
    }

    // ---------------------------------------------------------------- penjelajahan

    /** Jalankan operasi remote di thread IO; kegagalan jadi pesan, bukan crash. */
    private fun io(showLoading: Boolean = true, block: (RemoteFs) -> Unit) {
        val s = session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (showLoading) _ui.update { it.copy(loading = true) }
            try {
                block(s)
            } catch (e: Exception) {
                if (!s.isAlive) recover(s, e)
                else message(friendlyError(e))
            } finally {
                _ui.update { it.copy(loading = false) }
            }
        }
    }

    fun navigate(path: String) = io { s ->
        val entries = s.list(path)
        _ui.update { it.copy(path = path, entries = entries, selected = emptySet(), filter = "") }
    }

    fun refresh() = io(showLoading = false) { s ->
        val path = _ui.value.path
        val entries = s.list(path)
        _ui.update { if (it.path == path) it.copy(entries = entries, selected = emptySet()) else it }
    }

    fun up() = navigate(parentPath(_ui.value.path))

    fun open(entry: RemoteEntry) {
        when {
            entry.isDirectory -> navigate(entry.path)
            entry.size > MAX_EDIT_BYTES -> message("Berkas ${formatSize(entry.size)} terlalu besar untuk dibuka; gunakan Unduh")
            else -> openEditor(entry)
        }
    }

    fun toggleSelect(entry: RemoteEntry) = _ui.update {
        it.copy(selected = if (entry.path in it.selected) it.selected - entry.path else it.selected + entry.path)
    }

    fun clearSelection() = _ui.update { it.copy(selected = emptySet()) }

    fun selectAll(entries: List<RemoteEntry>) = _ui.update { it.copy(selected = entries.map { e -> e.path }.toSet()) }

    fun toggleHidden() = _ui.update { it.copy(showHidden = !it.showHidden) }

    // ---------------------------------------------------------------- bookmark folder

    /** Tandai/lepas tanda folder yang sedang dibuka; disimpan pada situs (terenkripsi bersama profil). */
    fun toggleBookmarkHere() {
        val st = _ui.value
        val site = st.site ?: return
        val updated = site.copy(bookmarks = toggleBookmark(site.bookmarks, st.path))
        repo.save(updated)
        activeId?.let { id -> synchronized(slotLock) { slots[id]?.site = updated } }
        _ui.update { it.copy(site = updated) }
    }

    // ---------------------------------------------------------------- host tepercaya

    private fun loadTrusted(): List<TrustedHost> = knownHosts.all().mapNotNull { (k, v) ->
        splitHostKey(k)?.let { (h, p) -> TrustedHost(h, p, v) }
    }.sortedWith(compareBy({ it.host.lowercase() }, { it.port }))

    fun showTrustedHosts() = _ui.update { it.copy(trustedHosts = loadTrusted()) }

    fun closeTrustedHosts() = _ui.update { it.copy(trustedHosts = null) }

    /** Lupakan host: koneksi berikutnya akan meminta konfirmasi fingerprint lagi. */
    fun forgetHost(h: TrustedHost) {
        knownHosts.remove(h.host, h.port)
        _ui.update { it.copy(trustedHosts = loadTrusted()) }
    }

    // ---------------------------------------------------------------- buka dengan aplikasi lain

    private fun openCacheDir() = File(getApplication<Application>().cacheDir, "open")

    /** Unduh ke cache lalu minta UI membukanya lewat aplikasi lain (hanya baca; perubahan tidak dikirim balik). */
    fun openExternal(entry: RemoteEntry) {
        if (entry.isDirectory) return
        if (entry.size > MAX_EXTERNAL_BYTES) return message("Berkas ${formatSize(entry.size)} terlalu besar untuk dibuka langsung; gunakan Unduh")
        io { s ->
            val dir = File(openCacheDir(), java.util.UUID.randomUUID().toString())
            if (!dir.mkdirs()) throw IOException("Tidak bisa membuat folder sementara")
            val name = entry.name.replace('/', '_').replace('\\', '_').ifBlank { "berkas" }
            val file = File(dir, name)
            try {
                file.outputStream().buffered(64 * 1024).use { s.download(entry.path, it, 0, {}, {}) }
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            _ui.update { it.copy(openFile = OpenFile(file.absolutePath, name, mime)) }
        }
    }

    fun consumeOpenFile() = _ui.update { it.copy(openFile = null) }

    // ---------------------------------------------------------------- operasi berkas

    private fun validName(name: String): String? = when {
        name.isBlank() -> "Nama tidak boleh kosong"
        name == "." || name == ".." -> "Nama tidak valid"
        '/' in name -> "Nama tidak boleh memuat \"/\""
        else -> null
    }

    fun mkdir(name: String) {
        validName(name)?.let { return message(it) }
        io { s ->
            s.mkdir(joinPath(_ui.value.path, name.trim()))
            refreshNow(s)
        }
    }

    fun rename(entry: RemoteEntry, newName: String) {
        validName(newName)?.let { return message(it) }
        io { s ->
            val target = joinPath(parentPath(entry.path), newName.trim())
            if (s.exists(target)) throw IOException("\"${newName.trim()}\" sudah ada")
            s.rename(entry.path, target)
            refreshNow(s)
        }
    }

    fun delete(entries: List<RemoteEntry>) = io { s ->
        entries.forEach { s.delete(it) }
        refreshNow(s)
        message("${entries.size} item dihapus")
    }

    fun chmod(entry: RemoteEntry, mask: Int) = io { s ->
        s.chmod(entry.path, mask and 0xFFF)
        refreshNow(s)
    }

    private fun refreshNow(s: RemoteFs) {
        val path = _ui.value.path
        val entries = s.list(path)
        _ui.update { it.copy(entries = entries, selected = emptySet()) }
    }

    // ---------------------------------------------------------------- editor

    private fun openEditor(entry: RemoteEntry) = io { s ->
        val bytes = s.readBytes(entry.path, MAX_EDIT_BYTES)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw IOException("${entry.name} bukan berkas teks UTF-8; gunakan Unduh")
        }
        _ui.update { it.copy(screen = Screen.EDITOR, editor = EditorState(entry.path, text)) }
    }

    fun saveEditor(text: String) {
        val path = _ui.value.editor?.path ?: return
        io { s ->
            s.writeBytes(path, text.toByteArray(Charsets.UTF_8))
            _ui.update { it.copy(editor = EditorState(path, text)) }
            message("${baseName(path)} tersimpan")
        }
    }

    fun closeEditor() = _ui.update { it.copy(screen = Screen.BROWSER, editor = null) }

    // ---------------------------------------------------------------- transfer

    fun showTransfers() = _ui.update { it.copy(screen = Screen.TRANSFERS) }

    fun closeTransfers() = _ui.update { it.copy(screen = if (it.site != null) Screen.BROWSER else Screen.SITES) }

    fun upload(uris: List<Uri>) {
        val s = session ?: return
        if (uris.isEmpty()) return
        transfers.enqueueUploads(getApplication(), s, uris, _ui.value.path)
        TransferService.start(getApplication())
        message("${uris.size} berkas masuk antrean unggah")
    }

    fun download(entries: List<RemoteEntry>) {
        if (entries.isEmpty()) return
        val tree = downloadTree()
        if (tree == null) {
            _ui.update { it.copy(pendingDownload = entries, pickDownloadDir = true) }
        } else {
            startDownload(entries, tree)
        }
    }

    fun chooseDownloadDir() = _ui.update { it.copy(pickDownloadDir = true) }

    fun onDownloadDirPicked(uri: Uri?) {
        val pending = _ui.value.pendingDownload
        _ui.update { it.copy(pickDownloadDir = false, pendingDownload = null) }
        if (uri == null) return
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        settings.edit().putString(KEY_DOWNLOAD_TREE, uri.toString()).apply()
        if (pending != null) startDownload(pending, uri)
        else message("Folder unduhan diperbarui")
    }

    private fun downloadTree(): Uri? {
        val uri = settings.getString(KEY_DOWNLOAD_TREE, null)?.let(Uri::parse) ?: return null
        // Izin bisa dicabut pengguna lewat pengaturan sistem; periksa masih tersimpan.
        val ok = getApplication<Application>().contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return if (ok) uri else null
    }

    private fun startDownload(entries: List<RemoteEntry>, tree: Uri) {
        val s = session ?: return
        transfers.enqueueDownloads(getApplication(), s, entries, tree)
        TransferService.start(getApplication())
        message("${entries.size} item masuk antrean unduh")
    }

    // ---------------------------------------------------------------- urut, cari, potong-tempel

    fun setSort(key: SortKey) = _ui.update {
        if (it.sortKey == key) it.copy(sortAsc = !it.sortAsc) else it.copy(sortKey = key, sortAsc = true)
    }

    fun toggleSearch() = _ui.update { it.copy(searching = !it.searching, filter = "") }

    fun setFilter(text: String) = _ui.update { it.copy(filter = text) }

    fun cut(entries: List<RemoteEntry>) {
        _ui.update { it.copy(clipboard = entries, selected = emptySet()) }
        message("${entries.size} item dipotong; buka folder tujuan lalu ketuk Tempel")
    }

    fun clearClipboard() = _ui.update { it.copy(clipboard = emptyList()) }

    fun paste() {
        val items = _ui.value.clipboard
        if (items.isEmpty()) return
        val dest = _ui.value.path
        io { s ->
            var moved = 0
            val problems = mutableListOf<String>()
            for (e in items) {
                val target = joinPath(dest, e.name)
                when {
                    e.path == target -> problems += "${e.name}: sudah di folder ini"
                    e.isDirectory && (dest == e.path || dest.startsWith(e.path.trimEnd('/') + "/")) ->
                        problems += "${e.name}: tidak bisa dipindah ke dalam dirinya sendiri"
                    s.exists(target) -> problems += "${e.name}: sudah ada di tujuan"
                    else -> { s.rename(e.path, target); moved++ }
                }
            }
            _ui.update { it.copy(clipboard = emptyList()) }
            refreshNow(s)
            message(buildString {
                append("$moved item dipindahkan")
                if (problems.isNotEmpty()) append(". Dilewati: ").append(problems.take(3).joinToString("; "))
            })
        }
    }

    // ---------------------------------------------------------------- unggah folder

    fun uploadFolder(uri: Uri?) {
        val s = session ?: return
        if (uri == null) return
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        transfers.enqueueFolderUpload(getApplication(), s, uri, _ui.value.path)
        TransferService.start(getApplication())
        message("Folder masuk antrean unggah")
    }

    // ---------------------------------------------------------------- sinkronisasi

    fun openSync() = _ui.update { it.copy(sync = SyncState(remotePath = it.path)) }

    fun closeSync() = _ui.update { it.copy(sync = null) }

    private fun updateSync(f: (SyncState) -> SyncState) = _ui.update { st -> st.sync?.let { st.copy(sync = f(it)) } ?: st }

    fun setSyncTree(uri: Uri?) {
        if (uri == null) return
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val name = DocumentFile.fromTreeUri(getApplication(), uri)?.name
        updateSync { it.copy(localTree = uri, localName = name, plan = null) }
    }

    fun setSyncDirection(d: SyncDirection) = updateSync { it.copy(direction = d, plan = null) }

    fun setSyncDeleteExtra(v: Boolean) = updateSync { it.copy(deleteExtra = v, plan = null) }

    fun analyzeSync() {
        val s = session ?: return
        val st = _ui.value.sync ?: return
        val tree = st.localTree ?: return
        val root = DocumentFile.fromTreeUri(getApplication(), tree)
        if (root == null || !root.isDirectory) return message("Folder lokal tidak dapat dibaca")
        updateSync { it.copy(analyzing = true, plan = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val remote = SyncPlanner.scanRemote(s, st.remotePath) {}
                val local = SyncPlanner.scanLocal(root) {}
                val plan = SyncPlanner.plan(st.direction, remote, local, st.deleteExtra)
                updateSync { it.copy(analyzing = false, plan = plan) }
            } catch (e: Exception) {
                updateSync { it.copy(analyzing = false) }
                if (!s.isAlive) recover(s, e)
                else message("Analisis gagal. ${friendlyError(e)}")
            }
        }
    }

    fun runSync() {
        val s = session ?: return
        val st = _ui.value.sync ?: return
        val tree = st.localTree ?: return
        val plan = st.plan ?: return
        if (plan.isEmpty) return
        transfers.enqueueSync(getApplication(), s, st.remotePath, tree, plan)
        TransferService.start(getApplication())
        closeSync()
        message("Sinkronisasi masuk antrean")
    }

    // ---------------------------------------------------------------- pesan

    fun message(text: String) = _ui.update { it.copy(message = text) }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    override fun onCleared() {
        transfers.cancelAll()
        val all = synchronized(slotLock) { slots.values.toList().also { slots.clear() } }
        Thread { all.forEach { runCatching { it.fs.close() } } }.start()
        super.onCleared()
    }

    private companion object {
        const val MAX_EDIT_BYTES = 512 * 1024
        const val MAX_EXTERNAL_BYTES = 64L * 1024 * 1024
        const val KEY_DOWNLOAD_TREE = "download_tree"
    }
}
