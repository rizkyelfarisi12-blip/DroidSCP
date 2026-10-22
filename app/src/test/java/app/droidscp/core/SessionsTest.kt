package app.droidscp.core
import org.junit.Assert.assertEquals
import org.junit.Test
class SessionsTest {
    @Test fun uniqueTitleNumbersDuplicatesAndFillsGaps() {
        assertEquals("Server", uniqueTitle(emptyList(), "Server"))
        assertEquals("Server #2", uniqueTitle(listOf("Server"), "Server"))
        assertEquals("Server #3", uniqueTitle(listOf("Server", "Server #2"), "Server"))
        assertEquals("Server #2", uniqueTitle(listOf("Server", "Server #3"), "Server")) // celah diisi
        assertEquals("Sesi", uniqueTitle(emptyList(), "   "))
        assertEquals("Sesi #2", uniqueTitle(listOf("Sesi"), ""))
        assertEquals("a", uniqueTitle(listOf("b"), " a "))
    }
}
