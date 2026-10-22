package app.droidscp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val NOW = 1_700_000_000L // 2023-11-14T22:13:20Z

private fun ok(text: String) = ExecResult(0, text.toByteArray())
private fun fail(text: String, code: Int = 1) = ExecResult(code, ByteArray(0), text.toByteArray())

/** Runner palsu: mencatat perintah dan menjawab lewat [answer]. */
private class Script(val answer: (String, ByteArray?) -> ExecResult) : ShellRunner {
    val log = mutableListOf<String>()
    var lastStdin: ByteArray? = null
    override fun run(command: String, stdin: ByteArray?): ExecResult {
        log += command; lastStdin = stdin
        return answer(command, stdin)
    }
}

class ShellFsTest {
    @Test
    fun quoting() {
        assertEquals("'a b'", shellQuote("a b"))
        assertEquals("'it'\\''s'", shellQuote("it's"))
        assertEquals("'\$(rm -rf /)'", shellQuote("\$(rm -rf /)")) // tidak ada ekspansi
        assertEquals("\"\$HOME\"", shellPathArg("~"))
        assertEquals("\"\$HOME\"/'docs/a b'", shellPathArg("~/docs/a b"))
        assertEquals("'~user/x'", shellPathArg("~user/x"))
    }

    @Test
    fun parsesGnuEpochLines() {
        val e = parseLsLine("drwxr-xr-x 2 root root 4096 1700000000 My Docs", "/srv", NOW)!!
        assertEquals("My Docs", e.name)
        assertEquals("/srv/My Docs", e.path)
        assertTrue(e.isDirectory)
        assertFalse(e.isSymlink)
        assertEquals(1_700_000_000L, e.modifiedEpochSec)
        assertEquals(493, e.permissionMask) // 0755
        val f = parseLsLine("-rw-r--r-- 1 u g 1234567890123 1600000000 big.iso", "/", NOW)!!
        assertEquals(1_234_567_890_123L, f.size)
        assertEquals("/big.iso", f.path)
        assertEquals(420, f.permissionMask) // 0644
    }

    @Test
    fun parsesSpecialBitsDevicesAndAcl() {
        val s = parseLsLine("-rwsr-xr-x 1 root root 100 1700000000 su", "/bin", NOW)!!
        assertEquals(493, s.permissionMask)
        val cap = parseLsLine("-rwSr--r-- 1 root root 100 1700000000 x", "/", NOW)!!
        assertEquals(0b110_100_100, cap.permissionMask) // 'S' = setuid tanpa eksekusi
        val dev = parseLsLine("crw-rw-rw- 1 root root 1, 3 1700000000 null", "/dev", NOW)!!
        assertEquals(3L, dev.size)
        assertEquals("null", dev.name)
        val acl = parseLsLine("-rw-r--r--+ 1 u g 5 1700000000 a.txt", "/", NOW)!!
        assertEquals("a.txt", acl.name)
        val sel = parseLsLine("-rw-r--r--. 1 u g 5 1700000000 b.txt", "/", NOW)!!
        assertEquals("b.txt", sel.name)
    }

    @Test
    fun parsesSymlinkTargetOut() {
        val l = parseLsLine("lrwxrwxrwx 1 u g 7 1700000000 latest -> releases/v2", "/app", NOW)!!
        assertTrue(l.isSymlink)
        assertEquals("latest", l.name)
        assertEquals("/app/latest", l.path)
    }

    @Test
    fun parsesBusyboxStyleDates() {
        val withTime = parseLsLine("-rw-r--r--    1 root     root          1234 Nov  1 12:30 log.txt", "/", NOW)!!
        assertEquals(1_698_841_800L, withTime.modifiedEpochSec) // 2023-11-01T12:30Z
        val withYear = parseLsLine("-rw-r--r--    1 root     root          1234 Jan  5  2021 old.txt", "/", NOW)!!
        assertEquals(1_609_804_800L, withYear.modifiedEpochSec) // 2021-01-05T00:00Z
        // jam tanpa tahun yang jatuh di "masa depan" berarti tahun lalu
        val future = parseLsLine("-rw-r--r--    1 root     root          1 Dec 25 10:00 x", "/", NOW)!!
        assertEquals(1_703_498_400L - 365 * 86_400L, future.modifiedEpochSec)
    }

