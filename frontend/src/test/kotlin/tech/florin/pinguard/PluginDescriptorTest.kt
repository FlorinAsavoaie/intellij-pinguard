package tech.florin.pinguard

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plugin's two XML descriptors, read off the classpath so no fixture is needed.
 *
 * They name every extension by fully qualified class name and every label by bundle
 * key, in strings no compiler checks: a rename that misses the XML builds clean and
 * ships a plugin whose extensions never load, and a mistyped key ships a button
 * labelled `!key!`.
 *
 * The split between the two is the point. `META-INF/plugin.xml` carries the
 * plugin's identity and nothing else, while everything that does work is registered
 * in the content module. Getting that wrong is silent — a module whose descriptor
 * cannot be found is simply never loaded.
 */
class PluginDescriptorTest {

    /** Kept in step with `<content>` below; also the descriptor's file name. */
    private val moduleName = "tech.florin.pinguard.frontend"

    private fun load(resource: String): Element =
        checkNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
            "$resource is not on the test classpath"
        }.use { JDOMUtil.load(it) }

    private val descriptor: Element = load("META-INF/plugin.xml")

    private val module: Element = load("$moduleName.xml")

    private val bundle: PropertyResourceBundle =
        checkNotNull(javaClass.classLoader.getResourceAsStream("messages/PinGuardBundle.properties")) {
            "the message bundle is not on the test classpath"
        }.use { PropertyResourceBundle(it) }

    private val extensions: List<Element>
        get() = module.getChildren("extensions").flatMap { it.children }

    /** Every extension point and the class the module descriptor names for it. */
    private fun registeredClasses(): List<Pair<String, String>> =
        extensions.mapNotNull { extension ->
            val fqcn = extension.getAttributeValue("implementation")
                ?: extension.getAttributeValue("instance")
                ?: return@mapNotNull null
            extension.name to fqcn
        }

    @Test
    fun `every registered extension names a class that exists`() {
        registeredClasses().forEach { (point, fqcn) ->
            // Class.forName rather than a string comparison: a rename that missed
            // the XML is exactly a name that no longer resolves.
            Class.forName(fqcn)
            assertTrue(fqcn.startsWith("tech.florin.pinguard."), "$point registers something outside the plugin")
        }
    }

    @Test
    fun `every registered extension class is public, so the platform can instantiate it`() {
        registeredClasses().forEach { (point, fqcn) ->
            val type = Class.forName(fqcn)

            assertTrue(
                java.lang.reflect.Modifier.isPublic(type.modifiers),
                "$point registers $fqcn, which the platform cannot construct reflectively unless it is public",
            )
        }
    }

    @Test
    fun `every registered extension class has the no-argument constructor the platform calls`() {
        registeredClasses().forEach { (point, fqcn) ->
            val type = Class.forName(fqcn)

            val constructor = type.declaredConstructors.singleOrNull { it.parameterCount == 0 }
            assertTrue(
                constructor != null && java.lang.reflect.Modifier.isPublic(constructor.modifiers),
                "$point registers $fqcn, which has no public no-arg constructor for the platform to call",
            )
        }
    }

    @Test
    fun `the four extension points the plugin depends on are all still registered`() {
        assertEquals(
            listOf("applicationConfigurable", "errorHandler", "postStartupActivity", "virtualFilePreCloseCheck"),
            extensions.map { it.name }.sorted(),
        )
    }

    @Test
    fun `every bundle key named in the module descriptor exists in the properties file`() {
        val keys = extensions.mapNotNull { it.getAttributeValue("key") }

        assertTrue(keys.isNotEmpty(), "the configurable's title is declared by key; this test guards nothing if empty")
        keys.forEach { key ->
            assertTrue(bundle.containsKey(key), "$moduleName refers to '$key', which the bundle does not define")
        }
    }

    @Test
    fun `every bundle key the code asks for exists in the properties file`() {
        val used = listOf(
            "confirm.title",
            "confirm.title.plural",
            "confirm.message.single",
            "confirm.message.multiple",
            "confirm.yes",
            "confirm.no",
            "settings.title",
            "settings.enabled",
            "settings.confirm",
            "settings.confirm.comment",
            "settings.group.comment",
            "confirm.message.item",
            "confirm.message.more",
            "error.report.action",
            "error.report.link",
            "error.privacy.notice",
        )

        used.forEach { key ->
            assertTrue(bundle.containsKey(key), "the code asks for '$key', which the bundle does not define")
        }
    }

    @Test
    fun `the bundle carries nothing the plugin no longer uses`() {
        val declared = bundle.keys.toList().map { it as String }.toSet()
        val referenced = setOf(
            "confirm.title", "confirm.title.plural", "confirm.message.single", "confirm.message.multiple",
            "confirm.message.item", "confirm.message.more", "confirm.yes", "confirm.no",
            "settings.title", "settings.enabled", "settings.confirm", "settings.confirm.comment",
            "settings.group.comment",
            "error.report.action", "error.report.link", "error.privacy.notice",
        )

        assertEquals(emptySet(), declared - referenced, "these keys are shipped and translated but never read")
    }

    @Test
    fun `the resource bundle the module declares is the one the code loads`() {
        assertEquals("messages.PinGuardBundle", module.getChildText("resource-bundle"))
    }

    @Test
    fun `the module plugin dot xml names is the one whose descriptor is on the classpath`() {
        // Renaming one file and not the other means the module is never found: no
        // error, no extensions, a plugin that does nothing.
        val declared = descriptor.getChild("content").getChildren("module").map { it.getAttributeValue("name") }

        assertEquals(listOf(moduleName), declared)
    }

    @Test
    fun `the module loads optionally rather than required`() {
        // `loading="required"` fails the whole plugin wherever the module's
        // dependencies do not resolve, and on a remote development backend they never
        // do — the backend would refuse PinGuard outright, and a plugin the host will
        // not load is one the client is never served. Absence is the assertion: the
        // platform's default is optional.
        val module = descriptor.getChild("content").getChildren("module").single()

        assertNull(module.getAttributeValue("loading"), "the module is required again, which breaks split mode")
    }

    @Test
    fun `the module asks for the frontend, which is what decides where it loads`() {
        // Losing this dependency would load the guards on a remote development
        // backend, which dispatches none of the close actions they wrap.
        val dependencies = module.getChild("dependencies").getChildren("module").map { it.getAttributeValue("name") }

        assertTrue("intellij.platform.frontend" in dependencies, "the module no longer asks for the frontend")
    }

    @Test
    fun `plugin dot xml itself registers nothing`() {
        // Anything registered here would load on whichever process the plugin is
        // installed on, backend included, rather than where the tabs are.
        assertEquals(emptyList(), descriptor.getChildren("extensions"))
    }

    @Test
    fun `the plugin id the error reporter falls back to is the one the descriptor declares`() {
        // PinGuardErrorSubmitter hardcodes this for the case where the platform
        // never injected a descriptor, and the two drifting apart mislabels reports.
        assertEquals("tech.florin.pinguard", descriptor.getChildText("id"))
    }
}
