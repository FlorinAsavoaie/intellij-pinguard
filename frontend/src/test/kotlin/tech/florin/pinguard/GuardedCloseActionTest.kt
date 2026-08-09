package tech.florin.pinguard

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.fileEditor.impl.EditorComposite
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The close actions users actually reach, across the three [CloseActionScope]
 * implementations [GUARDED] names.
 *
 * The whole path from an action event to a set of closed tabs — scope, pin lookup,
 * gate, and the per-split targeting of the replacement close — only exists in a
 * running IDE, so all of it is pinned down here rather than in isolation.
 *
 * The wrappers are built directly rather than installed over the platform's
 * actions, so these tests say nothing about IDE-wide state; that is
 * [PinGuardActionGuardsTest]'s job.
 */
internal class GuardedCloseActionTest : RealEditorTestCase() {

    private fun gate(config: PinGuardState = PinGuardState(), answer: Boolean = false) =
        PinnedCloseGate(
            configProvider = { config },
            prompt = { answer },
        )

    private fun guarded(
        actionId: String,
        scope: CloseActionScope,
        gate: PinnedCloseGate = gate(),
    ): GuardedCloseAction {
        val original = requireNotNull(ActionManager.getInstance().getAction(actionId)) { "no action '$actionId'" }
        return GuardedCloseAction(original, scope, PlatformPinnedFiles.selector(), gate)
    }

    /** An event carrying what a real close action would have been handed. */
    private fun event(file: VirtualFile? = null, window: EditorWindow? = mainWindow()): AnActionEvent {
        val context: DataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .apply {
                if (file != null) add(CommonDataKeys.VIRTUAL_FILE, file)
                if (window != null) add(EditorWindow.DATA_KEY, window)
            }
            .build()

        return AnActionEvent.createEvent(context, Presentation(), "PinGuardTest", ActionUiKind.NONE, null)
    }

    private fun openNames(): List<String> = openFiles().map { it.name }

    /**
     * Stands in for one of the platform's close actions, recording that it ran.
     *
     * When PinGuard declines to interfere its whole contract is "run the original,
     * untouched", and that is what these record. Asserting on the real action's
     * side effects instead would be asserting the platform's: with a window in the
     * context `CloseAllEditorsAction` iterates `window.getFileList()` while closing
     * into it, so what survives depends on how that live list shifts underneath the
     * iterator. PinGuard's own replacement close iterates the target list it
     * computed beforehand and does not share the quirk.
     */
    private class RecordingAction : AnAction() {
        var invocations = 0
            private set
        var lastEvent: AnActionEvent? = null
            private set

        override fun actionPerformed(e: AnActionEvent) {
            invocations++
            lastEvent = e
        }
    }

    private fun guardedStub(scope: CloseActionScope, gate: PinnedCloseGate = gate()) =
        RecordingAction().let { it to GuardedCloseAction(it, scope, PlatformPinnedFiles.selector(), gate) }

    @Test
    fun `Close All keeps the pinned tab and closes everything else`() {
        val pinned = open("Pinned.kt")
        open("A.kt")
        open("B.kt")
        pin(pinned)

        val action = guarded("CloseAllEditors", AllTabsScope)
        inEdt { action.actionPerformed(event(window = mainWindow())) }

        assertEquals(listOf("Pinned.kt"), openNames())
    }

    @Test
    fun `a close with nothing pinned is handed to the platform's own action untouched`() {
        open("A.kt")
        open("B.kt")

        val (original, action) = guardedStub(AllTabsScope)
        val fired = event(window = mainWindow())
        inEdt { action.actionPerformed(fired) }

        assertEquals(1, original.invocations, "with no pin involved PinGuard has nothing to say")
        assertEquals(fired, original.lastEvent, "the original must see the event the user's gesture produced")
        assertEquals(listOf("A.kt", "B.kt"), openNames(), "and PinGuard must not have closed anything itself")
    }

    @Test
    fun `closing a single pinned tab closes nothing`() {
        val pinned = open("Pinned.kt")
        open("A.kt")
        pin(pinned)

        val action = guarded("CloseContent", SingleTabScope)
        inEdt { action.actionPerformed(event(file = pinned)) }

        assertEquals(setOf("Pinned.kt", "A.kt"), openNames().toSet())
    }

