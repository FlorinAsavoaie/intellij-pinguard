package tech.florin.pinguard

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Brings [PinGuardActionGuards] into existence once an IDE frame exists.
 *
 * The whole of the guarding — which actions, how they are wrapped, and how the
 * originals come back on unload — lives in that service. This class exists only
 * because a service needs something to ask for it.
 *
 * Public because the module descriptor names it and the platform instantiates it
 * reflectively.
 */
public class PinGuardCloseActions : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Opening a second project gets the same service back, and the installation is
        // idempotent, so this is once per IDE run rather than once per project.
        service<PinGuardActionGuards>().ensureInstalled()
    }
}
