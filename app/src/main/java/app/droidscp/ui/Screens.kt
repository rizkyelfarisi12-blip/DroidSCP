@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package app.droidscp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.PictureAsPdf
import java.time.ZoneId
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Dns
import app.droidscp.core.protocolSummary
import app.droidscp.core.siteAddress
import app.droidscp.data.TransferJob
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.ui.platform.LocalContext
import app.droidscp.core.formatOctal
import app.droidscp.core.parentPath
import java.io.File
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.droidscp.core.AuthType
import app.droidscp.core.Protocol
import app.droidscp.core.RemoteEntry
import app.droidscp.core.Site
import app.droidscp.core.baseName
import app.droidscp.core.formatPermissions
import app.droidscp.core.formatSize
import app.droidscp.data.ConflictAction
import app.droidscp.data.ConflictRequest
import app.droidscp.data.Direction
import app.droidscp.data.JobState
import app.droidscp.data.SyncDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ======================================================================= root

@Composable
fun DroidApp(vm: AppViewModel, state: UiState, sites: List<Site>) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    when (state.screen) {
        Screen.SITES -> SitesScreen(vm, sites, snackbar, state.addingSession)
        Screen.EDIT_SITE -> state.editingSite?.let { SiteEditScreen(vm, it, snackbar) }
        Screen.BROWSER -> BrowserScreen(vm, state, snackbar)
        Screen.TRANSFERS -> TransfersScreen(vm, snackbar)
        Screen.EDITOR -> state.editor?.let { EditorScreen(vm, state, it, snackbar) }
    }

    if (state.connecting) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Menghubungkan…") },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
        )
    }

    if (state.reconnecting) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Koneksi terputus") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator()
                    Text("Menyambung ulang…")
                }
            },
        )
    }

    val ctx = LocalContext.current
    LaunchedEffect(state.openFile) {
        val f = state.openFile ?: return@LaunchedEffect
        vm.consumeOpenFile()
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(f.path))
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, f.mime)
                clipData = ClipData.newRawUri(f.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(view, "Buka dengan").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            vm.message("Tidak ada aplikasi yang bisa membuka ${f.name}")
        }
    }

    state.trustedHosts?.let { TrustedHostsDialog(vm, it) }

    state.hostPrompt?.let { p ->
        val changed = p.oldFingerprint != null
        AlertDialog(
            onDismissRequest = vm::dismissHost,
            title = { Text(if (changed) "PERINGATAN: kunci host berubah" else "Host belum dikenal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (changed) {
                        Text(
                            "Kunci atau sertifikat server ${p.site.host}:${p.site.port} berbeda dari yang tersimpan. " +
                                "Ini bisa berarti server diinstal ulang atau sertifikatnya diperbarui, atau seseorang mencegat koneksi Anda.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text("Lama:", style = MaterialTheme.typography.labelMedium)
                        Text(p.oldFingerprint ?: "", fontFamily = FontFamily.Monospace)
                        Text("Baru:", style = MaterialTheme.typography.labelMedium)
                    } else {
                        Text("Keaslian ${p.site.host}:${p.site.port} belum dapat diverifikasi. Cocokkan fingerprint dengan yang diberikan administrator server.")
                        if (p.keyType.isNotBlank()) Text("Tipe: ${p.keyType}", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(p.fingerprint, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = vm::confirmHost) { Text(if (changed) "Ganti & percaya" else "Percaya & hubungkan") }
            },
            dismissButton = { TextButton(onClick = vm::dismissHost) { Text("Batal") } },
        )
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        vm.onDownloadDirPicked(it)
    }
    LaunchedEffect(state.pickDownloadDir) {
        if (state.pickDownloadDir) treeLauncher.launch(null)
    }

    val conflict by vm.transfers.conflict.collectAsState()
    conflict?.let { req -> ConflictDialog(req) { action, all -> vm.transfers.answerConflict(action, all) } }

    state.sync?.let { SyncDialog(vm, it) }
}

@Composable
private fun TrustedHostsDialog(vm: AppViewModel, hosts: List<TrustedHost>) {
    AlertDialog(
        onDismissRequest = vm::closeTrustedHosts,
        title = { Text("Host tepercaya") },
        text = {
            if (hosts.isEmpty()) {
                Text("Belum ada. Host yang Anda percayai saat pertama kali terhubung (SSH atau sertifikat FTPS) muncul di sini.")
            } else {
                LazyColumn {
                    items(hosts, key = { "${it.host}:${it.port}" }) { h ->
                        ListItem(
                            headlineContent = { Text("${h.host}:${h.port}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(h.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                            trailingContent = { IconButton(onClick = { vm.forgetHost(h) }) { Icon(Icons.Default.Delete, "Lupakan ${h.host}") } },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::closeTrustedHosts) { Text("Tutup") } },
    )
}

@Composable
private fun PropertiesDialog(e: RemoteEntry, dateFmt: SimpleDateFormat, onDismiss: () -> Unit) {
    val kind = when {
        e.isSymlink -> "Tautan simbolik"
        e.isDirectory -> "Folder"
        else -> "Berkas"
    }
    val rows = buildList {
        add("Nama" to e.name)
        add("Lokasi" to parentPath(e.path))
        add("Jenis" to kind)
        if (!e.isDirectory) add("Ukuran" to "${formatSize(e.size)} (${e.size} byte)")
        add("Izin" to "${e.permissionString} (${formatOctal(e.permissionMask)})")
        add("Diubah" to dateFmt.format(Date(e.modifiedEpochSec * 1000)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Properti") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { (k, v) ->
                    Column {
                        Text(k, style = MaterialTheme.typography.labelMedium)
                        Text(v)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

@Composable
private fun BookmarksDialog(vm: AppViewModel, state: UiState, onDismiss: () -> Unit) {
    val marks = state.site?.bookmarks.orEmpty()
    val here = if (state.path.length > 1) state.path.trimEnd('/') else state.path
    val marked = here in marks
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmark folder") },
        text = {
            Column {
                TextButton(onClick = vm::toggleBookmarkHere) {
                    Icon(if (marked) Icons.Default.Star else Icons.Default.StarBorder, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (marked) "Lepas tanda folder ini" else "Tandai folder ini")
                }
                HorizontalDivider()
                if (marks.isEmpty()) {
                    Text("Belum ada bookmark untuk situs ini.", modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    LazyColumn {
                        items(marks, key = { it }) { m ->
                            ListItem(
                                modifier = Modifier.clickable { vm.navigate(m); onDismiss() },
                                leadingContent = { Icon(Icons.Default.Folder, null) },
                                headlineContent = { Text(m, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

@Composable
private fun ConflictDialog(req: ConflictRequest, onAnswer: (ConflictAction, Boolean) -> Unit) {
    var all by remember(req.jobId, req.fileName) { mutableStateOf(false) }
    val where = if (req.direction == Direction.UPLOAD) "di server" else "di perangkat"
    AlertDialog(
        onDismissRequest = {}, // harus memilih; "Lewati" adalah pilihan paling aman
        title = { Text("Berkas sudah ada") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("\"${req.fileName}\" sudah ada $where.")
                Text(
                    "Yang ada: ${formatSize(req.existingSize)} · Baru: ${formatSize(req.newSize)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth().clickable { all = !all }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = all, onCheckedChange = { all = it })
                    Text("Terapkan ke semua berkas lain")
                }
                TextButton(onClick = { onAnswer(ConflictAction.OVERWRITE, all) }) { Text("Timpa") }
                if (req.canResume) {
                    TextButton(onClick = { onAnswer(ConflictAction.RESUME, all) }) {
                        Text("Lanjutkan (sudah ${formatSize(req.existingSize)})")
                    }
                }
                TextButton(onClick = { onAnswer(ConflictAction.RENAME, all) }) { Text("Simpan dengan nama lain") }
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(ConflictAction.SKIP, all) }) { Text("Lewati") } },
    )
}

@Composable
private fun SyncDialog(vm: AppViewModel, st: SyncState) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.setSyncTree(it) }
    val plan = st.plan
    AlertDialog(
        onDismissRequest = vm::closeSync,
        title = { Text("Sinkronisasi folder") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Remote: ${st.remotePath}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { picker.launch(null) }) {
                    Text(st.localName?.let { "Lokal: $it (ganti)" } ?: "Pilih folder lokal")
                }
                Text("Arah", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.fillMaxWidth().clickable { vm.setSyncDirection(SyncDirection.REMOTE_TO_LOCAL) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = st.direction == SyncDirection.REMOTE_TO_LOCAL, onClick = { vm.setSyncDirection(SyncDirection.REMOTE_TO_LOCAL) })
                    Text("Remote → lokal (unduh)")
                }
                Row(
                    Modifier.fillMaxWidth().clickable { vm.setSyncDirection(SyncDirection.LOCAL_TO_REMOTE) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = st.direction == SyncDirection.LOCAL_TO_REMOTE, onClick = { vm.setSyncDirection(SyncDirection.LOCAL_TO_REMOTE) })
                    Text("Lokal → remote (unggah)")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = st.deleteExtra, onCheckedChange = vm::setSyncDeleteExtra)
                    Text("Hapus yang tidak ada di sumber")
                }
                if (st.deleteExtra) {
                    Text(
                        "Berkas ekstra di tujuan akan DIHAPUS permanen. Periksa ringkasan sebelum menjalankan.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (st.analyzing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Menganalisis…")
                    }
                }
                if (plan != null) {
                    HorizontalDivider()
                    if (plan.isEmpty) {
                        Text("Sudah sinkron; tidak ada yang perlu dilakukan.")
                    } else {
                        Text(
                            "${plan.copies} berkas baru, ${plan.updates} diperbarui, ${plan.newDirs} folder baru" +
                                (if (plan.deletes > 0) ", ${plan.deletes} dihapus" else "") +
                                "\nData yang dipindahkan: ${formatSize(plan.bytesToCopy)}",
                        )
                    }
                    if (plan.newerAtTarget > 0) {
                        Text("${plan.newerAtTarget} berkas di tujuan lebih baru dan beda ukuran: dilewati.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (plan.typeMismatch > 0) {
                        Text("${plan.typeMismatch} path berbeda tipe (berkas vs folder): dilewati.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (plan != null && !plan.isEmpty) {
                TextButton(onClick = vm::runSync) { Text("Jalankan") }
            } else {
                TextButton(enabled = st.localTree != null && !st.analyzing, onClick = vm::analyzeSync) { Text("Analisis") }
            }
        },
        dismissButton = { TextButton(onClick = vm::closeSync) { Text("Tutup") } },
    )
}

// ======================================================================= daftar situs

@Composable
fun SitesScreen(vm: AppViewModel, sites: List<Site>, snackbar: SnackbarHostState, addingSession: Boolean = false) {
    var toDelete by remember { mutableStateOf<Site?>(null) }
    BackHandler(enabled = addingSession) { vm.cancelAddSession() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (addingSession) "Sesi baru" else "DroidSCP") },
                navigationIcon = {
                    if (addingSession) {
                        IconButton(onClick = vm::cancelAddSession) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali ke sesi") }
                    }
                },
                actions = { IconButton(onClick = vm::showTrustedHosts) { Icon(Icons.Default.VerifiedUser, "Host tepercaya") } },
            )
        },
        floatingActionButton = {
            if (sites.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { vm.editSite(Site()) },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Tambah situs") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (sites.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Dns,
                title = "Belum ada koneksi",
                text = "Tambahkan server SFTP, SCP, FTP, atau FTPS untuk mulai mengelola berkas dari ponsel Anda.",
                modifier = Modifier.padding(pad),
                action = {
                    Button(onClick = { vm.editSite(Site()) }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Tambah situs")
                    }
                },
            )
        } else {
            LazyColumn(
                Modifier.padding(pad),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "hint") {
                    Text(
                        "Ketuk situs untuk menyambung",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(sites, key = { it.id }) { site ->
                    SiteCard(site, onConnect = { vm.connect(site) }, onEdit = { vm.editSite(site) }, onDelete = { toDelete = site })
                }
            }
        }
    }

    toDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Hapus situs?") },
            text = { Text("\"${s.displayName}\" beserta kredensial tersimpannya akan dihapus.") },
            confirmButton = { TextButton(onClick = { vm.deleteSite(s); toDelete = null }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Batal") } },
        )
    }
}

// ======================================================================= form situs

@Composable
fun SiteEditScreen(vm: AppViewModel, initial: Site, snackbar: SnackbarHostState) {
    var site by remember(initial.id) { mutableStateOf(initial) }
    var portText by remember(initial.id) { mutableStateOf(initial.port.toString()) }
    var showSecret by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** Hapus kunci yang baru diimpor jika pengguna batal (kunci lama milik situs tidak disentuh). */
    fun discard() {
        val imported = site.keyFileName
        if (imported != null && imported != initial.keyFileName) vm.repo.keys.delete(imported)
        vm.cancelEdit()
    }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { vm.repo.importKey(uri) } }
                .onSuccess { name ->
                    val prev = site.keyFileName
                    if (prev != null && prev != initial.keyFileName) vm.repo.keys.delete(prev)
                    site = site.copy(keyFileName = name)
                }
                .onFailure { vm.message(it.message ?: "Gagal membaca kunci") }
        }
    }

    BackHandler { discard() }

    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
    val ssh = site.protocol.isSsh
    // FTP/FTPS: pengguna kosong berarti login anonim.
    val valid = site.host.isNotBlank() && (site.username.isNotBlank() || !ssh) && port != null &&
        (!ssh || site.authType == AuthType.PASSWORD || site.keyFileName != null)
    // Alasan tombol Simpan nonaktif, ditampilkan di dekat tombol agar pengguna tahu apa yang kurang.
    val missing = when {
        site.host.isBlank() -> "Isi alamat host"
        port == null -> "Port harus antara 1 dan 65535"
        ssh && site.username.isBlank() -> "Isi nama pengguna"
        ssh && site.authType == AuthType.PRIVATE_KEY && site.keyFileName == null -> "Pilih berkas kunci privat"
        else -> null
    }

    fun save() {
        val old = initial.keyFileName
        if (old != null && old != site.keyFileName) vm.repo.keys.delete(old)
        vm.saveSite(site.copy(host = site.host.trim(), username = site.username.trim(), port = port ?: 22))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial.host.isBlank()) "Situs baru" else "Ubah situs") },
                navigationIcon = { IconButton(onClick = ::discard) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (missing != null) {
                        Text(missing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Button(onClick = ::save, enabled = valid, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Simpan")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Server", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                site.name, { site = site.copy(name = it) }, label = { Text("Nama (opsional)") },
                placeholder = { Text("mis. Server kantor") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    site.host, { site = site.copy(host = it) }, label = { Text("Host") },
                    placeholder = { Text("mis. 192.168.1.10") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    portText, { portText = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                    isError = port == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(112.dp),
                )
            }
            OutlinedTextField(site.username, { site = site.copy(username = it) }, label = { Text(if (ssh) "Pengguna" else "Pengguna (kosong = anonymous)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Protokol transfer", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Protocol.entries.forEach { p ->
                    FilterChip(
                        selected = site.protocol == p,
                        onClick = {
                            // Ikut ganti port hanya bila pengguna belum mengubahnya dari bawaan protokol lama.
                            if (portText == site.protocol.defaultPort(site.implicitTls).toString()) portText = p.defaultPort(site.implicitTls).toString()
                            site = site.copy(protocol = p)
                        },
                        label = { Text(p.name) },
                    )
                }
            }
            Text(
                protocolHint(site.protocol.name, site.implicitTls),
                style = MaterialTheme.typography.bodySmall,
                color = if (site.protocol == Protocol.FTP) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (site.protocol == Protocol.FTPS) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("TLS implisit", style = MaterialTheme.typography.bodyLarge)
                        Text("Server FTPS lama yang langsung TLS di port 990 (tanpa AUTH TLS).", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = site.implicitTls,
                        onCheckedChange = { on ->
                            if (portText == site.protocol.defaultPort(site.implicitTls).toString()) portText = site.protocol.defaultPort(on).toString()
                            site = site.copy(implicitTls = on)
                        },
                    )
                }
            }

            if (ssh) {
                Text("Autentikasi", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = site.authType == AuthType.PASSWORD, onClick = { site = site.copy(authType = AuthType.PASSWORD) }, label = { Text("Password") })
                    FilterChip(selected = site.authType == AuthType.PRIVATE_KEY, onClick = { site = site.copy(authType = AuthType.PRIVATE_KEY) }, label = { Text("Kunci privat") })
                }
            }

            val secretToggle: @Composable () -> Unit = {
                IconButton(onClick = { showSecret = !showSecret }) {
                    Icon(if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Tampilkan")
                }
            }
            val secretTransform = if (showSecret) VisualTransformation.None else PasswordVisualTransformation()

            if (site.authType == AuthType.PASSWORD || !ssh) {
                OutlinedTextField(
                    site.password, { site = site.copy(password = it) }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = secretTransform, trailingIcon = secretToggle,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { keyPicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Lock, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (site.keyFileName == null) "Pilih berkas kunci" else "Ganti kunci")
                    }
                    if (site.keyFileName != null) Text("Kunci terpasang", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    site.keyPassphrase, { site = site.copy(keyPassphrase = it) }, label = { Text("Passphrase (jika ada)") }, singleLine = true,
                    visualTransformation = secretTransform, trailingIcon = secretToggle, modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("Lanjutan", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                site.initialRemoteDir, { site = site.copy(initialRemoteDir = it) }, label = { Text("Folder awal (opsional)") },
                placeholder = { Text("mis. /var/www") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ======================================================================= penjelajah remote

private sealed interface BrowserDialog {
    data object NewFolder : BrowserDialog
    data object Disconnect : BrowserDialog
    data class Rename(val entry: RemoteEntry) : BrowserDialog
    data class Delete(val entries: List<RemoteEntry>) : BrowserDialog
    data class Chmod(val entry: RemoteEntry) : BrowserDialog
    data class Props(val entry: RemoteEntry) : BrowserDialog
    data object Bookmarks : BrowserDialog
}

@Composable
fun BrowserScreen(vm: AppViewModel, state: UiState, snackbar: SnackbarHostState) {
    val jobs by vm.transfers.jobs.collectAsState()
    val activeCount = jobs.count { it.isActive }
    val shown = remember(state.entries, state.showHidden, state.sortKey, state.sortAsc, state.filter) {
        var list = if (state.showHidden) state.entries else state.entries.filterNot { it.name.startsWith(".") }
        val q = state.filter.trim()
        if (q.isNotEmpty()) list = list.filter { it.name.contains(q, ignoreCase = true) }
        val cmp: Comparator<RemoteEntry> = when (state.sortKey) {
            SortKey.NAME -> compareBy<RemoteEntry> { it.name.lowercase() }
            SortKey.SIZE -> compareBy<RemoteEntry> { it.size }
            SortKey.DATE -> compareBy<RemoteEntry> { it.modifiedEpochSec }
        }
        val sorted = list.sortedWith(cmp)
        // Folder selalu di atas (sortedBy stabil, jadi urutan di dalam kelompok tetap).
        (if (state.sortAsc) sorted else sorted.reversed()).sortedByDescending { it.isDirectory }
    }
    val selected = remember(shown, state.selected) { shown.filter { it.path in state.selected } }
    val selecting = selected.isNotEmpty()
    var dialog by remember { mutableStateOf<BrowserDialog?>(null) }
    var menu by remember { mutableStateOf(false) }
    var selMenu by remember { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val nowSec = remember(state.entries) { System.currentTimeMillis() / 1000 }
    val zone = remember { ZoneId.systemDefault() }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.upload(it) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.uploadFolder(it) }
    var uploadMenu by remember { mutableStateOf(false) }

    fun requestDisconnect() {
        if (vm.activeTransfersHere() > 0) dialog = BrowserDialog.Disconnect else vm.disconnect()
    }

    BackHandler {
        when {
            selecting -> vm.clearSelection()
            state.searching -> vm.toggleSearch()
            state.path != "/" -> vm.up()
            else -> requestDisconnect()
        }
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selected.size} dipilih") },
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "Batal pilih") } },
                    actions = {
                        IconButton(onClick = { vm.selectAll(shown) }) { Icon(Icons.Default.SelectAll, "Pilih semua") }
                        IconButton(onClick = { vm.download(selected) }) { Icon(Icons.Default.Download, "Unduh") }
                        if (selected.size == 1) {
                            IconButton(onClick = { dialog = BrowserDialog.Rename(selected[0]) }) { Icon(Icons.Default.Edit, "Ganti nama") }
                        }
                        IconButton(onClick = { vm.cut(selected) }) { Icon(Icons.Default.ContentCut, "Potong") }
                        IconButton(onClick = { dialog = BrowserDialog.Delete(selected) }) { Icon(Icons.Default.Delete, "Hapus") }
                        if (selected.size == 1) {
                            Box {
                                IconButton(onClick = { selMenu = true }) { Icon(Icons.Default.MoreVert, "Lainnya") }
                                DropdownMenu(expanded = selMenu, onDismissRequest = { selMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Properti") },
                                        leadingIcon = { Icon(Icons.Default.Info, null) },
                                        onClick = { selMenu = false; dialog = BrowserDialog.Props(selected[0]) },
                                    )
                                    if (!selected[0].isDirectory) {
                                        DropdownMenuItem(
                                            text = { Text("Buka dengan aplikasi lain") },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                                            onClick = { selMenu = false; vm.openExternal(selected[0]) },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Izin (chmod)") },
                                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                                        onClick = { selMenu = false; dialog = BrowserDialog.Chmod(selected[0]) },
                                    )
                                }
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(state.site?.displayName ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = ::requestDisconnect) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Putuskan") } },
                    actions = {
                        IconButton(onClick = vm::toggleSearch) { Icon(Icons.Default.Search, "Cari") }
                        IconButton(onClick = vm::showTransfers) {
                            BadgedBox(badge = { if (activeCount > 0) Badge { Text("$activeCount") } }) {
                                Icon(Icons.Default.SwapVert, "Transfer")
                            }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Segarkan") }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = { menu = false; vm.refresh() })
                                DropdownMenuItem(
                                    text = { Text(if (state.showHidden) "Sembunyikan berkas tersembunyi" else "Tampilkan berkas tersembunyi") },
                                    onClick = { menu = false; vm.toggleHidden() },
                                )
                                SortKey.entries.forEach { k ->
                                    val label = when (k) { SortKey.NAME -> "Nama"; SortKey.SIZE -> "Ukuran"; SortKey.DATE -> "Tanggal" }
                                    val mark = if (state.sortKey == k) (if (state.sortAsc) " ▲" else " ▼") else ""
                                    DropdownMenuItem(text = { Text("Urut: $label$mark") }, onClick = { menu = false; vm.setSort(k) })
                                }
                                DropdownMenuItem(text = { Text("Bookmark folder…") }, onClick = { menu = false; dialog = BrowserDialog.Bookmarks })
                                DropdownMenuItem(text = { Text("Sinkronkan folder…") }, onClick = { menu = false; vm.openSync() })
                                DropdownMenuItem(text = { Text("Pilih folder unduhan") }, onClick = { menu = false; vm.chooseDownloadDir() })
                                DropdownMenuItem(text = { Text("Sesi baru…") }, onClick = { menu = false; vm.newSession() })
                                DropdownMenuItem(text = { Text(if (state.sessions.size > 1) "Putuskan sesi ini" else "Putuskan koneksi") }, onClick = { menu = false; requestDisconnect() })
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selecting) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { uploadMenu = true },
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text("Tambah") },
                    )
                    DropdownMenu(expanded = uploadMenu, onDismissRequest = { uploadMenu = false }) {
                        DropdownMenuItem(text = { Text("Unggah berkas") }, leadingIcon = { Icon(Icons.Default.Upload, null) }, onClick = { uploadMenu = false; uploadPicker.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text("Unggah folder") }, leadingIcon = { Icon(Icons.Default.Folder, null) }, onClick = { uploadMenu = false; folderPicker.launch(null) })
                        DropdownMenuItem(text = { Text("Folder baru") }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) }, onClick = { uploadMenu = false; dialog = BrowserDialog.NewFolder })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (state.sessions.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.sessions.forEach { t ->
                        FilterChip(
                            selected = t.id == state.activeSession,
                            onClick = { vm.switchSession(t.id) },
                            label = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
            PathBar(state.path, vm::navigate)
            if (state.searching) {
                OutlinedTextField(
                    state.filter, vm::setFilter, singleLine = true, placeholder = { Text("Cari di folder ini") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    trailingIcon = { IconButton(onClick = vm::toggleSearch) { Icon(Icons.Default.Close, "Tutup pencarian") } },
                )
            }
            if (state.clipboard.isNotEmpty()) {
                ListItem(
                    headlineContent = { Text("${state.clipboard.size} item dipotong") },
                    trailingContent = {
                        Row {
                            TextButton(onClick = vm::paste) { Text("Tempel di sini") }
                            TextButton(onClick = vm::clearClipboard) { Text("Batal") }
                        }
                    },
                )
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (state.path != "/") {
                    item(key = "..") {
                        ListItem(
                            modifier = Modifier.clickable { vm.up() },
                            leadingContent = { Icon(Icons.Default.ArrowUpward, null) },
                            headlineContent = { Text("..") },
                            supportingContent = { Text("Naik satu tingkat") },
                        )
                    }
                }
                if (shown.isEmpty() && !state.loading) {
                    item(key = "empty") {
                        val filtering = state.filter.isNotBlank()
                        val onlyHidden = !state.showHidden && state.entries.isNotEmpty() && !filtering
                        EmptyState(
                            icon = if (filtering) Icons.Default.Search else Icons.Default.Folder,
                            title = if (filtering) "Tidak ada hasil" else "Folder ini kosong",
                            text = when {
                                filtering -> "Tidak ada berkas yang cocok dengan \u201C${state.filter.trim()}\u201D."
                                onlyHidden -> "Isinya hanya berkas tersembunyi. Buka menu \u22EE lalu pilih Tampilkan berkas tersembunyi."
                                else -> "Ketuk Tambah untuk mengunggah berkas atau membuat folder."
                            },
                            modifier = Modifier.fillMaxWidth().height(320.dp),
                        )
                    }
                }
                items(shown, key = { it.path }) { e ->
                    val isSel = e.path in state.selected
                    val kind = fileKind(e.name, e.isDirectory, e.isSymlink)
                    val whenText = friendlyDate(e.modifiedEpochSec, nowSec, zone)
                    ListItem(
                        modifier = Modifier.combinedClickable(
                            onClick = { if (selecting) vm.toggleSelect(e) else vm.open(e) },
                            onLongClick = { vm.toggleSelect(e) },
                        ),
                        colors = ListItemDefaults.colors(
                            containerColor = if (isSel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        ),
                        leadingContent = { FileBadge(kind, isSel) },
                        headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            val parts = listOfNotNull(if (e.isDirectory) null else formatSize(e.size), whenText.ifEmpty { null })
                            if (parts.isNotEmpty()) Text(parts.joinToString(" \u00B7 "), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        },
                        trailingContent = when {
                            selecting -> ({ Checkbox(checked = isSel, onCheckedChange = { vm.toggleSelect(e) }) })
                            e.isDirectory -> ({ Icon(Icons.Default.ChevronRight, null) })
                            else -> null
                        },
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        BrowserDialog.NewFolder -> TextInputDialog(
            title = "Folder baru", label = "Nama folder", initial = "", confirm = "Buat",
            onConfirm = { vm.mkdir(it); dialog = null }, onDismiss = { dialog = null },
        )
        is BrowserDialog.Rename -> TextInputDialog(
            title = "Ganti nama", label = "Nama baru", initial = d.entry.name, confirm = "Ganti",
            onConfirm = { vm.rename(d.entry, it); dialog = null }, onDismiss = { dialog = null },
        )
        is BrowserDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Hapus ${d.entries.size} item?") },
            text = {
                Text(
                    d.entries.take(5).joinToString("\n") { it.name } +
                        (if (d.entries.size > 5) "\n…" else "") +
                        (if (d.entries.any { it.isDirectory }) "\n\nIsi folder ikut terhapus dan tidak bisa dipulihkan." else ""),
                )
            },
            confirmButton = { TextButton(onClick = { vm.delete(d.entries); dialog = null }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Batal") } },
        )
        is BrowserDialog.Chmod -> ChmodDialog(
            entry = d.entry,
            onConfirm = { vm.chmod(d.entry, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is BrowserDialog.Props -> PropertiesDialog(d.entry, dateFmt) { dialog = null }
        BrowserDialog.Bookmarks -> BookmarksDialog(vm, state) { dialog = null }
        BrowserDialog.Disconnect -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (state.sessions.size > 1) "Putuskan sesi ini?" else "Putuskan koneksi?") },
            text = { Text("${vm.activeTransfersHere()} transfer di sesi ini masih berjalan dan akan dibatalkan.") },
            confirmButton = { TextButton(onClick = { dialog = null; vm.disconnect() }) { Text("Putuskan") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Tetap terhubung") } },
        )
    }
}

@Composable
private fun PathBar(path: String, onNavigate: (String) -> Unit) {
    val segments = path.split('/').filter { it.isNotEmpty() }
    val scroll = rememberScrollState()
    // Selalu tampilkan ujung kanan (folder aktif) saat jalur panjang.
    LaunchedEffect(path, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onNavigate("/") }) { Icon(Icons.Default.Home, "Folder akar") }
        var acc = ""
        segments.forEachIndexed { i, seg ->
            acc += "/$seg"
            val target = acc
            Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            val last = i == segments.lastIndex
            TextButton(onClick = { onNavigate(target) }) {
                Text(seg, maxLines = 1, style = if (last) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@Composable
private fun ChmodDialog(entry: RemoteEntry, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("%03o".format(entry.permissionMask and 0x1FF)) }
    val mask = text.takeIf { it.length in 3..4 }?.toIntOrNull(8)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Izin: ${entry.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text, { text = it.filter { c -> c in '0'..'7' }.take(4) }, label = { Text("Mode oktal, mis. 755") },
                    singleLine = true, isError = mask == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (mask != null) Text(formatPermissions(mask, entry.isDirectory, false), fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = { TextButton(enabled = mask != null, onClick = { mask?.let(onConfirm) }) { Text("Terapkan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

// ======================================================================= antrean transfer

@Composable
fun TransfersScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val jobs by vm.transfers.jobs.collectAsState()
    BackHandler { vm.closeTransfers() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfer") },
                navigationIcon = { IconButton(onClick = vm::closeTransfers) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } },
                actions = { TextButton(enabled = jobs.any { !it.isActive }, onClick = { vm.transfers.clearFinished() }) { Text("Bersihkan") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (jobs.isEmpty()) {
            EmptyState(
                icon = Icons.Default.SwapVert,
                title = "Belum ada transfer",
                text = "Unggahan, unduhan, dan sinkronisasi Anda akan muncul di sini.",
                modifier = Modifier.padding(pad),
            )
        } else {
            LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(jobs.asReversed(), key = { it.id }) { j -> TransferRow(vm, j) }
            }
        }
    }
}

// ======================================================================= editor teks

@Composable
fun EditorScreen(vm: AppViewModel, state: UiState, editor: EditorState, snackbar: SnackbarHostState) {
    var text by remember(editor.path) { mutableStateOf(editor.text) }
    val dirty = text != editor.text
    var confirmDiscard by remember { mutableStateOf(false) }

    BackHandler { if (dirty) confirmDiscard = true else vm.closeEditor() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(baseName(editor.path) + if (dirty) " •" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { if (dirty) confirmDiscard = true else vm.closeEditor() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali")
                    }
                },
                actions = {
                    IconButton(enabled = dirty && !state.loading, onClick = { vm.saveEditor(text) }) { Icon(Icons.Default.Save, "Simpan") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxSize().padding(8.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Buang perubahan?") },
            text = { Text("Perubahan yang belum disimpan akan hilang.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.closeEditor() }) { Text("Buang") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Lanjut mengedit") } },
        )
    }
}


// ======================================================================= komponen bersama

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector, container: Color, content: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = content)
    }
}

@Composable
private fun FileBadge(kind: FileKind, selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    if (selected) {
        IconBadge(Icons.Default.Check, cs.primary, cs.onPrimary)
        return
    }
    val (icon, bg, fg) = when (kind) {
        FileKind.FOLDER -> Triple(Icons.Default.Folder, cs.primaryContainer, cs.onPrimaryContainer)
        FileKind.IMAGE -> Triple(Icons.Default.Image, cs.tertiaryContainer, cs.onTertiaryContainer)
        FileKind.VIDEO -> Triple(Icons.Default.Movie, cs.tertiaryContainer, cs.onTertiaryContainer)
        FileKind.AUDIO -> Triple(Icons.Default.MusicNote, cs.tertiaryContainer, cs.onTertiaryContainer)
        FileKind.ARCHIVE -> Triple(Icons.Default.FolderZip, cs.secondaryContainer, cs.onSecondaryContainer)
        FileKind.PDF -> Triple(Icons.Default.PictureAsPdf, cs.errorContainer, cs.onErrorContainer)
        FileKind.TEXT -> Triple(Icons.Default.Description, cs.secondaryContainer, cs.onSecondaryContainer)
        FileKind.CODE -> Triple(Icons.Default.Code, cs.secondaryContainer, cs.onSecondaryContainer)
        FileKind.LINK -> Triple(Icons.Default.Link, cs.surfaceVariant, cs.onSurfaceVariant)
        FileKind.OTHER -> Triple(Icons.AutoMirrored.Filled.InsertDriveFile, cs.surfaceVariant, cs.onSurfaceVariant)
    }
    IconBadge(icon, bg, fg)
}

@Composable
private fun SiteCard(site: Site, onConnect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val secure = site.protocol != Protocol.FTP
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                if (secure) Icons.Default.Lock else Icons.Default.LockOpen,
                if (secure) cs.primaryContainer else cs.errorContainer,
                if (secure) cs.onPrimaryContainer else cs.onErrorContainer,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(site.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(siteAddress(site), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(protocolSummary(site), style = MaterialTheme.typography.labelMedium, color = if (secure) cs.primary else cs.error)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opsi untuk ${site.displayName}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Ubah") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Hapus") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun TransferRow(vm: AppViewModel, j: TransferJob) {
    val cs = MaterialTheme.colorScheme
    val (icon, label) = when (j.direction) {
        Direction.UPLOAD -> Icons.Default.Upload to "Unggah"
        Direction.DOWNLOAD -> Icons.Default.Download to "Unduh"
        Direction.SYNC -> Icons.Default.Sync to "Sinkron"
    }
    val pctLabel = percentText(j.done, j.total)
    val frac = if (j.total > 0) (j.done.toFloat() / j.total).coerceIn(0f, 1f) else null
    val (bg, fg) = when (j.state) {
        JobState.FAILED -> cs.errorContainer to cs.onErrorContainer
        JobState.DONE -> cs.primaryContainer to cs.onPrimaryContainer
        else -> cs.secondaryContainer to cs.onSecondaryContainer
    }
    ListItem(
        leadingContent = { IconBadge(if (j.state == JobState.DONE) Icons.Default.Check else icon, bg, fg) },
        headlineContent = { Text(j.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val status = when (j.state) {
                    JobState.QUEUED -> "$label · menunggu giliran"
                    JobState.RUNNING ->
                        if (pctLabel != null) "$label · $pctLabel · ${formatSize(j.done)} dari ${formatSize(j.total)}"
                        else "$label · ${formatSize(j.done)} diproses"
                    JobState.DONE -> "$label selesai" + (j.note?.let { " · $it" } ?: "")
                    JobState.FAILED -> "$label gagal" + (j.error?.let { ". $it" } ?: "")
                    JobState.CANCELLED -> "$label dibatalkan"
                }
                Text(status, maxLines = 3, color = if (j.state == JobState.FAILED) cs.error else Color.Unspecified)
                if (j.state == JobState.RUNNING) {
                    if (frac != null) {
                        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (j.canRetry) Text("Ketuk tombol ulangi untuk mencoba lagi", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        },
        trailingContent = {
            if (j.isActive) IconButton(onClick = { vm.transfers.cancel(j.id) }) { Icon(Icons.Default.Close, "Batalkan") }
            else if (j.canRetry) IconButton(onClick = { vm.transfers.retry(j.id) }) { Icon(Icons.Default.Replay, "Ulangi") }
        },
    )
}
