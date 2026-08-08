package tech.florin.pinguard

import com.intellij.ide.lightEdit.LightEditCompatible
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.runInEdtAndWait
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Installing the guards over the IDE's own close actions, and taking them off
 * again.
 *
 * [GUARDED] names its actions as bare strings, and an id the IDE has renamed is
 * logged at INFO and skipped — so that close path silently loses its protection,
 * with a working plugin and a green build either side of it. These tests are what
 * turns that into a failure.
 *
 * `replaceAction` is IDE-wide state, so every test here puts back what it changed
 * even when it fails.
 */
@TestApplication
internal class PinGuardActionGuardsTest {

    private val actions: ActionManager get() = ActionManager.getInstance()

    /**
     * The application-level guards the plugin installs for itself.
     *
     * The plugin is really loaded in the test sandbox, so the first project any
     * test opens runs [PinGuardCloseActions] and leaves every close action
     * wrapped for the rest of the JVM. Tests that want to watch an install
     * happen therefore have to put the platform's own actions back first — which
     * is what an unload does anyway — and hand them over again afterwards.
     */
    private val installedByThePlugin: PinGuardActionGuards
        get() = ApplicationManager.getApplication().service()

    @BeforeEach
    fun startFromThePlatformsOwnActions() {
        runInEdtAndWait { installedByThePlugin.dispose() }
    }

    @AfterEach
    fun leaveTheGuardsInstalledAsTheyWereFound() {
        runInEdtAndWait { installedByThePlugin.install(actions) }
    }

    private fun snapshot(): Map<String, AnAction> =
        GUARDED.keys.associateWith { id -> requireNotNull(actions.getAction(id)) { "no action '$id'" } }

    /** Installs a fresh set of guards, runs [body], then restores the platform's actions. */
    private fun installed(body: (PinGuardActionGuards) -> Unit) {
        lateinit var guards: PinGuardActionGuards
        runInEdtAndWait { guards = PinGuardActionGuards().apply { ensureInstalled() } }
        try {
            body(guards)
        } finally {
            runInEdtAndWait { guards.dispose() }
        }
    }

    @Test
    fun `every close action PinGuard means to guard still exists in this IDE`() {
        GUARDED.keys.forEach { id ->
            assertTrue(actions.getAction(id) != null, "close action '$id' is not registered; it would go unguarded")
        }
    }

    @Test
    fun `installing wraps every one of them`() {
        val before = snapshot()

        installed {
            GUARDED.keys.forEach { id ->
                val installedAction = actions.getAction(id)
                assertTrue(installedAction is GuardedCloseAction, "'$id' was left unwrapped")
                assertTrue(installedAction !== before[id], "'$id' is still the platform's own action")
            }
        }
    }

    @Test
    fun `disposing puts the platform's own actions back, by identity`() {
        val before = snapshot()

        installed { }

        before.forEach { (id, original) ->
            assertSame(original, actions.getAction(id), "'$id' was not restored to the instance PinGuard displaced")
        }
    }

    @Test
    fun `installing twice does not wrap a wrapper`() {
        // Without the `is GuardedCloseAction` check, a second pass would record a
        // wrapper as the "original" to restore, and unloading the plugin would
        // leave one behind for good.
        val before = snapshot()

        installed { guards ->
            val afterFirstPass = GUARDED.keys.associateWith { actions.getAction(it) }

            runInEdtAndWait { guards.install(actions) }

            GUARDED.keys.forEach { id ->
                assertSame(
                    afterFirstPass[id],
                    actions.getAction(id),
                    "'$id' was wrapped a second time, so a wrapper is now recorded as the original to restore",
                )
            }
        }

        before.forEach { (id, original) ->
            assertSame(original, actions.getAction(id), "'$id' did not come all the way back")
        }
    }

    @Test
    fun `a guarded action keeps the label the platform gave it`() {
        // replaceAction does not carry a presentation across, and a wrapper built on
        // a bare AnAction starts with an empty one. Menus, Settings | Keymap and
        // Find Action all render from the template presentation, so losing it shows
        // up as unnamed entries rather than as anything failing.
        val before = snapshot()

        // Guards against this test passing because everything is blank on both
        // sides: these two never set their text from update().
        listOf("CloseContent", "CloseAllEditorsButActive").forEach { id ->
            assertTrue(
                !requireNotNull(before[id]).templatePresentation.text.isNullOrBlank(),
                "'$id' has no template text even before wrapping; this test would prove nothing",
            )
        }

        installed {
            GUARDED.keys.forEach { id ->
                assertEquals(
                    requireNotNull(before[id]).templatePresentation.text,
                    requireNotNull(actions.getAction(id)).templatePresentation.text,
                    "'$id' lost the label the platform gave it",
                )
            }
        }
    }

