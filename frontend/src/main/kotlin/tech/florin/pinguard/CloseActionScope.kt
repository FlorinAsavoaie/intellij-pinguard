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
 * `CloseAllEditors`. It extends `AnAction` directly rather than
 * `CloseEditorsActionBase`, so there is nothing to interrogate and its two
 * branches are mirrored here from its own implementation.
 */
internal val AllTabsScope = CloseActionScope { event ->
    val window = event.getData(EditorWindow.DATA_KEY)
    if (window != null) {
        window.fileList.map { CloseTarget(it, window) }
    } else {
        projectWideTargets(event) { _, siblings -> siblings.toList() }
    }
}

/**
 * `CloseAllEditorsButActive` — Close Others — on the one branch of it that can
 * reach a pinned tab.
 *
 * The action has two, and only the second needs guarding:
 *
 *  - With an editor window in the event — the tab's context menu, or anything
 *    invoked with the focus in the editor — it closes through
 *    `EditorWindow.closeAllExcept`, which skips pinned files itself. A pinned tab
 *    was never among the tabs that close would take, so this names no targets and
 *    the action runs exactly as the IDE ships it.
 *  - With no editor window — **Window | Editor Tabs | Close Others** while the
 *    focus is in a tool window, or the same action from Find Action — it closes
 *    each sibling through `FileEditorManagerEx.closeFile`, which has no pin check
 *    at all. That is the branch guarded here.
 *
 * The empty list is not a null: to [GuardedCloseAction] both mean "run the
 * original untouched", but "this close was never going to touch a pin" is a
 * decision, where null is the absence of one.
 */
internal val OtherTabsScope = CloseActionScope { event ->
    if (event.getData(EditorWindow.DATA_KEY) != null) {
        emptyList()
    } else {
        // Keyed on the selected file rather than on the event's VIRTUAL_FILE,
        // which is what the platform's own project-wide branch does.
        projectWideTargets(event) { selected, siblings -> siblings.filterNot { it == selected } }
    }
}
