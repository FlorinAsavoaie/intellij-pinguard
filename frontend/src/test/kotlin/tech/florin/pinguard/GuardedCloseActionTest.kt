package tech.florin.pinguard

import com.intellij.ide.actions.CloseAction
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.fileEditor.impl.EditorComposite
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.tabs.impl.JBTabsImpl
import javax.swing.JPopupMenu
import javax.swing.event.PopupMenuEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The close actions users actually reach, across the three [CloseActionScope]
 * implementations [GUARDED] names.
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
     * untouched", which is what these record. Asserting on the real action's side
     * effects instead would be asserting the platform's: `CloseAllEditorsAction`
     * iterates `window.getFileList()` while closing into it, so what survives
     * depends on how that live list shifts underneath the iterator.
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
        // never in this close's way: refusing on account of it produces a Cmd+W that
        // silently does nothing.
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
        // The platform contract the test above rests on, read by running it: the one
        // call the in-window branch of `CloseAllEditorsButActiveAction` makes. The
        // day it stops skipping pins, OtherTabsScope must stop standing aside there.
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
     * Reflection on a non-public platform method, which is why it lives in a test
     * rather than in the plugin: here it fails loudly the day it stops resolving.
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
        // If this fails, those four ids belong back in GUARDED and the reflection
        // above belongs back in CloseActionScope as a scope of its own.
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
        // Every other test here supplies a window, so nothing else runs this branch.
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
        // The branch that actually needs guarding, reached from Window | Editor Tabs
        // | Close Others with the focus in a tool window.
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
        // The mirror of the closeAllExcept test above. Without it, the test above
        // would pass just as well if the platform protected pins on both branches
        // and the guard were dead weight. If this starts failing, OtherTabsScope can
        // go entirely.
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
    fun `confirming the close closes the pinned tab too, here rather than in the platform's action`() {
        val pinned = open("Pinned.kt")
        open("A.kt")
        pin(pinned)

        val (original, action) = guardedStub(
            AllTabsScope,
            gate(PinGuardState(enabled = true, confirmInsteadOfBlock = true), answer = true),
        )
        inEdt { action.actionPerformed(event(window = mainWindow())) }

        assertEquals(
            0,
            original.invocations,
            "asking took a dialog, so the original's event no longer describes the editor it would read",
        )
        assertEquals(emptySet(), openNames().toSet(), "the user said yes to all of it, pin included")
    }

    /**
     * The wrapper PinGuard installs over `CloseContent`, over a platform action of this
     * test's own.
     *
     * The plugin is really loaded in this sandbox, so [ActionManager] already answers
     * that id with a guard, and wrapping that would ask the user twice about one close.
     */
    private fun guardedCloseContent(gate: PinnedCloseGate): GuardedCloseAction =
        GuardedLightEditCloseAction(CloseAction(), SingleTabScope, PlatformPinnedFiles.selector(), gate)

    /**
     * Runs [body] where a close chosen from [file]'s tab context menu really runs: with
     * the menu just down, and the editor still remembering which tab it was on.
     *
     * That memory is `JBTabsImpl.popupInfo`, which is what `CloseContent` closes and
     * which the platform drops from an `invokeLater` queued as the menu goes. It
     * therefore outlives the gesture by one turn of the event queue, and nothing here
     * may span more than the single [inEdt] an IDE would have.
     */
    private fun <T> fromTheTabMenuOn(
        file: VirtualFile,
        window: EditorWindow = mainWindow(),
        body: (JBTabsImpl) -> T,
    ): T = runInEdtAndGetValue {
        val tabs = window.tabbedPane.tabs as JBTabsImpl
        tabs.popupInfo = requireNotNull(tabs.tabs.firstOrNull { it.`object` == file }) {
            "${file.name} has no tab in this window, so there is nothing to right-click"
        }
        tabs.popupMenuWillBecomeInvisible(PopupMenuEvent(JPopupMenu()))
        body(tabs)
    }

    /**
     * What a tab context menu hands a close action: the right-clicked file, which a
     * `TabLabel` answers with rather than the selected one, and the tabs component as
     * the thing to close.
     */
    private fun tabMenuEvent(file: VirtualFile, tabs: JBTabsImpl, window: EditorWindow = mainWindow()): AnActionEvent {
        val closeTarget = requireNotNull(tabs as? CloseAction.CloseTarget) {
            "the editor's tabs are no longer what CloseContent closes; this is not the gesture it used to be"
        }
        val context: DataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .add(EditorWindow.DATA_KEY, window)
            .add(CloseAction.CloseTarget.KEY, closeTarget)
            .build()

        return AnActionEvent.createEvent(
            context,
            Presentation(),
            ActionPlaces.EDITOR_TAB_POPUP,
            ActionUiKind.POPUP,
            null,
        )
    }

    /** A pinned tab, and an unpinned one selected in front of it. */
    private fun pinnedTabBehindTheActiveOne(): VirtualFile = open("Pinned.kt").also {
        pin(it)
        // Opened last, so it is the selected tab — the one a close falls back to.
        open("Active.kt")
    }

    @Test
    fun `confirming a close from the tab menu closes the tab the menu was on, not the tab in front of you`() {
        val pinned = pinnedTabBehindTheActiveOne()

        var asked = 0
        val confirming = PinnedCloseGate(
            configProvider = { PinGuardState(enabled = true, confirmInsteadOfBlock = true) },
            prompt = {
                asked++
                // Standing in for the real dialog, which is modal and so pumps the queue
                // the platform left that runnable in.
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                true
            },
        )

        fromTheTabMenuOn(pinned) { tabs ->
            guardedCloseContent(confirming).actionPerformed(tabMenuEvent(pinned, tabs))
        }

        assertEquals(1, asked, "the close never reached the confirmation, so this proves nothing about answering it")
        assertEquals(
            setOf("Active.kt"),
            openNames().toSet(),
            "the user right-clicked Pinned.kt and confirmed closing it; Active.kt was never part of that close",
        )
    }

    @Test
    fun `the same close with nothing to ask about closes the tab the menu was on`() {
        // The control for the test above. If this one ever fails, what is wrong is the
        // way these two set the gesture up rather than the plugin.
        val pinned = pinnedTabBehindTheActiveOne()

        fromTheTabMenuOn(pinned) { tabs ->
            guardedCloseContent(gate(PinGuardState(enabled = false, confirmInsteadOfBlock = false)))
                .actionPerformed(tabMenuEvent(pinned, tabs))
        }

        assertEquals(
            setOf("Active.kt"),
            openNames().toSet(),
            "switched off, PinGuard hands this straight to the platform, which closes the tab it was aimed at",
        )
    }

    @Test
    fun `stopping to ask is what loses the tab a menu close was aimed at`() {
        // The platform contract the two tests above rest on, read by running it. The day
        // the aim stops being lost this way, a confirmed close can go back to the
        // platform untouched and those two are guarding nothing.
        val pinned = pinnedTabBehindTheActiveOne()

        val (whenTheMenuClosed, afterTheDialog) = fromTheTabMenuOn(pinned) { tabs ->
            val aimedAt = tabs.targetInfo?.`object`
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            aimedAt to tabs.targetInfo?.`object`
        }

        assertEquals(
            pinned,
            whenTheMenuClosed,
            "precondition: with the menu just down, the close is still aimed at the tab it was on",
        )
        assertEquals(
            "Active.kt",
            (afterTheDialog as? VirtualFile)?.name,
            "one turn of the event queue, and a menu close is aimed at the selected tab instead",
        )
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
        // An event with no VIRTUAL_FILE is how tool windows and run consoles reach
        // CloseContent.
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
    fun `a prompt that throws keeps the pinned tab rather than handing the close back`() {
        // The one failure PinGuard cannot answer by standing aside: a prompt that throws
        // may have had its dialog up already, and the event cannot be handed back then.
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

        assertEquals(0, original.invocations, "handing back an event a dialog may have overtaken is the bug, not the fallback")
        assertEquals(
            setOf("Pinned.kt"),
            openNames().toSet(),
            "a guard that cannot ask keeps the pin, and still closes everything the user did mean to close",
        )
    }

    @Test
    fun `a close aimed at a tool window is not PinGuard's, whatever file it happens to name`() {
        // Cmd+W with the focus in the Project view hides that tool window, and the
        // context it carries names the file selected in the tree — which may be pinned
        // in the editor, and was never in this close's way.
        val pinned = open("Pinned.kt")
        pin(pinned)

        var asked = 0
        val counting = PinnedCloseGate(
            configProvider = { PinGuardState(enabled = true, confirmInsteadOfBlock = true) },
            prompt = {
                asked++
                true
            },
        )

        val (original, action) = guardedStub(SingleTabScope, counting)
        inEdt { action.actionPerformed(event(file = pinned, window = null)) }

        assertEquals(0, asked, "this close was never aimed at an editor tab, so there was nothing to ask about")
        assertEquals(1, original.invocations, "and it belongs to the platform, which has a tool window to hide")
        assertEquals(setOf("Pinned.kt"), openNames().toSet(), "the pinned tab is untouched either way")
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
        val original = requireNotNull(ActionManager.getInstance().getAction("CloseAllEditors"))
        val action = GuardedCloseAction(original, AllTabsScope, PlatformPinnedFiles.selector(), gate())

        assertEquals(original.actionUpdateThread, action.actionUpdateThread)
    }

    @Test
    fun `a guarded Terminal_CloseTab keeps a pinned terminal tab open`() {
        // A terminal in the editor is an ordinary tab over a light virtual file, so
        // a plain file stands in for one: what is exercised is the action, not the
        // terminal.
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
        // editor tabs and carry no virtual file.
        val pinned = open("Pinned.kt")
        pin(pinned)

        val (original, action) = guardedStub(terminalScope())
        inEdt { action.actionPerformed(event(file = null, window = null)) }

        assertEquals(1, original.invocations, "a close with no virtual file is not PinGuard's to intercept")
        assertEquals(setOf("Pinned.kt"), openNames().toSet())
    }
}
