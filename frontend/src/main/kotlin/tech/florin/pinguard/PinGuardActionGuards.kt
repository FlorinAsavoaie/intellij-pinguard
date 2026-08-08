package tech.florin.pinguard

import com.intellij.ide.lightEdit.LightEditCompatible
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.atomic.AtomicBoolean

private val LOG: Logger = Logger.getInstance(PinGuardActionGuards::class.java)

/**
 * The close actions PinGuard wraps, each mapped to how its targets are worked out.
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
 */
internal val GUARDED: Map<String, CloseActionScope> = mapOf(
    "CloseContent" to SingleTabScope,
    "CloseAllEditors" to AllTabsScope,
    "CloseAllEditorsButActive" to OtherTabsScope,
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

            // Branching on the marker rather than on the id keeps the wrapper from
            // ever adding or dropping a capability the original did not carry,
            // including for any action GUARDED grows.
            actions.replaceAction(
                id,
                if (original is LightEditCompatible) {
                    GuardedLightEditCloseAction(original, scope, selector, gate)
                } else {
                    GuardedCloseAction(original, scope, selector, gate)
                },
            )
            displaced[id] = original
        } catch (failure: Throwable) {
            rethrowIfUnrecoverable(failure)
            LOG.warn("could not guard close action '$id'; it keeps its stock behaviour", failure)
        }
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
            displaced.forEach { (id, original) -> actions.replaceAction(id, original) }
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
}