    @Test
    fun `closing a single unpinned tab is left to the platform, even with a pin open elsewhere`() {
        val pinned = open("Pinned.kt")
        val loose = open("A.kt")
        pin(pinned)

        val (original, action) = guardedStub(SingleTabScope)
        inEdt { action.actionPerformed(event(file = loose)) }

        assertEquals(1, original.invocations, "a pin on another tab is not a reason to intercept this close")
        assertEquals(setOf("Pinned.kt", "A.kt"), openNames().toSet())
    }

    @Test
    fun `Close Others keeps both the active tab and the pinned one`() {
        val pinned = open("Pinned.kt")
        open("A.kt")
        val active = open("Active.kt")
        pin(pinned)

        val action = guarded("CloseAllEditorsButActive", OtherTabsScope)
        inEdt { action.actionPerformed(event(file = active)) }

        assertEquals(setOf("Pinned.kt", "Active.kt"), openNames().toSet())
    }

    @Test
    fun `a pin in one split does not block closing the same file in another`() {
        // `window.closeFile` touches one split, so a pin in the *other* split was
        // never in this close's way: refusing on account of it produces a Cmd+W
        // that silently does nothing — no dialog, no tab closed, no explanation.
        val shared = open("Shared.kt")
        val splits = split()
        val pinnedSide = splits[0]
        val looseSide = splits[1]
        pin(shared, pinnedSide)

        assertTrue(shared in filesIn(looseSide), "precondition: the file is open in both splits")
        assertFalse(isPinned(looseSide, shared), "precondition: and pinned in only one of them")

        val (original, action) = guardedStub(SingleTabScope)
        inEdt { action.actionPerformed(event(file = shared, window = looseSide)) }

        assertEquals(1, original.invocations, "the pin is in the other split, so this close was never in its way")
        assertTrue(shared in filesIn(pinnedSide), "and the pinned tab in the other split is untouched")
    }

    private fun filesIn(window: EditorWindow): List<VirtualFile> = runInEdtAndGetValue { window.fileList.toList() }

    private fun isPinned(window: EditorWindow, file: VirtualFile): Boolean =
        runInEdtAndGetValue { window.isFileOpen(file) && window.isFilePinned(file) }

    @Test
    fun `Close Others in a window never asks, because the platform was not going to close the pin`() {
        // In a window `CloseAllEditorsButActiveAction` closes through
        // `EditorWindow.closeAllExcept`, which skips pinned files on its own — so a
        // pinned tab there is not one this action would have closed, and
        // OtherTabsScope names no targets at all.
        val pinned = open("Pinned.kt")
        open("B.kt")
        val active = open("Active.kt")
        pin(pinned)

        var asked = 0
        val counting = PinnedCloseGate(
            configProvider = { PinGuardState(enabled = true, confirmInsteadOfBlock = true) },
            prompt = {
                asked++
                true
            },
        )

        val (original, action) = guardedStub(OtherTabsScope, counting)
        inEdt { action.actionPerformed(event(file = active, window = mainWindow())) }

        assertEquals(0, asked, "the pinned tab was never in this close's way, so there was nothing to ask about")
        assertEquals(1, original.invocations, "and with nothing to protect the close goes to the platform untouched")
    }

    @Test
    fun `closeAllExcept is what makes standing aside in a window safe`() {
        // The platform contract the test above rests on, read by running it.
        // `CloseAllEditorsButActiveAction` extends `AnAction` directly, so unlike
        // the Close Left/Right family below there is no `getFilesToClose` to
        // interrogate — but this is the one call its in-window branch makes. The day
        // it stops skipping pins, OtherTabsScope must stop returning nothing there.
        val pinned = open("Pinned.kt")
        open("Loose.kt")
        val active = open("Active.kt")
        pin(pinned)

        inEdt { mainWindow().closeAllExcept(active) }

        assertEquals(
            setOf("Pinned.kt", "Active.kt"),
            openNames().toSet(),
            "closeAllExcept closed a pinned tab; Close Others now needs guarding in a window too",
        )
    }

