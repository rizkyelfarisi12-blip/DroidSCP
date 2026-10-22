package app.droidscp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {
    private fun file(rel: String, size: Long = 10, mtime: Long = 1000) = SyncNode(rel, false, size, mtime)
    private fun dir(rel: String) = SyncNode(rel, true, 0, 0)
    private fun tree(vararg n: SyncNode): Map<String, SyncNode> = n.associateBy { it.rel }

    private fun plan(
        remote: Map<String, SyncNode>,
        local: Map<String, SyncNode>,
        dir: SyncDirection = SyncDirection.REMOTE_TO_LOCAL,
        delete: Boolean = false,
    ) = SyncPlanner.plan(dir, remote, local, delete)

    @Test
    fun newFileAtSourceIsCopied() {
        val p = plan(tree(file("a.txt")), tree())
        assertEquals(listOf("a.txt"), p.items.map { it.rel })
        assertEquals(SyncOp.COPY, p.items[0].op)
        assertEquals(1, p.copies)
        assertEquals(10L, p.bytesToCopy)
    }

    @Test
    fun identicalTreesNeedNothing() {
        val p = plan(tree(dir("d"), file("d/a")), tree(dir("d"), file("d/a")))
        assertTrue(p.isEmpty)
    }

    @Test
    fun sourceNewerBeyondToleranceIsUpdated() {
        val p = plan(tree(file("a", mtime = 1003)), tree(file("a", mtime = 1000)))
        assertEquals(listOf(SyncOp.UPDATE), p.items.map { it.op })
        assertEquals(1, p.updates)
    }

    @Test
    fun differenceWithinToleranceIsIgnored() {
        // Beda tepat 2 detik (presisi waktu antar sisi berbeda) tidak boleh memicu salinan sia-sia.
        val p = plan(tree(file("a", mtime = 1002)), tree(file("a", mtime = 1000)))
        assertTrue(p.isEmpty)
    }

    @Test
    fun targetNewerWithDifferentSizeIsNotOverwritten() {
        val p = plan(tree(file("a", size = 10, mtime = 1000)), tree(file("a", size = 99, mtime = 5000)))
        assertTrue(p.isEmpty)
        assertEquals(1, p.newerAtTarget)
    }

    @Test
    fun targetNewerWithSameSizeIsSilentlyFine() {
        val p = plan(tree(file("a", size = 10, mtime = 1000)), tree(file("a", size = 10, mtime = 5000)))
        assertTrue(p.isEmpty)
        assertEquals(0, p.newerAtTarget)
    }

    @Test
    fun fileVersusDirectoryIsSkippedAndCounted() {
        val p = plan(tree(dir("x")), tree(file("x")))
        assertTrue(p.isEmpty)
        assertEquals(1, p.typeMismatch)
    }

    @Test
    fun parentsComeBeforeChildren() {
        val p = plan(tree(file("a/b/c.txt"), dir("a/b"), dir("a")), tree())
        assertEquals(listOf("a", "a/b", "a/b/c.txt"), p.items.map { it.rel })
        assertEquals(2, p.newDirs)
        assertEquals(1, p.copies)
    }

    @Test
    fun extrasAreKeptUnlessDeleteRequested() {
        val local = tree(file("extra"))
        assertTrue(plan(tree(), local, delete = false).isEmpty)
        val p = plan(tree(), local, delete = true)
        assertEquals(listOf(SyncOp.DELETE), p.items.map { it.op })
        assertEquals(1, p.deletes)
    }

    @Test
    fun deleteRemovesChildrenBeforeTheirFolder() {
        val p = plan(tree(), tree(dir("d"), file("d/x"), dir("d/e"), file("d/e/y")), delete = true)
        val order = p.items.map { it.rel }
        assertEquals(4, order.size)
        fun before(a: String, b: String) = assertTrue("$a harus sebelum $b: $order", order.indexOf(a) < order.indexOf(b))
        before("d/e/y", "d/e")
        before("d/e", "d")
        before("d/x", "d")
    }

    @Test
    fun deleteDoesNotTouchDestinationItemsThatExistAtSource() {
        val p = plan(tree(file("keep")), tree(file("keep"), file("gone")), delete = true)
        assertEquals(listOf("gone"), p.items.map { it.rel })
    }

    @Test
    fun directionSwapsSourceAndTarget() {
        val remote = tree(file("onlyRemote"))
        val local = tree(file("onlyLocal"))
        val down = plan(remote, local, SyncDirection.REMOTE_TO_LOCAL)
        val up = plan(remote, local, SyncDirection.LOCAL_TO_REMOTE)
        assertEquals(listOf("onlyRemote"), down.items.map { it.rel })
        assertEquals(listOf("onlyLocal"), up.items.map { it.rel })
    }

    @Test
    fun bytesToCopyIgnoresDirectoriesAndDeletes() {
        val p = plan(tree(dir("d"), file("d/a", size = 7), file("b", size = 5)), tree(file("zzz", size = 1000)), delete = true)
        assertEquals(12L, p.bytesToCopy)
    }
}
