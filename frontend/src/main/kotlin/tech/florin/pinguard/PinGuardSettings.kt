package tech.florin.pinguard

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * The user-facing knobs, and the shape they persist in.
 *
 * Mutable `var`s with defaults are what the platform's XML serializer expects.
 *
 * Public because the serializer reaches it reflectively and because it is the type
 * argument of [PinGuardSettings]'s public supertype.
 */
public data class PinGuardState(
    // These defaults are re-applied to every existing install on each load, not
    // just to new ones: the store omits any value equal to a freshly constructed
    // state, so changing one silently reverses the setting for everyone on it.
    var enabled: Boolean = true,
    var confirmInsteadOfBlock: Boolean = false,
)

/**
 * Application-level settings, so the choice follows the user across projects.
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
     * Copied both ways, so a caller holding what it read cannot reconfigure every
     * open project through it.
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
