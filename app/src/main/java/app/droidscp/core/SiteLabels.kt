package app.droidscp.core

/** "user@host" dengan ":port" hanya bila bukan port bawaan protokolnya. */
fun siteAddress(site: Site): String {
    val user = if (site.username.isBlank()) "" else "${site.username.trim()}@"
    val port = if (site.port == site.protocol.defaultPort(site.implicitTls)) "" else ":${site.port}"
    return user + site.host.trim() + port
}

/** Ringkasan keamanan satu baris untuk kartu situs. */
fun protocolSummary(site: Site): String = when (site.protocol) {
    Protocol.SFTP -> "SFTP · terenkripsi (SSH)"
    Protocol.SCP -> "SCP · terenkripsi (SSH)"
    Protocol.FTPS -> if (site.implicitTls) "FTPS · terenkripsi (TLS implisit)" else "FTPS · terenkripsi (TLS)"
    Protocol.FTP -> "FTP · tidak terenkripsi"
}
