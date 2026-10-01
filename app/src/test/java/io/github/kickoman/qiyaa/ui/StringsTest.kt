package io.github.kickoman.qiyaa.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The interface texts in res/values (English), values-ru and values-be: every translatable text in
 * every language with the source's placeholders and each language's plural forms, and the English
 * texts saying "vibe" for Yandex's "волна", never "wave".
 */
class StringsTest {
    private data class Texts(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun read(folder: String): Texts {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/strings.xml"))
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        val stringNodes = document.getElementsByTagName("string")
        for (i in 0 until stringNodes.length) {
            val element = stringNodes.item(i) as Element
            if (element.getAttribute("translatable") != "false") {
                strings[element.getAttribute("name")] = element.textContent
            }
        }
        val pluralNodes = document.getElementsByTagName("plurals")
        for (i in 0 until pluralNodes.length) {
            val element = pluralNodes.item(i) as Element
            val items = element.getElementsByTagName("item")
            plurals[element.getAttribute("name")] = (0 until items.length).associate {
                val item = items.item(it) as Element
                item.getAttribute("quantity") to item.textContent
            }
        }
        return Texts(strings, plurals)
    }

    private fun placeholders(text: String): Set<String> =
        Regex("""%(\d+\$)?[sd]""").findAll(text).map { it.value }.toSet()

    private val english = read("values")

    @Test
    fun `every text is translated with the placeholders of its source`() {
        for (folder in listOf("values-ru", "values-be")) {
            val translated = read(folder)
            assertEquals(folder, english.strings.keys, translated.strings.keys)
            assertEquals(folder, english.plurals.keys, translated.plurals.keys)
            for ((name, source) in english.strings) {
                val text = translated.strings.getValue(name)
                assertTrue("$folder $name is empty", text.isNotBlank())
                assertEquals("$folder $name", placeholders(source), placeholders(text))
            }
            for ((name, forms) in translated.plurals) {
                assertEquals("$folder $name", setOf("one", "few", "many", "other"), forms.keys)
                val source = placeholders(english.plurals.getValue(name).getValue("other"))
                for ((quantity, text) in forms) {
                    assertEquals(
                        "$folder $name $quantity",
                        source,
                        placeholders(text),
                    )
                }
            }
        }
    }

    @Test
    fun `English says vibe, not wave`() {
        val texts = english.strings.values + english.plurals.values.flatMap { it.values }
        for (text in texts) assertFalse(text, text.contains("wave", ignoreCase = true))
        assertEquals("My Vibe", english.strings.getValue("library_my_wave"))
    }
}
