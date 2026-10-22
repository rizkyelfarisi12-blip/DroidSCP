package app.droidscp.core

/** Sumber isi kunci privat menurut nama berkas internal (diimplementasikan oleh KeyVault di Android). */
fun interface KeySource {
    /** Isi kunci. Pemanggil sebaiknya menimpa array dengan nol setelah dipakai. */
    fun load(name: String): ByteArray
}

/**
 * Pemeriksaan kasar bahwa [text] adalah kunci *privat*: PEM/OpenSSH/PKCS#8 (termasuk terenkripsi) atau PuTTY .ppk.
 * Menolak kunci publik ("ssh-ed25519 AAAA…", "BEGIN PUBLIC KEY") dan berkas acak, supaya salah pilih berkas
 * ketahuan saat impor, bukan sebagai kegagalan login yang membingungkan.
 */
fun looksLikePrivateKey(text: String): Boolean {
    val t = text.trimStart('﻿', ' ', '\t', '\r', '\n')
    if (t.startsWith("PuTTY-User-Key-File-")) return true
    val begin = t.indexOf("-----BEGIN ")
    if (begin < 0) return false
    val headerEnd = t.indexOf("-----", begin + "-----BEGIN ".length)
    if (headerEnd < 0) return false
    return t.substring(begin + "-----BEGIN ".length, headerEnd).endsWith("PRIVATE KEY")
}
