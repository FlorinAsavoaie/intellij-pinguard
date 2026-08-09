package tech.florin.pinguard

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile

private val LOG: Logger = Logger.getInstance(PlatformPinnedFiles::class.java)

/** Bridges [PinnedFileSelector] to the running IDE. */
internal object PlatformPinnedFiles {

    /** One editor window, labelled with the project it belongs to. */
    private class Window(val where: String, val window: EditorWindow)

    fun selector(): PinnedFileSelector = PinnedFileSelector { targets ->
        if (targets.isEmpty()) {
            // Short-circuited before touching the IDE: a close with nothing in it
            // must not walk every project's editor windows.
            emptyList()
        } else {
            // Once per close rather than once per target, or a "Close All" repeats
            // the same walk for every tab in it.
            val everyWindow = everyOpenWindow()

            targets.mapNotNull { target ->
                val pinnedIn = pinnedIn(target, everyWindow)
                if (pinnedIn.isEmpty()) null else PinnedFile(target.file, pinnedIn)
            }
        }
    }

    /**
     * Where [target] is pinned in a way that this particular close would disturb.
     *
     * A target naming a window is closing that split's tab and nothing else, so
     * only that window has a say. A target with no window closes the file across
     * every split, so every window gets a say and the strictest of them wins.
     */
    private fun pinnedIn(target: CloseTarget, everyWindow: List<Window>): List<String> {
        // Matched by identity rather than by asking the window for its project:
        // `EditorWindow.getManager()` is `@ApiStatus.Internal`, and the project is
        // wanted only for the log line. A window belonging to no open project — one
        // disposed mid-close — matches nothing and reads as "not pinned".
        val windows = target.window
            ?.let { aimedAt -> listOfNotNull(everyWindow.firstOrNull { it.window === aimedAt }) }
            ?: everyWindow

        return windows
            .filter { isPinned(it.where, it.window, target.file) }
            .map { it.where }
            .distinct()
    }

    /**
     * One entry per open editor window, across every open project, each labelled
     * with the project it belongs to.
     *
     * Every project, because a close can reach us with no project context at all.
     */
    private fun everyOpenWindow(): List<Window> =
        ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .flatMap { project -> windowsOf(project) }

    /**
     * The editor windows of one project, or none if it went away while we asked.
     *
     * Scoped to one project so that a project disposed between the
     * [Project.isDisposed] test above and the service lookup here cannot unprotect
     * the pins of every *other* open project.
     */
    private fun windowsOf(project: Project): List<Window> =
        try {
            FileEditorManagerEx.getInstanceExIfCreated(project)?.windows.orEmpty()
                .map { window -> Window(project.name, window) }
        } catch (failure: Throwable) {
            rethrowIfUnrecoverable(failure)
            LOG.warn("could not list the editor windows of project '${project.name}'; its pins are ignored", failure)
            emptyList()
        }

    private fun isPinned(where: String, window: EditorWindow, file: VirtualFile): Boolean {
        // isFilePinned throws when the file is not open in that window, which is
        // the normal case for splits, so check openness first.
        val pinned = window.isFileOpen(file) && window.isFilePinned(file)
        LOG.debug { "$where: '${file.name}' pinned=$pinned" }
        return pinned
    }
}