    @Test
    fun `a guarded CloseContent falls back to its main-menu label in the tab popup`() {
        // Pins down a known, deliberate cosmetic loss rather than a promise.
        // CloseContent carries <override-text place="EditorTabPopup"/>, so the stock
        // tab menu says "Close" where the main menu says "Close Tab". The wrapper
        // cannot carry that across, so it keeps the main-menu label everywhere.
        //
        // If a supported way to carry overrides appears, this is the test that
        // should start failing.
        installed {
            val wrapper = requireNotNull(actions.getAction("CloseContent"))
            val inTabPopup = Presentation()
                .also { it.copyFrom(wrapper.templatePresentation) }
                .also { wrapper.applyTextOverride(ActionPlaces.EDITOR_TAB_POPUP, it) }
                .text

            assertEquals(
                wrapper.templatePresentation.text,
                inTabPopup,
                "the wrapper answered a per-place override; the omission documented on GuardedCloseAction is stale",
            )
            assertTrue(!inTabPopup.isNullOrBlank(), "and whatever it shows must at least not be blank")
        }
    }

    @Test
    fun `only CloseContent gets the LightEdit variant`() {
        installed {
            GUARDED.keys.forEach { id ->
                val installedAction = requireNotNull(actions.getAction(id))
                val isLightEdit = installedAction is LightEditCompatible

                assertEquals(
                    id == "CloseContent",
                    isLightEdit,
                    "'$id' is LightEdit-compatible=$isLightEdit, which changes which actions LightEdit mode admits",
                )
            }
        }
    }

    @Test
    fun `the CloseContent special case rests on a marker the platform still carries`() {
        // If the platform drops the marker, GuardedLightEditCloseAction is adding a
        // capability rather than preserving one.
        val closeContent = requireNotNull(actions.getAction("CloseContent"))

        assertTrue(closeContent is LightEditCompatible, "CloseContent is no longer LightEdit-compatible")
    }

    @Test
    fun `the Close All family is not LightEdit compatible, which is why the marker is per-action`() {
        val others = GUARDED.keys - "CloseContent"

        others.forEach { id ->
            val original = requireNotNull(actions.getAction(id))

            assertFalse(
                original is LightEditCompatible,
                "'$id' is LightEdit-compatible in the platform now; the per-action marker may no longer be needed",
            )
        }
    }

    @Test
    fun `disposing twice is harmless, because the platform may unload a plugin more than once`() {
        lateinit var guards: PinGuardActionGuards
        runInEdtAndWait { guards = PinGuardActionGuards().apply { ensureInstalled() } }
        val before = snapshot()

        runInEdtAndWait { guards.dispose() }
        runInEdtAndWait { guards.dispose() }

        before.forEach { (id, _) ->
            assertFalse(actions.getAction(id) is GuardedCloseAction, "'$id' is still wrapped after two disposals")
        }
    }

    @Test
    fun `CloseAllUnpinnedEditors is deliberately left alone`() {
        // It already leaves pinned tabs alone, so wrapping it would ask about a
        // close that was never going to touch a pin.
        assertFalse("CloseAllUnpinnedEditors" in GUARDED.keys)
        assertTrue(actions.getAction("CloseAllUnpinnedEditors") != null, "the action it defers to still exists")
    }

    @Test
    fun `the scoped Close family is deliberately left alone, because it excludes pins itself`() {
        // These four never offer a pinned tab up in the first place, so guarding
        // them bought nothing and cost a setAccessible call on a non-public
        // platform method. GuardedCloseActionTest holds the platform to that.
        val noLongerGuarded = listOf(
            "CloseAllToTheLeft",
            "CloseAllToTheRight",
            "CloseAllUnmodifiedEditors",
            "CloseAllReadonly",
        )

        installed {
            noLongerGuarded.forEach { id ->
                assertFalse(id in GUARDED.keys)
                assertTrue(actions.getAction(id) != null, "'$id' should still exist, just unwrapped")
                assertFalse(
                    actions.getAction(id) is GuardedCloseAction,
                    "'$id' is wrapped again; either that is deliberate or GUARDED grew it back by accident",
                )
            }
        }
    }

    @Test
    fun `CloseEditor is deliberately left alone, because the extension point already covers it`() {
        // It is the one action that consults virtualFilePreCloseCheck, so guarding
        // it too would ask twice about the same close.
        assertFalse("CloseEditor" in GUARDED.keys)
        assertTrue(actions.getAction("CloseEditor") != null)
    }

    @Test
    fun `Terminal_CloseTab shares CloseContent's shortcut, which is how it takes Cmd+W`() {
        // The terminal plugin declares `use-shortcut-of="CloseContent"`, so a
        // terminal tab moved into the editor is closed by *this* action on the
        // keystroke users think of as CloseContent's. If that binding ever goes,
        // guarding this id stops buying anything and this test says so.
        val terminalClose = requireNotNull(actions.getAction(TERMINAL_CLOSE_TAB)) {
            "'$TERMINAL_CLOSE_TAB' is not registered; the terminal plugin is missing from this test IDE"
        }
        val closeContent = requireNotNull(actions.getAction("CloseContent"))

        assertEquals(
            closeContent.shortcutSet.shortcuts.toList(),
            terminalClose.shortcutSet.shortcuts.toList(),
            "'$TERMINAL_CLOSE_TAB' no longer answers CloseContent's shortcut",
        )
        assertTrue(
            terminalClose.shortcutSet.shortcuts.isNotEmpty(),
            "both are unbound in this keymap, so this test would prove nothing",
        )
    }