    @Test
    fun `a narrowed close runs as one named command, like the action it replaces`() {
        // Both CloseAllEditorsAction and CloseEditorsActionBase wrap their close
        // loop in CommandProcessor.executeCommand, and closing the same tabs
        // outside one is a different thing to command listeners and
        // IdeDocumentHistory grouping.
        val pinned = open("Pinned.kt")
        open("A.kt")
        open("B.kt")
        pin(pinned)

        val started = mutableListOf<String?>()
        val connection = ApplicationManager.getApplication().messageBus.connect()
        try {
            connection.subscribe(
                CommandListener.TOPIC,
                object : CommandListener {
                    override fun commandStarted(event: CommandEvent) {
                        started += event.commandName
                    }
                },
            )

            val action = guarded("CloseAllEditors", AllTabsScope)
            inEdt { action.actionPerformed(event(window = mainWindow())) }
        } finally {
            connection.disconnect()
        }

        assertEquals(listOf("Pinned.kt"), openNames(), "precondition: the close really did narrow")
        assertEquals(
            1,
            started.size,
            "a narrowed close must be one command, not a run of uncommanded closes; started=$started",
        )
    }

    /**
     * The files `CloseEditorsActionBase.getFilesToClose` would hand its action.
     *
     * Reflection on a non-public platform method, which is exactly why it lives in
     * a test and not in the plugin: a test that fails loudly the day the reflection
     * stops resolving is worth more than production code that silently returns
     * null.
     */
    private fun filesTheActionWouldClose(actionId: String): List<VirtualFile> {
        val action = requireNotNull(ActionManager.getInstance().getAction(actionId)) { "no action '$actionId'" }
        val base = generateSequence<Class<*>>(action.javaClass) { it.superclass }
            .firstOrNull { it.name == "com.intellij.ide.actions.CloseEditorsActionBase" }

        assertTrue(base != null, "'$actionId' no longer extends CloseEditorsActionBase; this contract cannot be read")

        val getFilesToClose = base.getDeclaredMethod("getFilesToClose", AnActionEvent::class.java)
        getFilesToClose.isAccessible = true

        return runInEdtAndGetValue {
            (getFilesToClose.invoke(action, event(window = mainWindow())) as List<*>).mapNotNull { entry ->
                ((entry as? Pair<*, *>)?.first as? EditorComposite)?.file
            }
        }
    }

    @Test
    fun `the Close Left-Right-Unmodified-Readonly family excludes pinned tabs by itself`() {
        // `CloseEditorsActionBase` consults `EditorComposite.isPinned` before
        // offering a tab up, which is why PinGuard does not guard these four at
        // all. If this fails, those four ids belong back in GUARDED and the
        // reflection above belongs back in CloseActionScope as a scope of its own.
        val pinned = open("Pinned.kt")
        open("Anchor.kt")
        open("Loose.kt")
        pin(pinned)

        listOf("CloseAllToTheLeft", "CloseAllToTheRight", "CloseAllUnmodifiedEditors", "CloseAllReadonly")
            .forEach { id ->
                assertFalse(
                    pinned in filesTheActionWouldClose(id),
                    "'$id' now offers a pinned tab up for closing; it needs guarding again",
                )
            }
    }

    @Test
    fun `that family really is reading the editor, not coming back empty for its own reasons`() {
        // Without this, the test above would also pass if the reflection quietly
        // returned nothing: "no pinned tab in here" would mean "nothing in here".
        val pinned = open("Pinned.kt")
        open("Anchor.kt")
        open("Loose.kt")
        pin(pinned)

        assertEquals(
            listOf("Anchor.kt"),
            filesTheActionWouldClose("CloseAllToTheLeft").map { it.name },
            "Close to the Left should be offering up the unpinned tabs before the context file",
        )
    }

    @Test
    fun `Close All with no editor window in context still keeps the pinned tab`() {
        // The other branch of every Close All scope: with no EditorWindow the scope
        // falls back to the project's sibling set and the replacement close goes
        // through FileEditorManagerEx rather than through one window. Every other
        // test here supplies a window, so nothing else runs this path.
        val pinned = open("Pinned.kt")
        open("A.kt")
        open("B.kt")
        pin(pinned)

        val action = guarded("CloseAllEditors", AllTabsScope)
        inEdt { action.actionPerformed(event(window = null)) }

        assertEquals(listOf("Pinned.kt"), openNames())
    }

