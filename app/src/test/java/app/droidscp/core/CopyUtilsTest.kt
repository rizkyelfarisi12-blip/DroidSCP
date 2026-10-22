package app.droidscp.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.Random

class CopyUtilsTest {
    private fun data(n: Int) = ByteArray(n).also { Random(42).nextBytes(it) }

    @Test
    fun copyExactCopiesOnlyRequestedLength() {
        val src = data(200_000)
        val input = ByteArrayInputStream(src)
        val out = ByteArrayOutputStream()
        var reported = 0L
        copyExact(input, out, 150_000, { reported += it }, {})
        assertArrayEquals(src.copyOfRange(0, 150_000), out.toByteArray())
        assertEquals(150_000L, reported)
        assertEquals(50_000, input.available()) // sisanya tidak ikut terbaca
    }

    @Test
    fun copyExactZeroLengthDoesNothing() {
        val out = ByteArrayOutputStream()
        copyExact(ByteArrayInputStream(data(10)), out, 0, { error("tidak boleh dipanggil") }, {})
        assertEquals(0, out.size())
    }

    @Test
    fun copyExactFailsWhenSourceEndsEarly() {
        assertThrows(EOFException::class.java) {
            copyExact(ByteArrayInputStream(data(10)), ByteArrayOutputStream(), 11, {}, {})
        }
    }

    @Test
    fun copyExactCanBeCancelledViaCheck() {
        val out = ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java) {
            copyExact(ByteArrayInputStream(data(1000)), out, 1000, {}, { throw IllegalStateException("batal") })
        }
        assertEquals(0, out.size())
    }

    @Test
    fun copyUntilEofCopiesEverything() {
        val src = data(300_001)
        val out = ByteArrayOutputStream()
        var reported = 0L
        copyUntilEof(ByteArrayInputStream(src), out, { reported += it }, {})
        assertArrayEquals(src, out.toByteArray())
        assertEquals(src.size.toLong(), reported)
    }

    @Test
    fun skipFullyWorksEvenIfSkipReturnsZero() {
        val src = data(30_000)
        val input = object : ByteArrayInputStream(src) {
            override fun skip(n: Long): Long = 0 // sebagian stream (mis. SAF) memang begini
        }
        skipFully(input, 20_000)
        val rest = input.readBytes()
        assertArrayEquals(src.copyOfRange(20_000, 30_000), rest)
    }

    @Test
    fun skipFullyFailsWhenSourceShorterThanOffset() {
        assertThrows(IOException::class.java) { skipFully(ByteArrayInputStream(data(5)), 6) }
        assertFalse(ByteArrayInputStream(data(5)).also { skipFully(it, 5) }.read() >= 0)
    }
}
