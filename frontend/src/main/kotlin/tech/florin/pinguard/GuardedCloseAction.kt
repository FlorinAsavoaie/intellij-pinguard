package tech.florin.pinguard

import com.intellij.ide.lightEdit.LightEditCompatible
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.DumbAware

private val LOG: Logger = Logger.getInstance(GuardedCloseAction::class.java)

/**
 * Wraps one of the platform's close actions and keeps pinned tabs out of it.
 *
 * A refusal narrows the action to its unpinned tabs rather than cancelling it, so
 * a guarded "Close All" still closes everything that is not pinned. The
 * presentation is forwarded untouched, so a guarded action is never disabled or
 * relabelled.
 *
 * The original runs only where PinGuard has said nothing to the user: once a
 * confirmation dialog has been up, the close is performed here instead.
 *
 * Two ways a guarded action differs from the one it displaced, both deliberate:
 *  - Per-place text overrides are not carried across, because every way to move
 *    them is internal or protected platform API. The visible effect is confined to
 *    `CloseContent`, whose tab context menu reads "Close Tab" where the stock IDE
 *    says "Close".
 *  - `ActionRemoteBehaviorSpecification.Frontend` is `@ApiStatus.Internal` and
 *    cannot be reproduced here, so `getActionBehavior` answers null for a wrapped
 *    action rather than `Frontend`.
 */
internal open class GuardedCloseAction(
    private val original: AnAction,
    private val scope: CloseActionScope,
    private val selector: PinnedFileSelector,
    private val gate: PinnedCloseGate,
) : AnAction(), DumbAware {

    init {
        // Menus, Settings | Keymap and Find Action all render an action from its
        // template presentation and `replaceAction` carries none of it across, so
        // without this the close family loses its labels — no wrapped action's
        // `update` sets its own text.
        //
        // Copied by hand rather than by extending `AnActionWrapper`, which would
        // also copy the delegate's shortcut set. The wrapper has to reach
        // `replaceAction` with an empty one, or `AnAction.setShortcutSet` logs a
        // `PluginException` against this plugin; see
        // `PinGuardActionGuards.surrenderShortcutSet` for the same dance on the way
        // back out.
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

        return when (askAbout(pinned)) {
            CloseOutcome.UNOPPOSED -> null

            // Closed here rather than handed back, because the platform's close
            // actions re-read the editor when they run instead of closing what their
            // event says. `CloseContent` keeps the tab a context menu was on only
            // until the first turn of the event queue after the menu comes down, and
            // getting a yes took a modal dialog, which pumps that queue.
            CloseOutcome.CONFIRMED -> targets

            CloseOutcome.REFUSED -> {
                val pinnedFiles = pinned.map { it.file }.toSet()
                targets.filterNot { it.file in pinnedFiles }
            }
        }
    }

    /**
     * The gate's answer, with a failure to reach one read as [CloseOutcome.REFUSED].
     *
     * Refused rather than allowed, unlike the failures around it: a prompt that throws
     * may have had its dialog up already, and an event cannot be handed back once that
     * has happened. Refusing keeps the pins and still closes the rest.
     */
    private fun askAbout(pinned: List<PinnedFile>): CloseOutcome =
        try {
            gate.outcomeFor(pinned)
        } catch (failure: Throwable) {
            rethrowIfUnrecoverable(failure)
            LOG.warn("could not put ${pinned.map { it.presentableName }} to the user; keeping them open", failure)
            CloseOutcome.REFUSED
        }

    /**
     * Closes exactly [targets], reproducing the original's targeting: per split
     * when the event named one, else project-wide.
     */
    private fun closeExactly(event: AnActionEvent, targets: List<CloseTarget>) {
        val project = event.project
        val manager = project?.let { FileEditorManagerEx.getInstanceEx(it) }

        // One command, as the actions this replaces both use. Closing a tab starts a
        // command of its own, so without an outer one a narrowed "Close All" becomes
        // a run of N commands where the original was a single named one — which
        // command listeners and `IdeDocumentHistory` grouping both see.
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
 * Per-action rather than on [GuardedCloseAction] itself, which would newly admit
 * the Close All family to LightEdit mode.
 */
internal class GuardedLightEditCloseAction(
    original: AnAction,
    scope: CloseActionScope,
    selector: PinnedFileSelector,
    gate: PinnedCloseGate,
) : GuardedCloseAction(original, scope, selector, gate), LightEditCompatible

/**
 * The variant for `Terminal.CloseTab`, whose original is an [ActionPromoter].
 *
 * `Terminal.CloseTab` and `CloseContent` both answer Cmd+W, and the promotion is
 * what decides which of them the platform performs; a wrapper without the marker
 * would silently hand the keystroke back to `CloseContent`.
 */
internal class GuardedPromotedCloseAction(
    original: AnAction,
    scope: CloseActionScope,
    selector: PinnedFileSelector,
    gate: PinnedCloseGate,
) : GuardedCloseAction(original, scope, selector, gate), ActionPromoter {

    // `this`, not the original: `Utils.rearrangeByPromotersImpl` splices whatever a
    // promoter returns into the head of the candidate list, so delegating would
    // insert the displaced, unguarded action and the platform would perform it.
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? = listOf(this)
}
