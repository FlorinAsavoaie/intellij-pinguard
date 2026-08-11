package tech.florin.pinguard

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.ComponentManagerEx
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions
import com.intellij.openapi.fileEditor.impl.FileEditorProviderManagerImpl
import com.intellij.openapi.fileEditor.impl.text.TextEditorCustomizer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.util.coroutines.childScope
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.runInEdtAndWait
import javax.swing.SwingConstants
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * The extension point the platform decorates a freshly built text editor from.
 *
 * Named as a string because the platform keeps its own [ExtensionPointName] for this
 * one out of reach.
 */
private val TEXT_EDITOR_CUSTOMIZERS: ExtensionPointName<TextEditorCustomizer> =
    ExtensionPointName("com.intellij.textEditorCustomizer")

/**
 * A real project with a real [FileEditorManagerImpl], for the tests that cannot be
 * written against fakes.
 *
 * The headless test environment installs [com.intellij.openapi.fileEditor.impl.TestEditorManagerImpl],
 * whose `getWindows()` is hardcoded to return an empty array — so every pin lookup
 * through it reports nothing pinned and a test built on it would pass without
 * exercising anything.
 */
@TestApplication
@TestFixtures
internal abstract class RealEditorTestCase {

    private val projectFixture = projectFixture(openAfterCreation = true)
    private val sourceRootFixture = projectFixture.moduleFixture("main").sourceRootFixture()

    protected val project: Project get() = projectFixture.get()

    private lateinit var scope: CoroutineScope
    private lateinit var serviceDisposable: Disposable

    /** The production manager, standing in for the headless stub. */
    protected lateinit var manager: FileEditorManagerImpl
        private set

    @BeforeEach
    fun installRealEditorManager() {
        runInEdtAndWait {
            project.putUserData(FileEditorManagerKeys.ALLOW_IN_LIGHT_PROJECT, true)
            scope = (project as ComponentManagerEx).getCoroutineScope().childScope("pinguard tests")
            serviceDisposable = Disposer.newDisposable("pinguard test editor manager")
            // Before the manager exists, so no editor is ever built with them: a
            // customizer runs from a coroutine on the project's own scope, which is
            // neither `scope` nor anything else this test can cancel. The one that
            // matters is `CodeFloatingToolbar`, which registers itself under the
            // `TextEditor` and holds that editor's project — so one arriving after
            // the teardown has walked past its editor strands the project and fails
            // the whole run in `LeakHunter`.
            ExtensionTestUtil.maskExtensions(TEXT_EDITOR_CUSTOMIZERS, emptyList(), serviceDisposable)
            manager = FileEditorManagerImpl(project, scope)
            project.replaceService(FileEditorManager::class.java, manager, serviceDisposable)
        }
    }

    /**
     * Mirrors `FileEditorManagerTestCase.tearDown`. Leaving any of this out fails
     * the run in [com.intellij.testFramework.LeakHunter] rather than in the test,
     * which is a considerably harder thing to read.
     *
     * [PinGuardSettings] is reset for a different reason: it is an application
     * service shared by every test in the JVM, so one that leaves it switched off
     * silently disarms the next.
     */
    @AfterEach
    fun closeEverything() {
        runInEdtAndWait {
            // Every window explicitly, not just closeAllFiles: a file open in two
            // splits keeps its composite alive until the last window lets go, and the
            // fixture then deletes the file out from under it.
            //
            // The list is copied before iterating because closing writes back into it
            // — the same hazard the platform's own CloseAllEditorsAction has.
            manager.windows.forEach { window ->
                window.fileList.toList().forEach { window.closeFile(it) }
            }
            manager.unsplitAllWindow()
            manager.closeAllFiles()
            EditorHistoryManager.getInstance(project).removeAllFiles()
            (FileEditorProviderManager.getInstance() as FileEditorProviderManagerImpl).clearSelectedProviders()
            // Installed by hand rather than owned by the container, so nothing else
            // will ever dispose it — and an undisposed one keeps its editors, and
            // through them the whole project, alive past the end of the run.
            Disposer.dispose(manager)
            Disposer.dispose(serviceDisposable)
        }

        // Joining rather than only asking: `cancel()` requests cancellation and
        // returns, leaving this test's coroutines running with the project in their
        // context. Bounded, so a coroutine that never completes fails this test
        // rather than hanging the build with no indication of which test stopped.
        runBlocking {
            withTimeoutOrNull(30.seconds) { scope.coroutineContext.job.cancelAndJoin() }
                ?: error("the test scope did not finish cancelling; something is still holding the project")
        }

        // After the disposal has returned, not inside it: closing an editor and
        // cancelling a scope both queue work with invokeLater, and draining any
        // earlier is draining before that work has been queued.
        drainTheEventQueue()

        PinGuardSettings.getInstance().config = PinGuardState()
    }

