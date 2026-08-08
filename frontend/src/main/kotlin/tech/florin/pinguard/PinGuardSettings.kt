package tech.florin.pinguard

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * The user-facing knobs, and the shape they persist in.
 *
 * One class rather than a persisted form plus a domain copy: the two would hold
 * the same two booleans, and the mapping between them is code that can only ever
 * be wrong. Mutable `var`s with defaults are what the platform's XML serializer
 * expects.
 *
 * Public because the serializer reaches it reflectively and because it is the type
 * argument of [PinGuardSettings]'s public supertype.
 */
public data class PinGuardState(
    /**
     * Guard on, prompt off. Someone installing a plugin called "PinGuard" wants
     * pins to hold; opting into a dialog is the deliberate step.
     */
    var enabled: Boolean = true,
    var confirmInsteadOfBlock: Boolean = false,
)

/**
 * Application-level settings: pinning is a habit of working, not a property of one
 * project, so the choice follows the user across projects.
 *
 * Public because the service container instantiates it reflectively.
 */
@Service(Service.Level.APP)
@State(name = "PinGuard", storages = [Storage("pinguard.xml")])
public class PinGuardSettings : PersistentStateComponent<PinGuardState> {

    private var current = PinGuardState()

    override fun getState(): PinGuardState = current

    override fun loadState(state: PinGuardState) {
        current = state
    }

    /**
     * The settings as the rest of the plugin reads and writes them.
     *
     * Copied both ways: [PinGuardState] has to be mutable for the serializer, and
     * a caller holding a live reference to the stored object could otherwise
     * change what every open project does simply by keeping it around.
     */
    internal var config: PinGuardState
        get() = current.copy()
        set(value) {
            current = value.copy()
        }

    internal companion object {
        fun getInstance(): PinGuardSettings = service()
    }
}