    @Test
    fun `Terminal_CloseTab promotes itself, which is why guarding CloseContent alone is not enough`() {
        // This is the whole bug: on a shortcut both actions answer, the platform
        // asks every ActionPromoter to reorder the candidates and then performs the
        // first *enabled* one. Terminal.CloseTab puts itself in front, so PinGuard's
        // wrapped CloseContent is never even consulted.
        val terminalClose = requireNotNull(actions.getAction(TERMINAL_CLOSE_TAB))
        val closeContent = requireNotNull(actions.getAction("CloseContent"))

        assertTrue(
            terminalClose is ActionPromoter,
            "'$TERMINAL_CLOSE_TAB' is no longer a promoter; it may no longer outrank CloseContent",
        )
        assertEquals(
            listOf(terminalClose),
            (terminalClose as ActionPromoter).promote(listOf(closeContent, terminalClose), DataContext.EMPTY_CONTEXT),
            "'$TERMINAL_CLOSE_TAB' no longer promotes itself to the front",
        )
    }

    @Test
    fun `Terminal_CloseTab is guarded, because it is what Cmd+W actually runs in a terminal tab`() {
        assertTrue(TERMINAL_CLOSE_TAB in GUARDED.keys)

        installed {
            assertTrue(
                actions.getAction(TERMINAL_CLOSE_TAB) is GuardedCloseAction,
                "'$TERMINAL_CLOSE_TAB' was left unwrapped, so Cmd+W still closes a pinned terminal tab",
            )
        }
    }

    @Test
    fun `a guarded Terminal_CloseTab is still a promoter, so it keeps winning the shortcut`() {
        // A wrapper that dropped the marker would hand the keystroke back to
        // CloseContent. Both are guarded, so the user-visible outcome would survive
        // — but which action runs would have moved, silently, as a side effect of
        // wrapping. Preserve what the original carried.
        installed {
            val wrapper = requireNotNull(actions.getAction(TERMINAL_CLOSE_TAB))

            // Without this the test reads the platform's own action and passes
            // whether or not PinGuard wraps anything.
            assertTrue(wrapper is GuardedCloseAction, "'$TERMINAL_CLOSE_TAB' is not wrapped at all")
            assertTrue(wrapper is ActionPromoter, "the wrapper dropped the promoter marker the original carried")
            assertEquals(
                listOf(wrapper),
                (wrapper as ActionPromoter).promote(listOf(actions.getAction("CloseContent"), wrapper), DataContext.EMPTY_CONTEXT),
                "the wrapper no longer promotes itself the way the action it displaced did",
            )
        }
    }

    @Test
    fun `a guarded Terminal_CloseTab keeps its shortcut, so the terminal still lets Cmd+W through`() {
        // TerminalEventDispatcher swallows every keystroke and sends it to the shell
        // unless it matches the shortcut of an allow-listed action, and it resolves
        // that list by id through ActionManager — so it will resolve *the wrapper*.
        // A wrapper that reported no shortcut would make Cmd+W reach the shell as
        // ^W instead, which is a far worse bug than the one being fixed.
        val before = requireNotNull(actions.getAction(TERMINAL_CLOSE_TAB)).shortcutSet.shortcuts.toList()

        installed {
            val wrapper = requireNotNull(actions.getAction(TERMINAL_CLOSE_TAB))

            assertTrue(wrapper is GuardedCloseAction, "'$TERMINAL_CLOSE_TAB' is not wrapped at all")
            assertEquals(
                before,
                wrapper.shortcutSet.shortcuts.toList(),
                "the wrapper answers different shortcuts, so the terminal's allow-list no longer matches Cmd+W",
            )
        }
    }

    @Test
    fun `no guarded action carries both markers, which is why wrap reads them in order`() {
        // PinGuardActionGuards.wrap has a variant per marker and none for the two
        // together, so an action carrying both would silently lose one. Nothing the
        // platform ships does — and this is what notices the day one starts.
        GUARDED.keys.mapNotNull { actions.getAction(it) }.forEach { original ->
            assertFalse(
                original is LightEditCompatible && original is ActionPromoter,
                "'${actions.getId(original)}' is now both LightEditCompatible and an ActionPromoter, " +
                    "so wrapping it drops one; wrap() needs a combined variant",
            )
        }
    }

    @Test
    fun `Terminal_CloseSession is deliberately left alone, because it closes nothing`() {
        // Ctrl+D only writes EOF to the shell. The tab that follows is closed by
        // TerminalViewFileEditor on session termination, programmatically, which no
        // action-layer guard can or should intercept.
        assertFalse("Terminal.CloseSession" in GUARDED.keys)
    }
}
