package app.droidscp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteLabelsTest {
    @Test
    fun addressHidesDefaultPortAndEmptyUser() {
        assertEquals("root@srv.com", siteAddress(Site(host = "srv.com", username = "root", port = 22)))
        assertEquals("root@srv.com:2222", siteAddress(Site(host = " srv.com ", username = " root ", port = 2222)))
        assertEquals("ftp.x.org", siteAddress(Site(host = "ftp.x.org", username = "", protocol = Protocol.FTP, port = 21)))
        assertEquals("u@h", siteAddress(Site(host = "h", username = "u", protocol = Protocol.FTPS, implicitTls = true, port = 990)))
        assertEquals("u@h:21", siteAddress(Site(host = "h", username = "u", protocol = Protocol.FTPS, implicitTls = true, port = 21)))
    }

    @Test
    fun summaryStatesEncryptionHonestly() {
        assertTrue(protocolSummary(Site(protocol = Protocol.FTP)).contains("tidak terenkripsi"))
        assertTrue(protocolSummary(Site(protocol = Protocol.FTPS, implicitTls = true)).contains("implisit"))
        assertTrue(protocolSummary(Site(protocol = Protocol.SFTP)).contains("SSH"))
        assertTrue(!protocolSummary(Site(protocol = Protocol.FTPS)).contains("implisit"))
    }
}
