package tech.florin.pinguard

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.ComponentManagerEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions
import com.intellij.openapi.fileEditor.impl.FileEditorProviderManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.util.coroutines.childScope
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
 * A real project with a real [FileEditorManagerImpl], for the tests that cannot
 * be written against fakes.
 *
 * The headless test environment installs [com.intellij.openapi.fileEditor.impl.TestEditorManagerImpl],
 * whose `getWindows()` is hardcoded to return an empty array — so every pin
 * lookup through it reports nothing pinned and a test built on it would pass
 * without exercising anything. Swapping in the production implementation, the
 * way the platform's own `FileEditorManagerTestCase` does, is what makes
 * [PlatformPinnedFiles] and the guarded close actions testable at all.
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
            manager = FileEditorManagerImpl(project, scope)
            project.replaceService(FileEditorManager::class.java, manager, serviceDisposable)
        }
    }

    /**
     * Mirrors `FileEditorManagerTestCase.tearDown`. Leaving any of this out fails
     * the run in [com.intellij.testFramework.LeakHunter] rather than in the test,
     * which is a considerably harder thing to read.
     *
     * The settings reset is here for a different reason: [PinGuardSettings] is an
     * application service, so it is one object shared by every test in the JVM, and
     * a test that leaves it switched off silently disarms the next one.
     */
    @AfterEach
    fun closeEverything() {
        runInEdtAndWait {
            // Every window explicitly, not just closeAllFiles: a file open in two
            // splits keeps its composite alive until the last window lets go, and
            // the fixture then deletes the file out from under it.
            //
            // The list is copied before iterating because closing writes back into
            // it — the same hazard the platform's own CloseAllEditorsAction has.
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

        // Off the EDT, and joining rather than only asking: `cancel()` *requests*
        // cancellation and returns, so on its own it leaves this test's coroutines
        // still running — and every one of them carries the project in its context.
        // Joining is what makes "cancelled" mean "finished". Bounded, because a
        // coroutine that never completes should fail this test rather than hang the
        // build with no indication of which test stopped.
        runBlocking {
            withTimeoutOrNull(30.seconds) { scope.coroutineContext.job.cancelAndJoin() }
                ?: error("the test scope did not finish cancelling; something is still holding the project")
        }

        // Separately, and after the disposal has returned: closing an editor and
        // cancelling a scope both queue work with invokeLater, and a runnable still
        // sitting in the queue holds the project through its coroutine context.
        // Draining from inside the block above is too early — the work being waited
        // on has not been queued yet.
        drainTheEventQueue()

        PinGuardSettings.getInstance().config = PinGuardState()
    }

    /**
     * Runs everything queued on the EDT, and whatever that queues in turn, so
     * that nothing outlives the test.
     *
     * Waiting comes before draining, rather than the drain being repeated on its
     * own. The runnables that keep a disposed project alive are queued onto the EDT
     * *by* background tasks, so a drain that runs while one of those is still going
     * only empties the queue in front of the next enqueue — which is how a fixed
     * number of drains comes to pass or fail depending on timing. Left as three
     * drains alone this leaked a `ProjectImpl` on roughly two runs in three.
     */
    protected fun drainTheEventQueue() {
        // Once, and first. Off the EDT, because it waits on the other threads
        // rather than on this one, and it is what covers the work the queue drain
        // cannot reach: the runnable that strands a project is queued by a
        // background task needing the write-intent lock, which a plain drain leaves
        // where it is. It is also by far the most expensive thing in this teardown,
        // so it runs once and the cheap drains do the repeating.
        PlatformTestUtil.waitForAllBackgroundActivityToCalmDown()

        repeat(3) {
            // Both on the EDT: waitForAsyncTaskCompletion asserts it is there,
            // because pumping the queue while it waits is how it lets the tasks it
            // is waiting for finish.
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
     * Reaching the same state by opening everywhere and closing again does not
     * work: each split holds its own
     * [com.intellij.openapi.fileEditor.impl.EditorComposite], and closing one while
     * another window still shows the file leaves that composite undisposed and the
     * project reachable from it.
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
