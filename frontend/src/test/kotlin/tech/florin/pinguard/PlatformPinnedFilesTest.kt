package tech.florin.pinguard

import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.vfs.VirtualFile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The bridge from [PinnedFileSelector] to the running IDE.
 *
 * Two things here can only be answered by a real editor: that a pin really is a
 * property of one split rather than of the file, and that
 * `EditorWindow.isFilePinned` really does throw for a file the window does not
 * hold — an undocumented behaviour that the `isFileOpen(file) &&` guard in
 * [PlatformPinnedFiles] exists solely to work around. What the selector's answer
 * becomes once the gate has it is [PinnedFileSelectorTest]'s job.
 *
 * Not covered, and deliberately so: the `getInstanceExIfCreated` null branch, for a
 * project whose editors have never been touched. This fixture installs an editor
 * manager into its project by hand, so it cannot produce that state, and a test
 * that merely asserts the branch was not taken proves nothing about it.
 */
internal class PlatformPinnedFilesTest : RealEditorTestCase() {

    /** A close that names no window, so every window of every project has a say. */
    private fun pinnedAmong(vararg files: VirtualFile) =
        runInEdtAndGetValue {
            PlatformPinnedFiles.selector().pinnedAmong(files.map { CloseTarget(it, window = null) })
        }

    /** A close aimed at one split, which is the only window entitled to an opinion. */
    private fun pinnedIn(window: EditorWindow, vararg files: VirtualFile) =
        runInEdtAndGetValue {
            PlatformPinnedFiles.selector().pinnedAmong(files.map { CloseTarget(it, window) })
        }

    /**
     * Two windows, and a file open only in the first of them.
     *
     * A split carries every already-open file into both sides, so the file has
     * to be opened afterwards and into one window by name.
     */
    private fun splitWithFileInFirstWindowOnly(): Pair<List<EditorWindow>, VirtualFile> {
        open("Anchor.kt")
        val windows = split()
        assertEquals(2, windows.size, "the editor did not actually split; the rest of this proves nothing")

        val absent = openIn(windows[0], "Absent.kt")
        assertTrue(runInEdtAndGetValue { !windows[1].isFileOpen(absent) }, "the second window holds it after all")

        return windows to absent
    }

    @Test
    fun `isFilePinned still throws for a file the window does not hold`() {
        // The whole reason PlatformPinnedFiles checks openness first. The day it
        // stops throwing, that guard becomes dead code and this test is the only
        // thing that will say so.
        val (windows, absent) = splitWithFileInFirstWindowOnly()

        val thrown = runCatching { runInEdtAndGetValue { windows[1].isFilePinned(absent) } }.exceptionOrNull()

        assertNotNull(thrown, "isFilePinned no longer throws for an unopened file; the isFileOpen guard is now dead")
    }

    @Test
    fun `the selector survives that, because it asks whether the file is open first`() {
        val (_, absent) = splitWithFileInFirstWindowOnly()

        assertEquals(emptyList(), pinnedAmong(absent))
    }

    @Test
    fun `a pin in one split still counts when another split does not hold the file at all`() {
        val (windows, absent) = splitWithFileInFirstWindowOnly()

        pin(absent, windows[0])

        assertEquals(
            listOf(absent),
            pinnedAmong(absent).map { it.file },
            "a window that cannot answer must not be able to veto a window that can",
        )
    }

    @Test
    fun `a file pinned in one split is reported even though the other split has it unpinned`() {
        val file = open("Main.kt")
        val windows = split()
        val holders = windows.filter { window -> runInEdtAndGetValue { window.isFileOpen(file) } }
        assertEquals(2, holders.size, "the split should have carried the open file into both windows")

        pin(file, holders.first())

        assertEquals(listOf(file), pinnedAmong(file).map { it.file })
    }

    @Test
    fun `a close aimed at one split ignores a pin held only by the other`() {
        // Counting a pin the close leaves alone anyway is what turns Cmd+W into a
        // keystroke that silently does nothing.
        val file = open("Main.kt")
        val windows = split()
        val holders = windows.filter { runInEdtAndGetValue { it.isFileOpen(file) } }
        assertEquals(2, holders.size, "the split should have carried the open file into both windows")
        pin(file, holders.first())

        assertEquals(
            emptyList(),
            pinnedIn(holders.last(), file),
            "a pin in the split the user is not closing must not count against this one",
        )
    }

    @Test
    fun `a close aimed at one split still respects that split's own pin`() {
        val file = open("Main.kt")
        val windows = split()
        val holders = windows.filter { runInEdtAndGetValue { it.isFileOpen(file) } }
        pin(file, holders.first())

        assertEquals(
            listOf(file),
            pinnedIn(holders.first(), file).map { it.file },
            "the window the close is aimed at is exactly the one whose pin matters",
        )
    }

    @Test
    fun `a close naming no window is vetoed by a pin in any split`() {
        // With no window the file closes everywhere, so every pin really is in the
        // way.
        val file = open("Main.kt")
        val windows = split()
        val holders = windows.filter { runInEdtAndGetValue { it.isFileOpen(file) } }
        pin(file, holders.last())

        assertEquals(listOf(file), pinnedAmong(file).map { it.file })
    }

    @Test
    fun `a file pinned in both splits of one project names that project once`() {
        val file = open("Main.kt")
        val windows = split()
        windows.filter { window -> runInEdtAndGetValue { window.isFileOpen(file) } }.forEach { pin(file, it) }

        val pinned = pinnedAmong(file).single()

        assertEquals(listOf(project.name), pinned.pinnedIn, "one project must not be listed twice")
    }

    @Test
    fun `the pin is reported against the project that holds it`() {
        val file = open("Main.kt")
        pin(file)

        assertEquals(listOf(project.name), pinnedAmong(file).single().pinnedIn)
    }

    @Test
    fun `only the pinned subset of a multi-file close is reported, in input order`() {
        val a = open("A.kt")
        val b = open("B.kt")
        val c = open("C.kt")
        pin(b)
        pin(c)

        assertEquals(listOf(b, c), pinnedAmong(a, b, c).map { it.file })
    }

    @Test
    fun `a close with nothing in it never walks the editor windows at all`() {
        pin(open("Main.kt"))

        assertEquals(emptyList(), pinnedAmong())
    }
}
