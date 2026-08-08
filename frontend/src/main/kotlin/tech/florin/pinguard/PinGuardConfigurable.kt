package tech.florin.pinguard

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected

/**
 * Settings | Editor | PinGuard
 *
 * Public because the module descriptor names it and the platform instantiates it
 * reflectively.
 */
public class PinGuardConfigurable : BoundConfigurable(PinGuardBundle.message("settings.title")) {

    override fun createPanel(): DialogPanel {
        val settings = PinGuardSettings.getInstance()

        return panel {
            lateinit var enabled: Cell<JBCheckBox>

            row {
                enabled = checkBox(PinGuardBundle.message("settings.enabled"))
                    .bindSelected(
                        { settings.config.enabled },
                        { settings.config = settings.config.copy(enabled = it) },
                    )
            }

            indent {
                row {
                    checkBox(PinGuardBundle.message("settings.confirm"))
                        .bindSelected(
                            { settings.config.confirmInsteadOfBlock },
                            { settings.config = settings.config.copy(confirmInsteadOfBlock = it) },
                        )
                        .enabledIf(enabled.selected)
                        .comment(PinGuardBundle.message("settings.confirm.comment"))
                }
            }

            row {
                comment(PinGuardBundle.message("settings.group.comment"))
            }
        }
    }
}
