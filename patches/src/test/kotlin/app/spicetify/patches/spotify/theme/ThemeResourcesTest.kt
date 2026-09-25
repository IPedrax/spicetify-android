package app.spicetify.patches.spotify.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory

class ThemeResourcesTest {
    private val roleMap = loadRoleMap()

    @Test
    fun `the role map covers every role and names each color once`() {
        assertEquals(ROLE_KEYS, roleMap.keys.toList())
        val names = roleMap.values.flatten()
        assertEquals(40, names.size)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `the role table keeps the stock alpha of translucent colors`() {
        val table = roleTable(parse(fixture()), roleMap)
        assertTrue(table.startsWith("main:gray_7,gray_10,dark_base_background_base,bg_gradient_end_color,sthlm_blk|"))
        assertTrue(
            "|selected-row:dark_base_background_tinted_base@1A,dark_base_background_tinted_highlight@24," +
                "dark_base_background_tinted_press@36,opacity_white_10@1A|" in table,
        )
        assertTrue(table.endsWith("|shadow:artwork_shadow@75,bottom_sheet_background_color@B3"))
    }

    @Test
    fun `a missing or duplicated color fails with its name`() {
        val missing = parse(fixture().replace("<color name=\"gray_20\">#FF333333</color>", ""))
        val failure = assertThrows(IllegalArgumentException::class.java) { roleTable(missing, roleMap) }
        assertTrue(failure.message!!.contains("missing gray_20"))
        val duplicated = parse(fixture().replace("</resources>", "<color name=\"gray_7\">#FF121212</color></resources>"))
        assertThrows(IllegalArgumentException::class.java) { roleTable(duplicated, roleMap) }
    }

    @Test
    fun `the overlayable declaration lists every mapped color under one public policy`() {
        val xml = overlayableXml(listOf("gray_7", "gray_10"))
        assertTrue("<overlayable name=\"SpicetifyTheme\">" in xml)
        assertTrue("<policy type=\"public\">" in xml)
        assertTrue("<item type=\"color\" name=\"gray_7\" />" in xml)
        assertTrue("<item type=\"color\" name=\"gray_10\" />" in xml)
    }

    /** Every mapped color. The alias and the translucent colors carry their 9.1.80.2221 stock values; the rest are opaque. */
    private fun fixture(): String {
        val stock = mapOf(
            "gray_20" to "#FF333333",
            "bg_gradient_end_color" to "@color/gray_7",
            "dark_base_background_tinted_base" to "#1AFFFFFF",
            "dark_base_background_tinted_highlight" to "#24FFFFFF",
            "dark_base_background_tinted_press" to "#36FFFFFF",
            "opacity_white_10" to "#1AFFFFFF",
            "artwork_shadow" to "#75000000",
            "bottom_sheet_background_color" to "#B3000000",
        )
        val colors = roleMap.values.flatten().joinToString("\n") { "<color name=\"$it\">${stock[it] ?: "#FF121212"}</color>" }
        return "<resources>\n$colors\n<string name=\"gray_7\">not a color</string>\n</resources>"
    }

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
}
