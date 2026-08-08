package tech.florin.pinguard

import com.intellij.ide.lightEdit.LightEditCompatible
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.DumbAware

private val LOG: Logger = Logger.getInstance(GuardedCloseAction::class.java)

/**
 * Wraps one of the platform's close actions and keeps pinned tabs out of it.
 *
 * A refusal narrows the action to its unpinned tabs rather than cancelling it: a
 * "Close All" that silently did nothing because one tab was pinned would be a
 * worse bug than the one this plugin fixes. The presentation is forwarded
 * untouched, so a guarded action is never disabled or relabelled.
 *
 * Guarding at the action layer is what makes the plugin work at all — the
 * platform's own veto hook is consulted from `CloseEditor` alone, which has no
 * default shortcut and appears in no menu — and it also makes the guard
 * structurally incapable of interfering with project or IDE shutdown, neither of
 * which dispatches actions. Because this class lives in the plugin's frontend
 * module, the `ActionManager` it displaces an action in belongs to whichever
 * process owns the editor tabs — a monolithic IDE, or the JetBrains Client in a
 * remote development session — so the close runs where the tabs are, as it always
 * did.
 *
 * Three known limits, all of them to keep the Plugin Verifier clean:
 *  - This extends [AnAction] and forwards by hand rather than extending
 *    `AnActionWrapper`, whose constructor calls `AnAction.copyFrom` and so copies
 *    the delegate's shortcut set; `ActionManager.replaceAction` then assigns the
 *    action id's own, and `AnAction.setShortcutSet` logs a `PluginException`
 *    against this plugin for that second assignment. Only the shortcut set was
 *    ever the problem, so the presentation is copied by hand below.
 *  - Per-place text overrides are not carried across. They live on the action
 *    rather than on its presentation, and every way to move them —
 *    `applyTextOverride`, which the verifier reports as internal, or
 *    `copyActionTextOverrides`, which is protected and copies the wrong way round
 *    — costs more than it buys. The visible effect is confined to `CloseContent`:
 *    the tab's context menu reads "Close Tab" where the stock IDE says "Close".
 *  - The wrapped actions all carry `ActionRemoteBehaviorSpecification.Frontend`,
 *    which is `@ApiStatus.Internal` and cannot be reproduced here, so a wrapped
 *    action loses the marker and `getActionBehavior` answers null rather than
 *    `Frontend` for it.
 */
internal open class GuardedCloseAction(
    private val original: AnAction,
    private val scope: CloseActionScope,
    private val selector: PinnedFileSelector,
    private val gate: PinnedCloseGate,
) : AnAction(), DumbAware {

    init {
        // Menus, Settings | Keymap and Find Action all render an action from its
        // template presentation, and `replaceAction` carries none of it across, so
        // without this the whole close family loses its labels — neither action's
        // `update` sets its own text.
        templatePresentation.copyFrom(original.templatePresentation)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = original.actionUpdateThread

    /** Forwarded wholesale, presentation included. */
    override fun update(e: AnActionEvent) {
        original.update(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val narrowed = try {
            narrow(e)
        } catch (failure: Throwable) {
            rethrowIfUnrecoverable(failure)
            LOG.warn("could not evaluate pins for '${original.javaClass.simpleName}'; closing as usual", failure)
            null
        }

        if (narrowed == null) {
            original.actionPerformed(e)
            return
        }
        closeExactly(e, narrowed)
    }

    /**
     * @return the tabs to close instead of running the original, or null to let
     *   the original run untouched.
     */
    private fun narrow(event: AnActionEvent): List<CloseTarget>? {
        val targets = scope.targets(event)?.takeIf { it.isNotEmpty() } ?: return null

        val pinned = selector.pinnedAmong(targets)
        if (pinned.isEmpty()) return null

        if (gate.canClose(pinned)) return null

        val pinnedFiles = pinned.map { it.file }.toSet()
        return targets.filterNot { it.file in pinnedFiles }
    }

    /**
     * Closes exactly [targets], reproducing the original's targeting: per split
     * when the event named one, else project-wide.
     *
     * Inside one command, as `CloseAllEditorsAction` and `CloseEditorsActionBase`
     * both do. Closing a tab starts a command of its own, so without an outer one
     * a narrowed "Close All" is a run of N separate commands where the action it
     * replaced was a single named one — a difference command listeners and
     * `IdeDocumentHistory` grouping both see.
     */
    private fun closeExactly(event: AnActionEvent, targets: List<CloseTarget>) {
        val project = event.project
        val manager = project?.let { FileEditorManagerEx.getInstanceEx(it) }

        CommandProcessor.getInstance().executeCommand(
            project,
            {
                targets.forEach { target ->
                    val window = target.window
                    if (window != null) window.closeFile(target.file) else manager?.closeFile(target.file)
                }
            },
            templatePresentation.text,
            null,
        )
    }
}

/**
 * The variant for `CloseContent`, whose original is [LightEditCompatible].
 *
 * The marker is carried per-action rather than put on [GuardedCloseAction]: adding
 * it to the Close All family would newly admit those actions to LightEdit mode,
 * which is a behaviour change this plugin has no business making.
 */
internal class GuardedLightEditCloseAction(
    original: AnAction,
    scope: CloseActionScope,
    selector: PinnedFileSelector,
    gate: PinnedCloseGate,
) : GuardedCloseAction(original, scope, selector, gate), LightEditCompatible
