package app.droidscp.core

interface KnownHostsStore {
    fun get(host: String, port: Int): String?
    fun put(host: String, port: Int, fingerprint: String)

    /** Semua host tepercaya, kunci "host:port". Bawaan kosong agar implementasi lama tetap valid. */
    fun all(): Map<String, String> = emptyMap()

    fun remove(host: String, port: Int) {}
}

/** Pecah kunci "host:port" (host IPv6 memuat ":", jadi port diambil dari ":" terakhir). */
fun splitHostKey(key: String): Pair<String, Int>? {
    val i = key.lastIndexOf(':')
    if (i <= 0) return null
    val port = key.substring(i + 1).toIntOrNull() ?: return null
    return key.substring(0, i) to port
}

/** Host belum pernah dikenal: UI harus meminta konfirmasi pengguna (trust on first use). */
class HostKeyUnknownException(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val keyType: String,
) : Exception("Host $host:$port belum dikenal")

/** Fingerprint berbeda dari yang tersimpan: kemungkinan serangan man-in-the-middle. */
class HostKeyChangedException(
    val host: String,
    val port: Int,
    val oldFingerprint: String,
    val newFingerprint: String,
) : Exception("Kunci host $host:$port berubah")
