import org.jetbrains.intellij.platform.gradle.TestFrameworkType

/**
 * The jar has to be named after the content module it carries.
 *
 * The platform resolves `<content><module name="X"/></content>` by loading
 * `lib/modules/X.jar` and reading `X.xml` out of it; the module jars are not part
 * of the plugin's own descriptor classpath, so a jar under any other name is
 * never opened and the module fails to load with "Cannot resolve X.xml". Gradle's
 * default here would be `<rootProject.name>.<project.path>` — `pinguard.frontend`
 * — which is close enough to look right and still wrong.
 */
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask>("composedJar") {
    archiveBaseName = "tech.florin.pinguard.frontend"
}

dependencies {
    intellijPlatform {
        // Declared here rather than left to the root project. Without it,
        // resolving this module's classpath reaches sideways into the root's
        // `:intellijPlatformDependency`, and Gradle 9 refuses cross-project
        // resolution without an exclusive lock — which `org.gradle.parallel` in
        // gradle.properties means it does not hold.
        //
        // Nothing about that fails loudly. `./gradlew build` resolves in a
        // context that does hold the lock, so only the per-module resolution the
        // IDE runs at sync time trips it, and the sync then reports success
        // while handing the IDE a `frontend` module with an empty classpath.
        // What that looks like is every reference in this source set unresolved
        // — `com.intellij`, and, because `kotlin.stdlib.default.dependency =
        // false` puts the stdlib on the platform too, `listOf` beside it.
        intellijIdea(providers.gradleProperty("platformVersion"))

        // The dependency that decides where this module runs. The platform loads
        // a content module only where its dependencies resolve, and
        // `intellij.platform.frontend` resolves in exactly two places: a plain
        // monolithic IDE, and the JetBrains Client that renders the editor in a
        // remote development or Code With Me session. Both are processes that own
        // real editor windows — which is the whole point.
        bundledModule("intellij.platform.frontend")

        // Brings in com.intellij.testFramework, so a test can start a real
        // application and open a real project.
        //
        // Bundled rather than Platform: the two resolve to different jars, and
        // only the bundled `lib/testFramework.jar` carries
        // `com.intellij.testFramework.junit5`. Those JUnit 5 fixtures are what
        // let the platform tests here run on the same engine as everything else
        // — `BasePlatformTestCase` is JUnit 3, and under `useJUnitPlatform()`
        // would need a vintage engine merely to be discovered.
        testFramework(TestFrameworkType.Bundled)
    }

    testImplementation(kotlin("test"))
    // 5.12 or newer: the 2025.3 test framework's fixture extension calls
    // ExtensionContext.getEnclosingTestClasses(), which older Jupiter releases do
    // not declare — every fixture-based test then dies in beforeAll with a
    // NoSuchMethodError rather than failing an assertion.
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("junit:junit:4.13.2")
}

/**
 * The plugin's identity, which lives in the root project, put where the tests can
 * read it.
 *
 * The tests are here, with the code they exercise, because that is what makes
 * Kotlin's `internal` visible to them: visibility is per compilation, and almost
 * all of PinGuard is internal. Test-compiling them in the root project instead
 * needs `-Xfriend-paths` pointing at this project's jar — which works, and which
 * the IDE has no way to know about, so every `internal` reference in the suite
 * reads as an error in the editor while Gradle compiles it clean.
 *
 * What the root project has that this one does not is `META-INF/plugin.xml`, and
 * a test gets the plugin registered by having that on the classpath. Not out of
 * the sandbox: `prepareTestSandbox` here installs this project's own jar, which is
 * a content module and not a plugin at all. Without the descriptor,
 * `PinnedTabCloseGuardPlatformTest` finds PinGuard on no extension point.
 *
 * Prepended, rather than added as a `testRuntimeOnly` dependency, because being
 * *first* is the part that matters. The platform ships its own
 * `META-INF/plugin.xml` in `app.jar`, `getResourceAsStream` returns whichever
 * comes first, and this task's classpath is not the test source set's: the
 * IntelliJ Platform Gradle plugin rebuilds it with the sandbox and the platform
 * in front and the source set's own output behind them — which is how the root
 * project used to win this without anyone arranging it, its sandbox holding a
 * composed jar with the descriptor inside. Anywhere behind `app.jar` and every
 * assertion in `PluginDescriptorTest` reads `com.intellij`'s descriptor instead.
 *
 * The patched copy rather than `src/main/resources`: `patchPluginXml` writes the
 * version and the since-build, so its output is the descriptor that ships. Naming
 * the task is also what orders it before this project's tests.
 */
tasks.test {
    classpath = files(rootProject.tasks.named("processResources")) + classpath

    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
