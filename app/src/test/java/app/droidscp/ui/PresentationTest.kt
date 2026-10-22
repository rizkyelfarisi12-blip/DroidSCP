package app.droidscp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PresentationTest {
    private val wib = ZoneId.of("Asia/Jakarta")
    private fun ts(y: Int, m: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, m, d, h, mi, 0, 0, wib).toEpochSecond()
    private val now = ts(2026, 10, 21, 0, 30)

    @Test fun kinds() {
        assertEquals(FileKind.FOLDER, fileKind("src", true, false))
        assertEquals(FileKind.LINK, fileKind("latest", false, true))
        assertEquals(FileKind.IMAGE, fileKind("Foto.JPG", false, false))
        assertEquals(FileKind.VIDEO, fileKind("a.mkv", false, false))
        assertEquals(FileKind.AUDIO, fileKind("a.flac", false, false))
        assertEquals(FileKind.ARCHIVE, fileKind("backup.tar.gz", false, false))
        assertEquals(FileKind.PDF, fileKind("laporan.pdf", false, false))
        assertEquals(FileKind.CODE, fileKind("main.kt", false, false))
        assertEquals(FileKind.TEXT, fileKind("catatan.txt", false, false))
        assertEquals(FileKind.OTHER, fileKind("Makefile", false, false))
        assertEquals(FileKind.OTHER, fileKind(".bashrc", false, false)) // berkas titik, bukan ekstensi
        assertEquals(FileKind.OTHER, fileKind("x.zzz", false, false))
        assertEquals(FileKind.FOLDER, fileKind("data.zip", true, false)) // folder menang atas ekstensi
    }

    @Test fun dates() {
        assertEquals("Hari ini 00:05", friendlyDate(ts(2026, 10, 21, 0, 5), now, wib))
        assertEquals("Kemarin 23:59", friendlyDate(ts(2026, 10, 20, 23, 59), now, wib))
        assertEquals("3 Mei", friendlyDate(ts(2026, 5, 3, 10, 0), now, wib))
        assertEquals("31 Des 2025", friendlyDate(ts(2025, 12, 31, 10, 0), now, wib))
        assertEquals("", friendlyDate(0, now, wib))
    }

    @Test fun percent() {
        assertEquals("0%", percentText(0, 100))
        assertEquals("42%", percentText(42, 100))
        assertEquals("100%", percentText(150, 100)) // tidak lebih dari 100
        assertNull(percentText(5, -1))
        assertNull(percentText(5, 0))
    }

    @Test fun hints() {
        assert(protocolHint("SFTP").contains("aman"))
        assert(protocolHint("FTP").contains("Tidak terenkripsi"))
        assert(protocolHint("FTPS", true).contains("990"))
        assertEquals("", protocolHint("???"))
    }
}
