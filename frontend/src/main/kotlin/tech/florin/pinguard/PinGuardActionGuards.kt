package tech.florin.pinguard

import com.intellij.ide.lightEdit.LightEditCompatible
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.atomic.AtomicBoolean

private val LOG: Logger = Logger.getInstance(PinGuardActionGuards::class.java)

/** The terminal's own "Close Tab", which is what Cmd+W runs in a terminal tab. */
internal const val TERMINAL_CLOSE_TAB: String = "Terminal.CloseTab"

/**
 * The close actions PinGuard wraps, each mapped to how its targets are worked out.
 *
 * [TERMINAL_CLOSE_TAB] is here because guarding `CloseContent` does not cover
 * Cmd+W: the terminal plugin declares its own close with
 * `use-shortcut-of="CloseContent"` and promotes itself past it, so a terminal
 * moved into the editor and pinned there is closed by that action instead.
 *
 * Left out on purpose:
 *  - `CloseEditor`, the only action consulting `virtualFilePreCloseCheck`.
 *    Wrapping it too would ask twice about the same close.
 *  - `CloseAllUnpinnedEditors`, which already leaves pinned tabs alone.
 *  - the tab's own close button, which already refuses to close a pinned tab and
 *    is constructed per tab rather than registered with [ActionManager].
 *  - Close to the Left/Right, Close Unmodified and Close Readonly, whose shared
 *    `CloseEditorsActionBase` consults `EditorComposite.isPinned` before offering
 *    a tab up. `GuardedCloseActionTest` holds the platform to that.
 *  - `Terminal.CloseSession`, the terminal's Ctrl+D, which only writes EOF to the
 *    shell. The tab that follows is closed by `TerminalViewFileEditor` when the
 *    session terminates — programmatically, so no action-layer guard can see it.
 */
internal val GUARDED: Map<String, CloseActionScope> = mapOf(
    "CloseContent" to SingleTabScope,
    "CloseAllEditors" to AllTabsScope,
    "CloseAllEditorsButActive" to OtherTabsScope,
    TERMINAL_CLOSE_TAB to SingleTabScope,
)

/**
 * Holds the close-action guards for as long as the plugin is loaded.
 *
 * [ActionManager.replaceAction] mutates IDE-wide state, so this is an application
 * service: the guards go on once however many projects are open, and the displaced
 * actions have somewhere to wait until the plugin is unloaded.
 *
 * [PinGuardCloseActions] is what brings this into existence; nothing else calls
 * [ensureInstalled].
 *
 * Public because the service container instantiates it reflectively.
 */
@Service(Service.Level.APP)
public class PinGuardActionGuards : Disposable {

    /** The actions displaced by a guard, so an unload can restore them. */
    private val displaced = mutableMapOf<String, AnAction>()

    private val installed = AtomicBoolean(false)

    private val selector = PlatformPinnedFiles.selector()

    private val gate = platformGate()

    /**
     * Installs the guards, once, however many projects open.
     *
     * Not the constructor's work: a service constructor must be fast and must not
     * reach for other services, and installing acquires [ActionManager] and
     * rewrites IDE-wide action state.
     */
    internal fun ensureInstalled() {
        if (installed.compareAndSet(false, true)) {
            install(ActionManager.getInstance())
        }
    }

    internal fun install(actions: ActionManager) {
        GUARDED.forEach { (id, scope) -> guard(actions, id, scope) }
    }

    private fun guard(actions: ActionManager, id: String, scope: CloseActionScope) {
        try {
            val original = actions.getAction(id)
            if (original == null) {
                LOG.info("close action '$id' is not registered in this IDE; not guarded")
                return
            }
            if (original is GuardedCloseAction) return

            actions.replaceAction(id, wrap(original, scope))
            displaced[id] = original
        } catch (failure: Throwable) {
            rethrowIfUnrecoverable(failure)
            LOG.warn("could not guard close action '$id'; it keeps its stock behaviour", failure)
        }
    }

    /**
     * The wrapper for [original], carrying across whichever markers it had.
     *
     * No action the platform ships carries both, so there is no combined variant.
     * `PinGuardActionGuardsTest` fails the day one does, rather than letting this
     * silently drop the second.
     */
    private fun wrap(original: AnAction, scope: CloseActionScope): GuardedCloseAction = when (original) {
        is LightEditCompatible -> GuardedLightEditCloseAction(original, scope, selector, gate)
        is ActionPromoter -> GuardedPromotedCloseAction(original, scope, selector, gate)
        else -> GuardedCloseAction(original, scope, selector, gate)
    }

    /**
     * Puts the platform's own actions back, so an unloaded plugin leaves behind no
     * wrappers still referencing its classes.
     *
     * Safe to call more than once, and safe during shutdown.
     */
    override fun dispose() {
        try {
            // Inside the guard: during shutdown the service container may already be
            // cancelled, and a `ProcessCanceledException` escaping a `dispose()` is
            // itself an error the platform reports.
            val actions = ActionManager.getInstance()
            displaced.forEach { (id, original) ->
                surrenderShortcutSet(original)
                actions.replaceAction(id, original)
            }
        } catch (failure: Throwable) {
            rethrowIfUnrecoverable(failure)
            LOG.warn("could not restore the platform's close actions while unloading", failure)
        } finally {
            displaced.clear()
            // So a service disposed and used again installs afresh rather than
            // believing it already has.
            installed.set(false)
        }
    }

    /**
     * Empties [action]'s shortcut set so the [ActionManager.replaceAction] that
     * follows can assign the id's own without the platform logging at us.
     *
     * Nothing is surrendered permanently: that assignment is the same one which
     * gives a wrapper Cmd+W on the way in.
     */
    private fun surrenderShortcutSet(action: AnAction) {
        // `replaceAction` assigns the id's shortcut set to whatever it registers, and
        // `AnAction.setShortcutSet` logs a `PluginException` against this plugin when
        // the target is registered under an id and already carries a non-empty set.
        // Installing is silent because a fresh wrapper starts empty; a displaced
        // action has held the keymap's shortcuts since the IDE registered it, so
        // without this the restore logs once per guarded action — and the first of
        // those, landing during shutdown, drags
        // `PluginProblemReporterImpl is initialized during dispose` with it.
        //
        // Guarded on its own: a wrapper for one id must still come off even if this
        // throws for another.
        try {
            action.copyShortcutFrom(NoShortcuts)
        } catch (failure: Throwable) {
            rethrowIfUnrecoverable(failure)
            LOG.warn("could not clear a displaced action's shortcut set before restoring it", failure)
        }
    }
}

/**
 * An action held only to be copied from, which is how [PinGuardActionGuards]
 * says "carry no shortcuts" without reaching for internal API.
 *
 * [AnAction]'s constructor starts a shortcut set at [CustomShortcutSet.EMPTY], and
 * this one is never registered under an id, so it stays there.
 *
 * The direct equivalent — assigning `CustomShortcutSet.EMPTY` to
 * `AnAction.setShortcutSet` — is `@ApiStatus.Internal`, which `verifyPlugin` fails
 * the build on. [AnAction.copyShortcutFrom] is public and final and its whole body
 * is that same assignment.
 */
private object NoShortcuts : AnAction() {
    // Unreachable: nothing registers this action, puts it in a menu, or dispatches
    // to it.
    override fun actionPerformed(e: AnActionEvent): Unit = Unit
}
