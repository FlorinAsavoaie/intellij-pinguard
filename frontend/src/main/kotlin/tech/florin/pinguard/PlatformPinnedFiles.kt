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
            // Short-circuited before touching the IDE at all: a close with nothing
            // in it must not walk every project's editor windows.
            emptyList()
        } else {
            // Once per close rather than once per target: a "Close All" would
            // otherwise repeat the same walk for every tab in it.
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
     * only that window has a say: the same file pinned in another split, or in
     * another project, was never in this close's way, and refusing on account of it
     * is a close that silently does nothing. A target with no window closes the
     * file across every split, so there every window gets a say and the strictest
     * of them wins.
     *
     * The aimed-at window is picked out of the already-labelled set by identity
     * rather than asked which project it belongs to: `EditorWindow.getManager()` is
     * `@ApiStatus.Internal`, and its project is wanted only for the log line. A
     * window that belongs to no open project — one disposed mid-close — matches
     * nothing and reads as "not pinned", which is the same fail-open answer every
     * other lookup here gives.
     */
    private fun pinnedIn(target: CloseTarget, everyWindow: List<Window>): List<String> {
        // An EditorWindow belongs to exactly one project's manager, so the
        // aimed-at case matches at most once.
        val windows = target.window
            ?.let { aimedAt -> listOfNotNull(everyWindow.firstOrNull { it.window === aimedAt }) }
            ?: everyWindow

        return windows
            .filter { isPinned(it.where, it.window, target.file) }
            .map { it.where }
            .distinct()
    }

    /**
     * One entry per open editor window, across every open project.
     *
     * A close can reach us without any project context, so we consult them all.
     * Each carries its project's name so the log can say which window vetoed.
     */
    private fun everyOpenWindow(): List<Window> =
        ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .flatMap { project -> windowsOf(project) }

    /**
     * The editor windows of one project, or none if it went away while we asked.
     *
     * Scoped to one project on purpose: a project disposed between the
     * [Project.isDisposed] test above and the service lookup here throws, and that
     * must not unprotect the pins of every *other* open project — which is exactly
     * the situation PinGuard exists for.
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