    @Test
    fun `Close Others with no editor window keeps the selected tab and the pinned one`() {
        // The branch that actually needs guarding: with no EditorWindow in the
        // context — Window | Editor Tabs | Close Others with the focus in a tool
        // window — the platform closes each sibling through
        // FileEditorManagerEx.closeFile, which has no pin check of its own.
        val pinned = open("Pinned.kt")
        open("A.kt")
        val active = open("Active.kt")
        pin(pinned)

        val action = guarded("CloseAllEditorsButActive", OtherTabsScope)
        inEdt { action.actionPerformed(event(file = active, window = null)) }

        assertEquals(
            setOf("Pinned.kt", "Active.kt"),
            openNames().toSet(),
            "project-wide, Close Others keeps the selected file — and the pin has to survive it",
        )
    }

    @Test
    fun `closeFile is what makes that windowless branch need guarding`() {
        // The mirror of the closeAllExcept test above, and the reason this action is
        // guarded at all. Without it, the test above would pass just as well if the
        // platform protected pins on both branches, and the guard would be dead
        // weight rather than the one thing between Window | Editor Tabs | Close
        // Others and a pin. If this starts failing, OtherTabsScope can go entirely.
        val pinned = open("Pinned.kt")
        pin(pinned)

        inEdt { manager.closeFile(pinned) }

        assertEquals(
            emptySet(),
            openNames().toSet(),
            "closeFile refuses a pinned tab now, so the windowless branch protects itself",
        )
    }

    @Test
    fun `a disabled guard hands even a pinned close straight to the platform`() {
        val pinned = open("Pinned.kt")
        open("A.kt")
        pin(pinned)

        val (original, action) = guardedStub(
            AllTabsScope,
            gate(PinGuardState(enabled = false, confirmInsteadOfBlock = false)),
        )
        inEdt { action.actionPerformed(event(window = mainWindow())) }

        assertEquals(1, original.invocations, "switched off, PinGuard must not narrow anything")
        assertEquals(setOf("Pinned.kt", "A.kt"), openNames().toSet(), "and must not close anything itself")
    }

    @Test
    fun `confirming the close hands it back to the platform, pinned tab included`() {
        val pinned = open("Pinned.kt")
        open("A.kt")
        pin(pinned)

        val (original, action) = guardedStub(
            AllTabsScope,
            gate(PinGuardState(enabled = true, confirmInsteadOfBlock = true), answer = true),
        )
        inEdt { action.actionPerformed(event(window = mainWindow())) }

        assertEquals(1, original.invocations, "the user said yes, so the close is the platform's again in full")
        assertEquals(setOf("Pinned.kt", "A.kt"), openNames().toSet())
    }

    @Test
    fun `declining the close keeps the pinned tab and still closes the rest`() {
        val pinned = open("Pinned.kt")
        open("A.kt")
        pin(pinned)

        val action = guarded(
            "CloseAllEditors",
            AllTabsScope,
            gate(PinGuardState(enabled = true, confirmInsteadOfBlock = true), answer = false),
        )
        inEdt { action.actionPerformed(event(window = mainWindow())) }

        assertEquals(listOf("Pinned.kt"), openNames())
    }

    @Test
    fun `a scope that cannot tell what would close stands aside entirely`() {
        // SingleTabScope returns null without a VIRTUAL_FILE, which is how tool
        // windows and run consoles reach CloseContent.
        val pinned = open("Pinned.kt")
        pin(pinned)

        val (original, action) = guardedStub(SingleTabScope)
        inEdt { action.actionPerformed(event(file = null, window = null)) }

        assertEquals(1, original.invocations, "no virtual file means no opinion, and no opinion means hands off")
        assertEquals(listOf("Pinned.kt"), openNames())
    }

    @Test
    fun `a selector that throws does not swallow the user's close`() {
        open("A.kt")
        val original = RecordingAction()
        val action = GuardedCloseAction(
            original,
            AllTabsScope,
            { error("editor windows went away mid-close") },
            gate(),
        )

        inEdt { action.actionPerformed(event(window = mainWindow())) }

        assertEquals(1, original.invocations, "a guard that cannot decide must let the close happen, not eat it")
    }

