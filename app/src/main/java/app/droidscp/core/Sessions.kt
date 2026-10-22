package app.droidscp.core

/**
 * Judul tab sesi yang unik: bila [base] sudah dipakai, tambahkan nomor terkecil yang masih kosong
 * ("Server", "Server #2", "Server #3"). Judul kosong diganti "Sesi".
 */
fun uniqueTitle(taken: Collection<String>, base: String): String {
    val b = base.trim().ifEmpty { "Sesi" }
    if (b !in taken) return b
    var n = 2
    while ("$b #$n" in taken) n++
    return "$b #$n"
}
