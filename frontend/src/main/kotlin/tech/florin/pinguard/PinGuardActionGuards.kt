package tech.florin.pinguard

import com.intellij.ide.lightEdit.LightEditCompatible
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.atomic.AtomicBoolean

private val LOG: Logger = Logger.getInstance(PinGuardActionGuards::class.java)

/**
 * The terminal's own "Close Tab", which is what Cmd+W runs in a terminal tab.
 *
 * Named rather than inlined because several tests have to talk about it: it is the
 * one guarded id that belongs to a bundled plugin rather than to the platform, so
 * what it does and what it is bound to are the platform's to change.
 */
internal const val TERMINAL_CLOSE_TAB: String = "Terminal.CloseTab"

/**
 * The close actions PinGuard wraps, each mapped to how its targets are worked out.
 *
 * [TERMINAL_CLOSE_TAB] is here because guarding `CloseContent` does not cover the
 * keystroke users think of as its own. The terminal plugin declares its own close
 * with `use-shortcut-of="CloseContent"`, so both answer Cmd+W; the platform then
 * asks every `ActionPromoter` to reorder the candidates and performs the first
 * *enabled* one, and the terminal's promotes itself. A terminal moved into the
 * editor and pinned there was therefore closed by an action PinGuard had never
 * heard of. It reaches the same `CloseAction.CloseTarget` that `CloseContent`
 * does, so [SingleTabScope] describes it exactly.
 *
 * Left out on purpose:
 *  - `CloseEditor`, the only action consulting `virtualFilePreCloseCheck`.
 *    Wrapping it too would ask twice about the same close.
 *  - `CloseAllUnpinnedEditors`, which already leaves pinned tabs alone.
 *  - the tab's own close button, which already refuses to close a pinned tab and
 *    is constructed per tab rather than registered with [ActionManager].
 *  - Close to the Left/Right, Close Unmodified and Close Readonly.
 *    `CloseEditorsActionBase` consults `EditorComposite.isPinned` before offering
 *    a tab up, so a pinned tab is never among the files any of them would close;
 *    guarding them cost a `setAccessible` call on a non-public platform method to
 *    re-refuse closes that were never going to happen. `GuardedCloseActionTest`
 *    holds the platform to that behaviour, and if it ever changes these four come
 *    back and the reflection with them.
 *  - `Terminal.CloseSession`, the terminal's Ctrl+D, which only writes EOF to the
 *    shell. The tab that follows is closed by `TerminalViewFileEditor` when the
 *    session terminates — programmatically, so no action-layer guard can see it.
 */
internal val GUARDED: Map<String, CloseActionScope> = mapOf(
    "CloseContent" to SingleTabScope,
    "CloseAllEditors" to AllTabsScope,
    // Wrapped for one of its two branches only; see [OtherTabsScope] for which,
    // and why the other one is the platform's business rather than PinGuard's.
    "CloseAllEditorsButActive" to OtherTabsScope,
    TERMINAL_CLOSE_TAB to SingleTabScope,
)

/**
 * Holds the close-action guards for as long as the plugin is loaded.
 *
 * An application service rather than a one-shot install because
 * [ActionManager.replaceAction] mutates IDE-wide state: the install then happens
 * exactly once however many projects are open, and the displaced actions have
 * somewhere to wait until the plugin is unloaded.
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

    // Held as fields rather than built per close: both are stateless and reach for
    // no service, which keeps the service constructor within the "fast, and no
    // other services" rule that ensureInstalled exists to honour.
    private val selector = PlatformPinnedFiles.selector()

    private val gate = platformGate()

    /**
     * Installs the guards, once, however many projects open.
     *
     * Deliberately not the constructor's work: a service constructor is required
     * to be fast and not to reach for other services, and installing means
     * acquiring [ActionManager] and rewriting IDE-wide action state.
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
     * Branching on the markers rather than on the id keeps a wrapper from ever
     * adding or dropping a capability the original did not carry, including for any
     * action [GUARDED] grows.
     *
     * No action the platform ships carries both markers, so there is no combined
     * variant here and this reads them in a fixed order rather than as a matrix.
     * `PinGuardActionGuardsTest` holds the platform to that: the day a guarded
     * action is both [LightEditCompatible] and an [ActionPromoter], it fails rather
     * than letting this silently drop one of the two.
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
     * Safe to call more than once, and safe during shutdown: the [ActionManager]
     * lookup is inside the guard because the service container may already be
     * cancelled, and a `ProcessCanceledException` escaping a `dispose()` is itself
     * an error the platform reports.
     */
    override fun dispose() {
        try {
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
            // So a service that is disposed and used again installs afresh rather
            // than believing it already has.
            installed.set(false)
        }
    }

    /**
     * Empties [action]'s shortcut set so the [ActionManager.replaceAction] that
     * follows can assign the id's own without the platform logging at us.
     *
     * `replaceAction` assigns the action id's shortcut set to whatever it registers,
     * and `AnAction.setShortcutSet` logs a `PluginException` against this plugin
     * whenever the action it assigns to is registered under an id *and* already
     * carries a set that is not [CustomShortcutSet.EMPTY]. Installing is silent
     * because a freshly built [GuardedCloseAction] starts empty — see the note on
     * that class, which is where this was first paid for. Restoring is not: the
     * original has carried the keymap's shortcuts since the IDE registered it, so
     * the assignment on the way back out logs once per guarded action:
     *
     *     ShortcutSet of global AnActions should not be changed outside of
     *     KeymapManager. Action: Close Tab (Close currently focused content)
     *
     * and the first of them, when it lands during application shutdown, drags
     * `PluginProblemReporterImpl is initialized during dispose` along with it,
     * because building that exception instantiates a service the container is busy
     * tearing down.
     *
     * Handing the action back empty is what makes it symmetric: it goes into
     * `replaceAction` in the same state a wrapper does, and comes out holding the
     * shortcuts the id owns. Nothing is surrendered permanently — the assignment
     * this defers to is the same one that gives the wrapper Cmd+W on the way in.
     *
     * Done here rather than by not restoring at all during shutdown, which would
     * have quietened the logs by leaving a dynamic unload — the case [dispose] is
     * for — logging exactly as before.
     *
     * The write is guarded on its own: a wrapper for one id must still be taken off
     * even if this throws for another, and the shortcut set is not what makes the
     * restore worth doing.
     */
    private fun surrenderShortcutSet(action: AnAction) {
        try {
            action.shortcutSet = CustomShortcutSet.EMPTY
        } catch (failure: Throwable) {
            rethrowIfUnrecoverable(failure)
            LOG.warn("could not clear a displaced action's shortcut set before restoring it", failure)
        }
    }
}
