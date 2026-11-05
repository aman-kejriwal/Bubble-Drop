package dev.bubbledrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Two engines syncing through direct file copies, as two devices would over the network. */
class FolderSyncTest {
    private lateinit var a: Device
    private lateinit var b: Device

    private class Device(val name: String, val fingerprint: String, dir: File) {
        val folder = File(dir, "$name-folder").apply { mkdirs() }
        val engine = FolderSyncEngine(folder, File(dir, "$name-state.json"))

        fun write(path: String, text: String) {
            File(folder, path).apply { parentFile?.mkdirs(); writeText(text) }
            // Old enough to count as settled.
            File(folder, path).setLastModified(System.currentTimeMillis() - 60_000 - text.hashCode().toLong().mod(1000))
        }

        fun read(path: String) = File(folder, path).takeIf { it.isFile }?.readText()

        fun files() = folder.walkTopDown().filter { it.isFile && !it.path.contains("/.") }
            .map { it.relativeTo(folder).path }.sorted().toList()

        /** One round pulling from [other]; returns the number of changes made here. */
        fun syncFrom(other: Device): Int {
            engine.scan()
            val remote = other.engine.index()
            var changes = 0
            for (action in engine.plan(remote.entries, fingerprint, other.fingerprint)) {
                var downloaded: File? = null
                if (action is SyncAction.Pull) {
                    downloaded = engine.partialFile()
                    other.engine.file(action.path)!!.copyTo(downloaded)
                }
                engine.apply(action, downloaded, name)
                changes++
            }
            engine.scan()
            return changes
        }
    }

    /** Rounds both ways until nothing changes. */
    private fun settle() {
        repeat(5) {
            if (a.syncFrom(b) + b.syncFrom(a) == 0) return
        }
        error("sync didn't settle")
    }

    @Before
    fun setUp() {
        val dir = Files.createTempDirectory("sync").toFile()
        // "a" has the smaller fingerprint, so it keeps the name in a conflict.
        a = Device("A", "aaaa", dir)
        b = Device("B", "bbbb", dir)
    }

    @Test
    fun firstSyncIsAUnion() {
        a.write("one.txt", "1")
        a.write("sub/two.txt", "2")
        b.write("three.txt", "3")
        settle()
        assertEquals(listOf("one.txt", "sub/two.txt", "three.txt").sorted(), a.files())
        assertEquals(a.files(), b.files())
    }

    @Test
    fun editsAndDeletesPropagate() {
        a.write("doc.txt", "v1")
        a.write("gone.txt", "x")
        settle()
        b.write("doc.txt", "v2 longer")
        File(a.folder, "gone.txt").delete()
        settle()
        assertEquals("v2 longer", a.read("doc.txt"))
        assertEquals(listOf("doc.txt"), a.files())
        assertEquals(listOf("doc.txt"), b.files())
        // Deleted files go to the trash, not away.
        assertTrue(File(b.folder, FolderSyncEngine.TRASH).walkTopDown().any { it.name == "gone.txt" })
    }

    @Test
    fun conflictingEditsKeepBoth() {
        a.write("note.txt", "base")
        settle()
        a.write("note.txt", "from A")
        b.write("note.txt", "from B!")
        settle()
        assertEquals("from A", a.read("note.txt"))
        assertEquals("from A", b.read("note.txt"))
        val copy = b.files().single { it.startsWith("note (conflict from B") }
        assertEquals("from B!", b.read(copy))
        assertEquals("from B!", a.read(copy))
    }

    @Test
    fun editBeatsDelete() {
        a.write("keep.txt", "v1")
        settle()
        File(a.folder, "keep.txt").delete()
        b.write("keep.txt", "v2 edited")
        settle()
        assertEquals("v2 edited", a.read("keep.txt"))
        assertEquals("v2 edited", b.read("keep.txt"))
    }

    @Test
    fun unsafePathsAreRejected() {
        assertFalse(SyncPaths.isValid("../escape.txt"))
        assertFalse(SyncPaths.isValid("/etc/passwd"))
        assertFalse(SyncPaths.isValid("a//b"))
        assertFalse(SyncPaths.isValid("a/./b"))
        assertFalse(SyncPaths.isValid(".hidden"))
        assertFalse(SyncPaths.isValid("dir\\file"))
        assertTrue(SyncPaths.isValid("Projects/plan v2.txt"))
    }
}
