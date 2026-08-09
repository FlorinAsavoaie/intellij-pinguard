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
 * across every split at once.
 */
internal data class CloseTarget(
    val file: VirtualFile,
    val window: EditorWindow?,
)

/**
 * Works out which tabs one of the platform's close actions is about to close.
 *
 * Returning null means "cannot tell", and the guard then stands aside and lets the
 * action run untouched.
 */
internal fun interface CloseActionScope {
    fun targets(event: AnActionEvent): List<CloseTarget>?
}

/**
 * `CloseContent` — the single tab the user aimed at, whether by Cmd+W, the tab's
 * context menu, or the keyboard.
 *
 * Names nothing unless the event carries an editor window. `CloseContent` also
 * serves tool windows and run consoles — Cmd+W in the Project view hides that tool
 * window — and those events carry a file of their own, the one selected in the
 * tree, which may itself be pinned. The window tells the two gestures apart.
 */
internal val SingleTabScope = CloseActionScope { event ->
    val window = event.getData(EditorWindow.DATA_KEY)
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    if (window == null || file == null) null else listOf(CloseTarget(file, window))
}

/**
 * The branch every Close All variant falls to when the event names no window: the
 * selected file's siblings across the project, filtered by what the action keeps.
 *
 * @param keeping given the selected file and its siblings, the tabs to close.
 */
private fun projectWideTargets(
    event: AnActionEvent,
    keeping: (selected: VirtualFile, siblings: Collection<VirtualFile>) -> List<VirtualFile>,
): List<CloseTarget>? {
    val project = event.project ?: return null
    val manager = FileEditorManagerEx.getInstanceEx(project)
    val selected = manager.selectedFiles.firstOrNull() ?: return null
    return keeping(selected, manager.getSiblings(selected)).map { CloseTarget(it, null) }
}

/**
 * `CloseAllEditors`. Its two branches are mirrored from the platform's own
 * implementation, which extends `AnAction` directly and so offers nothing to
 * interrogate.
 */
internal val AllTabsScope = CloseActionScope { event ->
    val window = event.getData(EditorWindow.DATA_KEY)
    if (window != null) {
        window.fileList.map { CloseTarget(it, window) }
    } else {
        projectWideTargets(event) { _, siblings -> siblings.toList() }
    }
}

/** `CloseAllEditorsButActive` — Close Others. */
internal val OtherTabsScope = CloseActionScope { event ->
    if (event.getData(EditorWindow.DATA_KEY) != null) {
        // With a window the action closes through `EditorWindow.closeAllExcept`,
        // which skips pinned files itself; without one it closes each sibling
        // through `FileEditorManagerEx.closeFile`, which has no pin check at all.
        // Only the second branch can reach a pin, so only it is guarded.
        emptyList()
    } else {
        // Keyed on the selected file rather than on the event's VIRTUAL_FILE,
        // which is what the platform's own project-wide branch does.
        projectWideTargets(event) { selected, siblings -> siblings.filterNot { it == selected } }
    }
}