    @Test
    fun `a prompt that throws also lets the close through`() {
        val pinned = open("Pinned.kt")
        open("A.kt")
        pin(pinned)
        val original = RecordingAction()
        val action = GuardedCloseAction(
            original,
            AllTabsScope,
            PlatformPinnedFiles.selector(),
            PinnedCloseGate(
                configProvider = { PinGuardState(enabled = true, confirmInsteadOfBlock = true) },
                prompt = { error("no UI here") },
            ),
        )

        inEdt { action.actionPerformed(event(window = mainWindow())) }

        assertEquals(1, original.invocations)
    }

    @Test
    fun `the presentation is forwarded untouched, so Close All never appears disabled`() {
        val pinned = open("Pinned.kt")
        pin(pinned)
        val original = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = Unit
            override fun update(e: AnActionEvent) {
                e.presentation.text = "Close All Editors"
                e.presentation.isEnabled = true
            }
        }

        val action = GuardedCloseAction(original, AllTabsScope, PlatformPinnedFiles.selector(), gate())
        val update = event(window = mainWindow())
        inEdt { action.update(update) }

        assertTrue(update.presentation.isEnabled, "in blocking mode Close All stays enabled and closes fewer tabs")
        assertEquals("Close All Editors", update.presentation.text)
    }

    @Test
    fun `the update thread is the original's, not a default of the wrapper's own`() {
        // Declaring the wrong one is an IDE-wide threading error rather than a
        // PinGuard-shaped bug.
        val original = requireNotNull(ActionManager.getInstance().getAction("CloseAllEditors"))
        val action = GuardedCloseAction(original, AllTabsScope, PlatformPinnedFiles.selector(), gate())

        assertEquals(original.actionUpdateThread, action.actionUpdateThread)
    }

    @Test
    fun `a guarded Terminal_CloseTab keeps a pinned terminal tab open`() {
        // The reported bug: a terminal moved into the editor and pinned there is
        // closed by Terminal.CloseTab on Cmd+W, not by CloseContent. The tab is an
        // ordinary editor tab over a light virtual file, so a plain file stands in
        // for it — what is being exercised is the action, not the terminal.
        val pinned = open("Pinned.kt")
        open("A.kt")
        pin(pinned)

        val action = guarded(TERMINAL_CLOSE_TAB, terminalScope())
        inEdt { action.actionPerformed(event(file = pinned)) }

        assertEquals(setOf("Pinned.kt", "A.kt"), openNames().toSet())
    }

    /**
     * The scope PinGuard really wires this action to, rather than one this test
     * chose — so picking the wrong one in [GUARDED] fails here rather than shipping.
     */
    private fun terminalScope(): CloseActionScope =
        requireNotNull(GUARDED[TERMINAL_CLOSE_TAB]) { "'$TERMINAL_CLOSE_TAB' is not guarded, so Cmd+W is unprotected" }

    @Test
    fun `a guarded Terminal_CloseTab closes an unpinned tab exactly as it always did`() {
        val loose = open("A.kt")

        val (original, action) = guardedStub(terminalScope())
        inEdt { action.actionPerformed(event(file = loose)) }

        assertEquals(1, original.invocations, "with no pin involved the terminal's own action must run untouched")
        assertEquals(setOf("A.kt"), openNames().toSet(), "and PinGuard must not have closed it itself")
    }

    @Test
    fun `a terminal tool-window close reaches the terminal's own action untouched`() {
        // Terminal.CloseTab also closes tool-window terminal tabs, which are not
        // editor tabs and carry no virtual file. SingleTabScope then cannot name a
        // target, the guard stands aside, and the tool window behaves as it always
        // has — including while an unrelated pinned tab sits in the editor.
        val pinned = open("Pinned.kt")
        pin(pinned)

        val (original, action) = guardedStub(terminalScope())
        inEdt { action.actionPerformed(event(file = null, window = null)) }

        assertEquals(1, original.invocations, "a close with no virtual file is not PinGuard's to intercept")
        assertEquals(setOf("Pinned.kt"), openNames().toSet())
    }
}
