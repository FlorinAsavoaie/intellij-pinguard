import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// The jar has to be named after the content module it carries: the platform
// resolves `<content><module name="X"/></content>` by loading `lib/modules/X.jar`
// and reading `X.xml` out of it, so a jar under any other name is never opened and
// the module fails with "Cannot resolve X.xml". Gradle's default would be
// `pinguard.frontend`, which is close enough to look right and still wrong.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask>("composedJar") {
    archiveBaseName = "tech.florin.pinguard.frontend"
}

dependencies {
    intellijPlatform {
        // Declared here rather than left to the root project. Without it, resolving
        // this module's classpath reaches sideways into the root's
        // `:intellijPlatformDependency`, which Gradle 9 refuses without an exclusive
        // lock that `org.gradle.parallel` means it does not hold — and it fails
        // quietly: only the per-module resolution the IDE runs at sync time trips it,
        // reporting success while handing the IDE an empty classpath for `frontend`.
        intellijIdea(providers.gradleProperty("platformVersion"))

        // The dependency that decides where this module runs. The platform loads a
        // content module only where its dependencies resolve, and
        // `intellij.platform.frontend` resolves in exactly two places: a monolithic
        // IDE, and the JetBrains Client that renders the editor in a remote
        // development or Code With Me session. Both own real editor windows.
        bundledModule("intellij.platform.frontend")

        // `Terminal.CloseTab` is the one id in `GUARDED` belonging to a bundled
        // plugin rather than to the platform, and the tests that hold the platform to
        // what it does need it registered to say anything at all.
        //
        // `testBundledPlugin` rather than `bundledPlugin`: PinGuard names the action
        // by id and links against nothing in the terminal, so the production compile
        // classpath would be declaring a dependency the plugin does not have.
        testBundledPlugin("org.jetbrains.plugins.terminal")

        // Brings in com.intellij.testFramework, so a test can start a real
        // application and open a real project.
        //
        // Bundled rather than Platform: only the bundled `lib/testFramework.jar`
        // carries `com.intellij.testFramework.junit5`, whose fixtures let these tests
        // run on the same engine as everything else — `BasePlatformTestCase` is JUnit
        // 3 and would need a vintage engine merely to be discovered.
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

// The tests live here, with the code they exercise, because Kotlin visibility is
// per compilation and almost all of PinGuard is `internal`. What the root project
// has that this one does not is `META-INF/plugin.xml`, and a test only gets the
// plugin registered by having that on the classpath — not out of the sandbox, which
// holds this project's own jar, a content module rather than a plugin.
//
// Prepended rather than added as a `testRuntimeOnly` dependency, because being
// *first* is the part that matters: the platform ships its own `META-INF/plugin.xml`
// in `app.jar` and `getResourceAsStream` returns whichever comes first. Anywhere
// behind it and `PluginDescriptorTest` reads `com.intellij`'s descriptor instead.
//
// The patched copy rather than `src/main/resources`, since `patchPluginXml` writes
// the version and the since-build; naming the task also orders it before the tests.
tasks.test {
    classpath = files(rootProject.tasks.named("processResources")) + classpath

    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Keeps bundled plugins that PinGuard does not exercise out of the test IDE.
//
// The test framework fails a test on any error the platform *logs*, and opening real
// files in a real editor makes it enumerate every `LspServerSupportProvider` there
// is. Vue's throws while being constructed on this distribution:
//
//     IllegalStateException: .../plugins/vuejs-plugin/lib/modules should be lib directory
//
// It arrives on a pooled thread, so it lands on whichever test happens to be running
// and takes the suite down with it. Disabling the plugin prevents the error rather
// than swallowing it, which a `LoggedErrorProcessor` filter would do to PinGuard's
// own errors just as readily.
//
// Through the sandbox task rather than by hand into `disabled_plugins.txt`, which
// `prepareTestSandbox` rewrites from this property on every run.
//
// Add another only when it does the same; disabling by guesswork means the suite
// stops exercising the platform it ships against.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareTestSandbox") {
    disabledPlugins.add("org.jetbrains.plugins.vue")
}
