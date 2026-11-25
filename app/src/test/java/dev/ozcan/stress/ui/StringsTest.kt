package dev.ozcan.stress.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The English (default) and Turkish strings must say the same things: the
 * same names, and the same format arguments in each, or a screen crashes or
 * prints a raw "%1$s" in one language only.
 */
class StringsTest {

    private fun resources(directory: String, tag: String): List<Node> {
        val files = File(directory).listFiles { file -> file.name.startsWith("strings") && file.extension == "xml" }.orEmpty()
        assertTrue("missing resources in $directory (tests run from the app module)", files.isNotEmpty())
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return files.flatMap { file ->
            val nodes = builder.parse(file).getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) }
        }
    }

    private fun strings(directory: String): Map<String, String> =
        resources(directory, "string").associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }

    /** Each plural's items, by quantity ("one", "other"). */
    private fun plurals(directory: String): Map<String, Map<String, String>> =
        resources(directory, "plurals").associate { node ->
            val items = node.childNodes
            node.attributes.getNamedItem("name").nodeValue to (0 until items.length)
                .map { items.item(it) }
                .filter { it.nodeName == "item" }
                .associate { it.attributes.getNamedItem("quantity").nodeValue to it.textContent }
        }

    private val english = strings("src/main/res/values")
    private val turkish = strings("src/main/res/values-tr")
    private val englishPlurals = plurals("src/main/res/values")
    private val turkishPlurals = plurals("src/main/res/values-tr")

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
    fun `both languages have the same plurals, every item with the same arguments`() {
        assertEquals(englishPlurals.keys.sorted(), turkishPlurals.keys.sorted())
        for ((name, items) in englishPlurals) {
            val expected = arguments(items.getValue("other"))
            for ((language, plurals) in listOf("en" to englishPlurals, "tr" to turkishPlurals)) {
                for ((quantity, text) in plurals.getValue(name)) {
                    assertEquals("$language $name/$quantity", expected, arguments(text))
                }
            }
        }
    }

    @Test
    fun `the code names only plurals that exist`() {
        val used = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { Regex("R\\.plurals\\.([a-z0-9_]+)").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()
        val missing = used - englishPlurals.keys
        assertTrue("missing: $missing", missing.isEmpty())
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
