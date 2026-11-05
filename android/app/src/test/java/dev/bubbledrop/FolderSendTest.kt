package dev.bubbledrop

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderSendTest {
    private fun offer(name: String, path: String? = null, size: Long = 1) = FileOffer("id-$name-$path", name, size, "text/plain", path)

    @Test
    fun foldersComeFromSafePathsOnly() {
        assertEquals(listOf("Photos", "2024"), offer("a.jpg", "Photos/2024/a.jpg").folders)
        assertEquals(emptyList<String>(), offer("a.jpg").folders)
        // Unsafe paths fall back to a loose file in Downloads/BubbleDrop.
        assertEquals(emptyList<String>(), offer("x", "../../etc/x").folders)
        assertEquals(emptyList<String>(), offer("x", "/abs/x").folders)
        assertEquals(emptyList<String>(), offer("x", "a//x").folders)
        // Folder names are sanitized like file names.
        assertEquals(listOf("hidden", "a_b"), offer("x", ".hidden/a:b/x").folders)
    }

    @Test
    fun offersAreSummarizedByTopLevelItem() {
        val folder = listOf(offer("a", "Photos/a", 2), offer("b", "Photos/2024/b", 3))
        assertEquals("Photos (2 files)", describeOffer(offerItems(folder)))
        assertEquals("2 items", describeOffer(offerItems(folder + offer("loose.txt"))))
        assertEquals("loose.txt", describeOffer(offerItems(listOf(offer("loose.txt")))))
        assertEquals("2 files", describeOffer(offerItems(listOf(offer("a"), offer("b")))))
        assertEquals(OfferItem("Photos", true, 2, 5), offerItems(folder).single())
    }

    @Test
    fun emptyFoldersAreOfferedAndShown() {
        val sender = DeviceInfo("id", "Mac", "Mac14,2", "macos")
        val onlyEmpty = AskRequest(sender, emptyList(), listOf("Proj/empty", "Proj/deep/a/b", "../evil"))
        // Unsafe paths are dropped; the rest become folder names, outermost first.
        assertEquals(listOf(listOf("Proj", "empty"), listOf("Proj", "deep", "a", "b")), onlyEmpty.emptyFolderComponents)
        assertEquals("Proj (empty)", onlyEmpty.summary())
        val withFile = AskRequest(sender, listOf(offer("r.txt", "Proj/docs/r.txt")), listOf("Proj/empty"))
        assertEquals("Proj (1 file)", withFile.summary())
    }
}
