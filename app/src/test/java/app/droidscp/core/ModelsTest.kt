package app.droidscp.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    @Test
    fun parentPath() {
        assertEquals("/", parentPath("/"))
        assertEquals("/", parentPath(""))
        assertEquals("/", parentPath("/a"))
        assertEquals("/a", parentPath("/a/b"))
        assertEquals("/a", parentPath("/a/b/"))
    }

    @Test
    fun joinPath() {
        assertEquals("/x", joinPath("/", "x"))
        assertEquals("/a/x", joinPath("/a", "x"))
        assertEquals("/a/x", joinPath("/a/", "x"))
    }

    @Test
    fun baseName() {
        assertEquals("b.txt", baseName("/a/b.txt"))
        assertEquals("b", baseName("/a/b/"))
        assertEquals("a", baseName("a"))
    }

    @Test
    fun normalizePathResolvesDotSegments() {
        assertEquals("/a/c", normalizePath("/a/./b/../c"))
        assertEquals("/", normalizePath("/../.."))
        assertEquals("/a/b", normalizePath("a/b"))
        assertEquals("/a/b", normalizePath("//a//b/"))
        assertEquals("/", normalizePath(""))
    }

    @Test
    fun normalizePathNeverEscapesRoot() {
        // Path dari server tidak dipercaya: ".." tidak boleh keluar dari akar.
        assertEquals("/etc/passwd", normalizePath("/../../etc/passwd"))
    }

    @Test
    fun formatPermissions() {
        assertEquals("drwxr-xr-x", formatPermissions(0b111_101_101, isDir = true, isLink = false))
        assertEquals("-rw-r--r--", formatPermissions(0b110_100_100, isDir = false, isLink = false))
        assertEquals("lrwxrwxrwx", formatPermissions(0b111_111_111, isDir = false, isLink = true))
        assertEquals("----------", formatPermissions(0, isDir = false, isLink = false))
    }

    @Test
    fun formatSize() {
        assertEquals("0 B", formatSize(0))
        assertEquals("1023 B", formatSize(1023))
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("1.5 KB", formatSize(1536))
        assertEquals("1.0 MB", formatSize(1024L * 1024))
        assertEquals("2.0 GB", formatSize(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun protocolDefaults() {
        assertEquals(22, Protocol.SFTP.defaultPort)
        assertEquals(22, Protocol.SCP.defaultPort)
        assertEquals(21, Protocol.FTP.defaultPort)
        assertEquals(21, Protocol.FTPS.defaultPort)
        assertEquals(listOf(true, true, false, false), Protocol.entries.map { it.isSsh })
    }
}
