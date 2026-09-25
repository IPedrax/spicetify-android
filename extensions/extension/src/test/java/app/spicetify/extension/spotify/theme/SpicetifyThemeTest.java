package app.spicetify.extension.spotify.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class SpicetifyThemeTest {
    private static final String CATPPUCCIN = "; Catppuccin-style file\n"
            + "[Mocha]\n"
            + "text               = cdd6f4\n"
            + "MAIN               = 1E1E2E   ; key names are case-insensitive\n"
            + "button: 7F849C\n"
            + "mauve              = #cba6f7\n"
            + "empty              =\n"
            + "\n"
            + "[latte]\n"
            + "main = eff1f5\n";

    @Test
    public void readsSectionsKeysAndValuesLikeTheSpicetifyCli() {
        List<SpicetifyTheme.Scheme> schemes = SpicetifyTheme.parse(CATPPUCCIN);
        assertEquals("mocha", schemes.get(0).name);
        assertEquals("latte", schemes.get(1).name);
        Map<String, Integer> mocha = schemes.get(0).colors;
        assertEquals(Integer.valueOf(0xFFCDD6F4), mocha.get("text"));
        assertEquals(Integer.valueOf(0xFF1E1E2E), mocha.get("main"));
        assertEquals(Integer.valueOf(0xFF7F849C), mocha.get("button"));
        assertEquals(Integer.valueOf(0xFFCBA6F7), mocha.get("mauve"));
        assertFalse(mocha.containsKey("empty"));
    }

    @Test
    public void skipsKeysBeforeTheFirstSection() {
        assertEquals(new HashSet<>(Arrays.asList("text")),
                SpicetifyTheme.parse("main = 000000\n[dark]\ntext = ffffff").get(0).colors.keySet());
    }

    @Test
    public void acceptsHashAarrggbbButNotBareEightDigits() {
        assertEquals(Integer.valueOf(0x80FFFFFF), SpicetifyTheme.parse("[a]\nshadow = #80FFFFFF").get(0).colors.get("shadow"));
        failsWith("[a]\nshadow = 80FFFFFF", "Line 2: shadow");
    }

    @Test
    public void rejectsValuesThePhoneCantUse() {
        for (String value : new String[] {"${xrdb:color0}", "${HOME}", "red", "50,80,120", "#12345", "rgb(1,2,3)"}) {
            failsWith("[a]\nmain = " + value, "Line 2: main");
        }
    }

    @Test
    public void rejectsALineWithoutDelimiterAndAFileWithoutSections() {
        failsWith("[a]\njust text", "Line 2:");
        failsWith("; nothing here", "No color schemes found");
    }

    @Test
    public void readsSpiceVariablesFromCss() {
        List<SpicetifyTheme.Scheme> schemes = SpicetifyTheme.parse(":root {\n"
                + "  --spice-main: #121212;\n"
                + "  --spice-rgb-main: 18,18,18;\n"
                + "  --spice-button: rgb(30, 215, 96);\n"
                + "  --spice-shadow: rgba(0, 0, 0, 0.5);\n"
                + "  --spice-card: #28282880;\n"
                + "}\n.main-view { color: red; }");
        Map<String, Integer> colors = schemes.get(0).colors;
        assertEquals(1, schemes.size());
        assertEquals(Integer.valueOf(0xFF121212), colors.get("main"));
        assertEquals(Integer.valueOf(0xFF1ED760), colors.get("button"));
        assertEquals(Integer.valueOf(0x80000000), colors.get("shadow"));
        assertEquals(Integer.valueOf(0x80282828), colors.get("card"));
        assertEquals(4, colors.size());
    }

    @Test
    public void rejectsCssWithUnsupportedValues() {
        failsWith(":root { --spice-main: var(--x); }", "--spice-main");
        failsWith(":root { --spice-shadow: rgba(0, 0, 0, 1.2.3); }", "--spice-shadow");
        failsWith(":root { --spice-shadow: rgba(0, 0, 0, 2); }", "--spice-shadow");
    }

    private static void failsWith(String text, String messageStart) {
        try {
            SpicetifyTheme.parse(text);
            fail("Expected a ThemeException for: " + text);
        } catch (ThemeException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith(messageStart)
                    || expected.getMessage().contains(messageStart));
        }
    }
}
