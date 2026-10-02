package dev.ozcan.stress.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The English (default) and Turkish strings must say the same things: the
 * same names, and the same format arguments in each, or a screen crashes or
 * prints a raw "%1$s" in one language only.
 */
class StringsTest {

    private fun strings(path: String): Map<String, String> {
        val file = File(path)
        assertTrue("missing $path (tests run from the app module)", file.exists())
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private val english = strings("src/main/res/values/strings.xml")
    private val turkish = strings("src/main/res/values-tr/strings.xml")

    /** Positional arguments ("%1$s", "%2$d"), ignoring escaped percent signs. */
    private fun arguments(text: String): List<String> =
        Regex("%(\\d+)\\$([sd])").findAll(text.replace("%%", "")).map { it.value }.sorted().toList()

    @Test
    fun `both languages have the same strings`() {
        assertEquals(english.keys.sorted(), turkish.keys.sorted())
    }

    @Test
    fun `every string takes the same arguments in both languages`() {
        for ((name, text) in english) {
            assertEquals(name, arguments(text), arguments(turkish.getValue(name)))
        }
    }

    @Test
    fun `a percent sign in a formatted string is escaped`() {
        for ((name, text) in english + turkish.mapKeys { "tr:${it.key}" }) {
            if (arguments(text).isEmpty()) continue
            val stray = text.replace("%%", "").replace(Regex("%\\d+\\$[sd]"), "")
            assertTrue("$name has a lone %: $text", '%' !in stray)
        }
    }

    @Test
    fun `the code names only strings that exist`() {
        val used = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { Regex("R\\.string\\.([a-z0-9_]+)").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()
        val missing = used - english.keys
        assertTrue("missing: $missing", missing.isEmpty())
    }
}
