package app.droidscp.core

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Ubah exception teknis menjadi kalimat yang bisa dipahami pengguna awam beserta saran tindakannya.
 * Rantai penyebab ditelusuri dari luar ke dalam; kecocokan pertama menang. Exception yang tidak dikenal
 * dikembalikan apa adanya (pesan aslinya sudah berbahasa Indonesia bila berasal dari kode aplikasi ini).
 */
fun friendlyError(e: Throwable): String {
    var t: Throwable? = e
    var depth = 0
    while (t != null && depth++ < 6) {
        classify(t)?.let { return it }
        t = t.cause
    }
    val raw = e.message?.trim().orEmpty()
    return if (raw.isNotEmpty()) raw else e.javaClass.simpleName
}

private fun classify(t: Throwable): String? {
    val msg = (t.message ?: "").lowercase()
    val name = t.javaClass.simpleName
    return when {
        t is UnknownHostException ->
            "Alamat server tidak ditemukan. Periksa penulisan alamat dan koneksi internet Anda."
        t is ConnectException || "connection refused" in msg ->
            "Server menolak koneksi. Periksa nomor port dan pastikan layanannya sedang aktif."
        t is SocketTimeoutException || "timed out" in msg || "timeout" in msg ->
            "Server tidak menjawab (waktu habis). Periksa alamat, port, dan kekuatan sinyal Anda."
        t is NoRouteToHostException || "network is unreachable" in msg || "no route to host" in msg ->
            "Server tidak terjangkau. Periksa koneksi internet Anda."
        name == "UserAuthException" || msg.startsWith("login gagal") ||
            "exhausted available authentication" in msg || "auth fail" in msg || "authentication failed" in msg ->
            "Login ditolak. Periksa nama pengguna dan password atau kunci Anda."
        t is SSLException || "certificate" in msg || "sertifikat" in msg && t !is HostKeyChangedException && t !is HostKeyUnknownException ->
            "Koneksi aman gagal karena sertifikat server bermasalah."
        "permission denied" in msg || "access denied" in msg || "not permitted" in msg ->
            "Akses ditolak. Anda tidak punya izin untuk melakukan ini di server."
        "no such file" in msg || "nosuchfile" in msg || "not found" in msg && "host" !in msg ->
            "Berkas atau folder tidak ditemukan di server."
        "no space left" in msg || "quota" in msg || "disk full" in msg ->
            "Ruang penyimpanan penuh. Kosongkan ruang lalu coba lagi."
        "connection reset" in msg || "broken pipe" in msg || "connection abort" in msg ||
            "sebelum berkas diterima" in msg || "koneksi scp terputus" in msg || name == "EOFException" || "end of stream" in msg ->
            "Koneksi terputus di tengah jalan. Periksa sinyal Anda lalu coba lagi."
        else -> null
    }
}
