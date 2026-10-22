package app.droidscp.data

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.droidscp.core.KnownHostsStore
import app.droidscp.core.Site
import app.droidscp.core.looksLikePrivateKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

/** Daftar situs tersimpan; seluruh isinya (termasuk password) dienkripsi lewat Android Keystore. */
class SiteRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Site.serializer())

    private val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "sites_encrypted",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Kunci privat, terenkripsi saat disimpan. */
    val keys = KeyVault(context, masterKey)

    private val _sites = MutableStateFlow(load())
    val sites: StateFlow<List<Site>> = _sites.asStateFlow()

    init {
        migrateKeys()
    }

    /** Enkripsi kunci polos dari versi lama dan perbarui rujukannya pada situs. */
    private fun migrateKeys() {
        var changed = false
        val migrated = _sites.value.map { site ->
            val name = site.keyFileName
            val renamed = if (name != null) runCatching { keys.migrate(name) }.getOrNull() else null
            if (renamed != null) {
                changed = true
                site.copy(keyFileName = renamed)
            } else {
                site
            }
        }
        if (changed) {
            persist(migrated)
            _sites.value = migrated
        }
    }

    private fun load(): List<Site> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun persist(list: List<Site>) {
        prefs.edit().putString(KEY, json.encodeToString(serializer, list)).apply()
    }

    @Synchronized
    fun save(site: Site) {
        _sites.update { list ->
            val idx = list.indexOfFirst { it.id == site.id }
            val next = if (idx >= 0) list.toMutableList().also { it[idx] = site } else list + site
            persist(next)
            next
        }
    }

    @Synchronized
    fun delete(site: Site) {
        site.keyFileName?.let { keys.delete(it) }
        _sites.update { list ->
            val next = list.filterNot { it.id == site.id }
            persist(next)
            next
        }
    }

    /**
     * Impor kunci privat pilihan pengguna: validasi bentuknya, lalu simpan terenkripsi di [keys].
     * Mengembalikan nama internal yang disimpan pada [Site.keyFileName].
     */
    fun importKey(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Tidak bisa membuka berkas kunci")
        // Kunci privat wajar berukuran kecil; batasi agar berkas acak tidak memenuhi penyimpanan.
        val bytes = input.use { src ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = src.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > MAX_KEY_BYTES) throw IOException("Berkas kunci terlalu besar")
            }
            buf.toByteArray()
        }
        try {
            if (!looksLikePrivateKey(String(bytes, Charsets.UTF_8))) {
                throw IOException("Berkas ini tidak tampak seperti kunci privat (butuh format PEM/OpenSSH atau PuTTY .ppk, bukan kunci publik)")
            }
            return keys.store(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private companion object {
        const val KEY = "sites_json"
        const val MAX_KEY_BYTES = 64 * 1024
    }
}

/** Fingerprint host tepercaya, kunci "host:port". Fingerprint bukan rahasia, cukup SharedPreferences biasa. */
class PrefsKnownHosts(context: Context) : KnownHostsStore {
    private val prefs = context.getSharedPreferences("known_hosts", Context.MODE_PRIVATE)

    override fun get(host: String, port: Int): String? = prefs.getString("$host:$port", null)

    override fun put(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString("$host:$port", fingerprint).apply()
    }

    override fun all(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    override fun remove(host: String, port: Int) {
        prefs.edit().remove("$host:$port").apply()
    }
}
