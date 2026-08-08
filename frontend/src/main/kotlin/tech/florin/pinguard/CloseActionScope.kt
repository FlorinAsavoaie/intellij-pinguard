package tech.florin.pinguard

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.vfs.VirtualFile

/**
 * One tab a close action is about to close.
 *
 * [window] is the split holding the tab, or null when the action closes the file
 * across every split at once. Keeping the distinction lets a narrowed close
 * reproduce the original's targeting exactly.
 */
internal data class CloseTarget(
    val file: VirtualFile,
    val window: EditorWindow?,
)

/**
 * Works out which tabs one of the platform's close actions is about to close.
 *
 * Returning null means "cannot tell", and the guard then stands aside and lets the
 * action run untouched. Guessing would be worse than not guarding: a wrong guess
 * either strands tabs the user asked to close or silently drops a pin.
 */
internal fun interface CloseActionScope {
    fun targets(event: AnActionEvent): List<CloseTarget>?
}

/**
 * `CloseContent` — the single tab the user aimed at, whether by Cmd+W, the tab's
 * context menu, or the keyboard.
 */
internal val SingleTabScope = CloseActionScope { event ->
    // CloseAction also serves tool windows and run consoles, which have no
    // virtual file. Those must reach the original untouched.
    event.getData(CommonDataKeys.VIRTUAL_FILE)?.let {
        listOf(CloseTarget(it, event.getData(EditorWindow.DATA_KEY)))
    }
}

/**
 * The two shapes every Close All variant has: one split's tabs when the event
 * names a window, and the selected file's siblings across the project when it does
 * not. Each caller supplies only what it keeps.
 *
 * @param inWindow given the window and every tab in it, the tabs to close.
 * @param projectWide given the selected file and its siblings, the tabs to close.
 */
private fun scopedTargets(
    event: AnActionEvent,
    inWindow: (EditorWindow, List<VirtualFile>) -> List<VirtualFile>,
    projectWide: (selected: VirtualFile, siblings: Collection<VirtualFile>) -> List<VirtualFile>,
): List<CloseTarget>? {
    val window = event.getData(EditorWindow.DATA_KEY)
    if (window != null) return inWindow(window, window.fileList).map { CloseTarget(it, window) }

    val project = event.project ?: return null
    val manager = FileEditorManagerEx.getInstanceEx(project)
    val selected = manager.selectedFiles.firstOrNull() ?: return null
    return projectWide(selected, manager.getSiblings(selected)).map { CloseTarget(it, null) }
}

/**
 * `CloseAllEditors`. It extends `AnAction` directly rather than
 * `CloseEditorsActionBase`, so there is nothing to interrogate and its two
 * branches are mirrored here from its own implementation.
 */
internal val AllTabsScope = CloseActionScope { event ->
    scopedTargets(
        event,
        inWindow = { _, tabs -> tabs },
        projectWide = { _, siblings -> siblings.toList() },
    )
}

/**
 * `CloseAllEditorsButActive`, mirrored from its implementation the same way as
 * [AllTabsScope] and for the same reason.
 */
internal val OtherTabsScope = CloseActionScope { event ->
    val keep = event.getData(CommonDataKeys.VIRTUAL_FILE)

    scopedTargets(
        event,
        // Pinned tabs are dropped here rather than left for the gate to catch,
        // because `EditorWindow.closeAllExcept` — which is what this action
        // runs — skips them itself. Counting them would raise a confirmation
        // about tabs that were never going to close.
        //
        // `isFilePinned` is safe on this list: every file in it is by
        // definition open in that window.
        inWindow = { window, tabs -> tabs.filterNot { it == keep || window.isFilePinned(it) } },
        // The project-wide branch keeps the selected file rather than `keep`,
        // which is what the platform's own implementation does.
        projectWide = { selected, siblings -> siblings.filterNot { it == selected } },
    )
}