    /**
     * Runs everything queued on the EDT, and whatever that queues in turn, so that
     * nothing outlives the test.
     */
    protected fun drainTheEventQueue() {
        // First, and off the EDT: the runnables that strand a project are queued onto
        // the EDT *by* background tasks needing the write-intent lock, so a drain that
        // runs while one is still going only empties the queue in front of the next
        // enqueue. Left as drains alone this leaked a `ProjectImpl` two runs in three.
        // It is also the most expensive thing here, so the cheap drains do the
        // repeating.
        PlatformTestUtil.waitForAllBackgroundActivityToCalmDown()

        repeat(3) {
            // Both on the EDT: waitForAsyncTaskCompletion asserts it is there, because
            // pumping the queue while it waits is how it lets those tasks finish.
            runInEdtAndWait {
                NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
        }
    }

    /** Runs [body] with the plugin configured differently, then puts it back. */
    protected fun withConfig(config: PinGuardState, body: () -> Unit) {
        val settings = PinGuardSettings.getInstance()
        val previous = settings.config
        settings.config = config
        try {
            body()
        } finally {
            settings.config = previous
        }
    }

    protected fun inEdt(body: () -> Unit): Unit = runInEdtAndWait(body)

    /** A file on disk in the project's source root, not yet open in any editor. */
    protected fun file(name: String, content: String = "// $name"): VirtualFile =
        runInEdtAndGetValue {
            runWriteAction {
                val directory = sourceRootFixture.get().virtualFile
                val created = directory.findChild(name) ?: directory.createChildData(this, name)
                created.setBinaryContent(content.toByteArray())
                created
            }
        }

    /** Opens [name] in the main editor window and returns it. */
    protected fun open(name: String): VirtualFile = file(name).also { file ->
        runInEdtAndWait { manager.openFile(file, true) }
    }

    /**
     * Opens [name] in [window] and nowhere else, which is how a file comes to be
     * absent from one split.
     *
     * Opening everywhere and closing again does not reach the same state: each split
     * holds its own [com.intellij.openapi.fileEditor.impl.EditorComposite], and
     * closing one while another window still shows the file leaves that composite
     * undisposed and the project reachable from it.
     */
    protected fun openIn(window: EditorWindow, name: String): VirtualFile = file(name).also { file ->
        runInEdtAndWait { manager.openFile(file, window, FileEditorOpenOptions()) }
    }

    protected fun pin(file: VirtualFile, window: EditorWindow = mainWindow()) {
        runInEdtAndWait { window.setFilePinned(file, true) }
    }

    protected fun mainWindow(): EditorWindow = runInEdtAndGetValue { manager.windows.first() }

    protected fun windows(): List<EditorWindow> = runInEdtAndGetValue { manager.windows.toList() }

    /** Splits the editor area, so a file can be open in one side and not the other. */
    protected fun split(from: EditorWindow = mainWindow()): List<EditorWindow> {
        inEdt { manager.createSplitter(SwingConstants.VERTICAL, from) }
        return windows()
    }

    protected fun openFiles(): List<VirtualFile> = runInEdtAndGetValue { manager.openFiles.toList() }
}

/** [runInEdtAndWait] with a result, without the `ThrowableComputable` ceremony. */
internal fun <T> runInEdtAndGetValue(body: () -> T): T {
    var result: T? = null
    runInEdtAndWait { result = body() }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