    @Test
    fun ignoresNonEntries() {
        assertNull(parseLsLine("total 48", "/", NOW))
        assertNull(parseLsLine("", "/", NOW))
        assertNull(parseLsLine("ls: cannot access 'x': No such file or directory", "/", NOW))
        assertNull(parseLsLine("drwxr-xr-x 2 root root 4096 1700000000 .", "/", NOW))
        assertNull(parseLsLine("drwxr-xr-x 2 root root 4096 1700000000 ..", "/", NOW))
        // CRLF dari server aneh tidak ikut ke nama
        assertEquals("a", parseLsLine("-rw-r--r-- 1 u g 1 1700000000 a\r", "/", NOW)!!.name)
    }

    @Test
    fun listSortsDirsFirstAndResolvesLinkTargets() {
        val sh = Script { cmd, _ ->
            when {
                cmd.startsWith("LC_ALL=C ls") -> ok(
                    """
                    total 12
                    -rw-r--r-- 1 u g 10 1700000000 b.txt
                    drwxr-xr-x 2 u g 4096 1700000000 zdir
                    lrwxrwxrwx 1 u g 4 1700000000 toDir -> zdir
                    lrwxrwxrwx 1 u g 5 1700000000 toFile -> b.txt
                    -rw-r--r-- 1 u g 1 1700000000 A.txt
                    """.trimIndent(),
                )
                cmd.startsWith("cd ") -> ok("D\nF\n")
                else -> fail("tak dikenal")
            }
        }
        val list = ShellOps(sh) { NOW }.list("/srv/")
        assertEquals(listOf("toDir", "zdir", "A.txt", "b.txt", "toFile"), list.map { it.name })
        assertTrue(list.first { it.name == "toDir" }.isDirectory)
        assertFalse(list.first { it.name == "toFile" }.isDirectory)
        assertTrue(sh.log[0].contains("--time-style=+%s"))
        assertTrue(sh.log[0].endsWith("-- '/srv/'"))
        assertTrue(sh.log[1].contains("'toDir' 'toFile'"))
    }

    @Test
    fun fallsBackWhenTimeStyleUnsupportedAndRemembersIt() {
        val sh = Script { cmd, _ ->
            when {
                cmd.contains("--time-style") -> fail("ls: unrecognized option '--time-style=+%s'", 1)
                else -> ok("-rw-r--r--    1 root     root          5 Nov  1 12:30 a\n")
            }
        }
        val ops = ShellOps(sh) { NOW }
        assertEquals(1, ops.list("/x").size)
        assertEquals(1, ops.list("/x").size)
        assertEquals(3, sh.log.size) // 1 percobaan GNU + 2 daftar; percobaan kedua tidak mengulang GNU
        assertFalse(sh.log.last().contains("--time-style"))
    }

    @Test
    fun genuineListErrorIsReportedNotMistakenForUnsupported() {
        val sh = Script { _, _ -> fail("ls: cannot open directory '/root/': Permission denied", 2) }
        val e = assertThrows(IOException::class.java) { ShellOps(sh) { NOW }.list("/root") }
        assertTrue(e.message!!.contains("Permission denied"))
        assertEquals(1, sh.log.size)
    }

    @Test
    fun statMissingIsNullOtherErrorsThrow() {
        val missing = Script { _, _ -> fail("ls: cannot access '/nope': No such file or directory", 2) }
        assertNull(ShellOps(missing) { NOW }.stat("/nope"))
        assertFalse(ShellOps(missing) { NOW }.exists("/nope"))
        val denied = Script { _, _ -> fail("ls: cannot access '/x/y': Permission denied", 2) }
        assertThrows(IOException::class.java) { ShellOps(denied) { NOW }.stat("/x/y") }
        val okFile = Script { _, _ -> ok("-rw-r--r-- 1 u g 42 1700000000 /srv/data.bin\n") }
        val st = ShellOps(okFile) { NOW }.stat("/srv/data.bin")!!
        assertEquals("data.bin", st.name)
        assertEquals("/srv/data.bin", st.path)
        assertEquals(42L, st.size)
        assertTrue(ShellOps(okFile) { NOW }.stat("/")!!.isDirectory) // akar tidak perlu perintah
    }

