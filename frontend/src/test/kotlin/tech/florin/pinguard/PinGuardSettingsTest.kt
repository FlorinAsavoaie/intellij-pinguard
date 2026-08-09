package tech.florin.pinguard

import com.intellij.openapi.components.State
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jdom.Element
import org.junit.jupiter.api.Test

/**
 * The settings object and the XML the platform actually writes for it.
 *
 * Handing the object returned by `getState()` straight back to `loadState()` proves
 * nothing about persistence, since no serializer runs. Everything below the first
 * two tests goes through the platform's own serializer, which needs no application.
 */
class PinGuardSettingsTest {

    /** The filter the component store itself uses when writing `pinguard.xml`. */
    private val storeFilter = SkipDefaultsSerializationFilter()

    private fun persist(config: PinGuardState): Element =
        XmlSerializer.serialize(PinGuardSettings().apply { this.config = config }.state, storeFilter)

    private fun restore(element: Element): PinGuardState =
        PinGuardSettings().apply { loadState(XmlSerializer.deserialize(element, PinGuardState::class.java)) }.config

    @Test
    fun `a fresh install guards pinned tabs`() {
        assertEquals(PinGuardState(), PinGuardSettings().config)
    }

    @Test
    fun `the shipped default is to guard silently rather than prompt`() {
        // Spelled out rather than left to `PinGuardState()`, which the test above
        // would still pass with both values flipped.
        assertEquals(true, PinGuardState().enabled)
        assertEquals(false, PinGuardState().confirmInsteadOfBlock)
    }

    @Test
    fun `the stored state cannot be changed by whoever last read it`() {
        val settings = PinGuardSettings()
        val read = settings.config

        read.enabled = false

        assertEquals(true, settings.config.enabled, "a read-only caller changed the live settings")
    }

    @Test
    fun `state restored from disk becomes the active config`() {
        val settings = PinGuardSettings()

        settings.loadState(PinGuardState(enabled = false, confirmInsteadOfBlock = true))

        assertEquals(
            PinGuardState(enabled = false, confirmInsteadOfBlock = true),
            settings.config,
        )
    }

    @Test
    fun `every config combination survives the platform's own serializer`() {
        for (enabled in listOf(true, false)) {
            for (confirm in listOf(true, false)) {
                val original = PinGuardState(enabled, confirm)

                val reloaded = restore(persist(original))

                assertEquals(original, reloaded, "lost $original through pinguard.xml")
            }
        }
    }

    @Test
    fun `choosing today's default writes a file indistinguishable from never having chosen`() {
        // The store omits any value equal to a freshly constructed state, so
        // PinGuardState() is re-applied to every existing install on each load rather
        // than only starting off new ones: changing it silently reverses the settings
        // of everyone who is on it.
        val chose = persist(PinGuardState())
        val neverOpenedTheSettings = Element("PinGuardState")

        assertEquals(JDOMUtil.write(neverOpenedTheSettings), JDOMUtil.write(chose))
        assertEquals(PinGuardState(), restore(neverOpenedTheSettings))
    }

    @Test
    fun `a non-default choice is written out rather than left to the default`() {
        val written = JDOMUtil.write(persist(PinGuardState(enabled = false, confirmInsteadOfBlock = true)))

        assertTrue(written.contains("""name="enabled""""), "a suppressed 'enabled' cannot survive a default change")
        assertTrue(written.contains("""name="confirmInsteadOfBlock""""))
        assertTrue(written.contains("""value="false""""))
    }

    @Test
    fun `the state class round-trips through the serializer under its own name`() {
        // The element name is derived from the class name, so renaming PinGuardState
        // without a migration orphans every existing pinguard.xml.
        assertEquals("PinGuardState", persist(PinGuardState(enabled = false, confirmInsteadOfBlock = true)).name)
    }

    @Test
    fun `the component keeps the name and the file the shipped settings are already stored under`() {
        // Both strings are where the user's existing choice lives, so changing
        // either is a silent reset for everyone who has one.
        val state = requireNotNull(PinGuardSettings::class.java.getAnnotation(State::class.java))

        assertEquals("PinGuard", state.name)
        assertEquals(listOf("pinguard.xml"), state.storages.map { it.value })
    }
}