    @Test
    fun mutatingCommandsAreQuotedAndPrecise() {
        val sh = Script { _, _ -> ok("") }
        val ops = ShellOps(sh) { NOW }
        ops.mkdir("/a/it's here")
        ops.mkdirs("/a/b/c")
        ops.rename("/a/x y", "/a/z")
        ops.chmod("/a/f", 0b111_101_101)
        ops.chmod("/a/g", 0x8000 or 0b110_100_100) // bit tipe dibuang
        val dir = RemoteEntry("d", "/a/d", true, false, 0, 0, 493)
        val link = RemoteEntry("l", "/a/l", true, true, 0, 0, 511)
        val file = RemoteEntry("f", "/a/f", false, false, 1, 0, 420)
        ops.delete(dir); ops.delete(link); ops.delete(file)
        assertEquals(
            listOf(
                "mkdir -- '/a/it'\\''s here'",
                "mkdir -p -- '/a/b/c'",
                "mv -- '/a/x y' '/a/z'",
                "chmod 755 '/a/f'",
                "chmod 644 '/a/g'",
                "rm -rf -- '/a/d'",
                "rm -f -- '/a/l'", // tautan ke folder tidak boleh dihapus rekursif
                "rm -f -- '/a/f'",
            ),
            sh.log,
        )
    }

    @Test
    fun failuresCarryServerMessage() {
        val sh = Script { _, _ -> fail("mkdir: cannot create directory '/a': File exists", 1) }
        val e = assertThrows(IOException::class.java) { ShellOps(sh) { NOW }.mkdir("/a") }
        assertTrue(e.message!!.startsWith("Buat folder gagal"))
        assertTrue(e.message!!.contains("File exists"))
    }

    @Test
    fun readWriteBytes() {
        val sh = Script { cmd, _ ->
            when {
                cmd.startsWith("LC_ALL=C ls") -> ok("-rw-r--r-- 1 u g 5 1700000000 /t.txt\n")
                cmd.startsWith("cat ") -> ExecResult(0, "halo\n".toByteArray())
                else -> ok("")
            }
        }
        val ops = ShellOps(sh) { NOW }
        assertEquals("halo\n", String(ops.readBytes("/t.txt", 1024)))
        assertThrows(IOException::class.java) { ops.readBytes("/t.txt", 3) } // terlalu besar, tanpa menjalankan cat
        assertEquals(1, sh.log.count { it.startsWith("cat ") })
        ops.writeBytes("/o'k.txt", "isi".toByteArray())
        assertEquals("cat > '/o'\\''k.txt'", sh.log.last())
        assertEquals("isi", String(sh.lastStdin!!))
    }

    @Test
    fun canonicalAndHome() {
        val sh = Script { cmd, _ ->
            when {
                cmd == "pwd" -> ok("/home/u\n")
                cmd.contains("'/real'") -> ok("/real\n")
                cmd.contains("\"\$HOME\"") -> ok("/home/u\n")
                else -> fail("sh: cd: nope: No such file or directory")
            }
        }
        val ops = ShellOps(sh) { NOW }
        assertEquals("/home/u", ops.home())
        assertEquals("/real", ops.canonical("/real"))
        assertEquals("/home/u", ops.canonical("~"))
        assertEquals("/not/there", ops.canonical("/not//there/")) // mutlak: dinormalkan saja
        assertThrows(IOException::class.java) { ops.canonical("relatif") }
    }
}

class ImplicitTlsModelTest {
    @Test
    fun implicitFtpsUsesPort990Only() {
        assertEquals(990, Protocol.FTPS.defaultPort(true))
        assertEquals(21, Protocol.FTPS.defaultPort(false))
        assertEquals(22, Protocol.SFTP.defaultPort(true))
        assertEquals(21, Protocol.FTP.defaultPort(true))
        assertFalse(Site().implicitTls)
    }
}
