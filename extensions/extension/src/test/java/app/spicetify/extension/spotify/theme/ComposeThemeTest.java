package app.spicetify.extension.spotify.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class ComposeThemeTest {
    /** Stands in for an Encore color group: final colors its constructor takes in name order. */
    public static final class Colors {
        public final long a;
        public final long b;

        public Colors(long a, long b) {
            this.a = a;
            this.b = b;
        }
    }

    /** Stands in for the palette. */
    public static final class Palette {
        public final Colors a;
        public final Colors b;

        public Palette(Colors a, Colors b) {
            this.a = a;
            this.b = b;
        }
    }

    /** Stands in for Encore's raw colors holder, whose slots aren't final. */
    public static final class RawColors {
        public Object d;
    }

    private static final String TABLE = "a.a=base@FF121212*,a.b=tinted@1AFFFFFF,b.a=accent@FF1ED760;d.a=gray@FF121212*";

    private final Palette stock = new Palette(new Colors(color(0xFF121212), color(0x1AFFFFFF)),
            new Colors(color(0xFF1ED761), color(0xFF000000)));
    private Map<String, Integer> values;

    @Before
    public void setUp() {
        ComposeTheme.tables = ComposeTheme.parse(TABLE);
        Map<String, Integer> roles = new HashMap<>();
        roles.put("main", 0xFF102040);
        roles.put("selected-row", 0xFFCBA6F7);
        roles.put("button", 0xFFFF00AA);
        // The overlay's values: each resource takes its role's color at the resource's stock alpha.
        values = ThemeRoleMap.overlayValues(ThemeRoleMap.parse("main:base,gray|selected-row:tinted@1A|button:accent"), roles);
    }

    @Test
    public void mappedColorsTakeTheThemeAndTheRestKeepSpotifys() {
        ComposeTheme.update(values, false);
        Palette themed = (Palette) ComposeTheme.palette(stock);
        assertEquals(color(0xFF102040), themed.a.a);
        assertEquals(color(0x1ACBA6F7), themed.a.b);
        // b.a's stock color moved and b.b isn't mapped, so that group is Spotify's own object.
        assertSame(stock.b, themed.b);
        assertSame(themed, ComposeTheme.palette(stock));
        ComposeTheme.update(values, false);
        assertNotSame(themed, ComposeTheme.palette(stock));
    }

    @Test
    public void noColorsRestoreSpotifysPaletteAndRawColors() {
        RawColors raw = new RawColors();
        Colors gray = new Colors(color(0xFF121212), color(0xFF181818));
        raw.d = gray;
        ComposeTheme.update(values, false);
        ComposeTheme.primitives(raw);
        assertEquals(color(0xFF102040), ((Colors) raw.d).a);
        assertEquals(gray.b, ((Colors) raw.d).b);

        ComposeTheme.update(Collections.<String, Integer>emptyMap(), true);
        assertSame(stock, ComposeTheme.palette(stock));
        assertSame(gray, raw.d);
        assertEquals(color(0x00102040), ComposeTheme.iconTint(color(0x00102040)));
    }

    @Test
    public void aBackgroundImageClearsOnlySeeThroughColorsAndKeepsTheirRgb() {
        ComposeTheme.update(values, true);
        Palette themed = (Palette) ComposeTheme.palette(stock);
        assertEquals(color(0x00102040), themed.a.a);
        assertEquals(color(0x1ACBA6F7), themed.a.b);
        assertEquals(0f, ComposeTheme.scrimAlpha(0.75f), 0f);
        // An icon tinted with the see-through background stays opaque; other tints don't change.
        assertEquals(color(0xFF102040), ComposeTheme.iconTint(color(0x00102040)));
        assertEquals(color(0x1ACBA6F7), ComposeTheme.iconTint(color(0x1ACBA6F7)));

        ComposeTheme.update(values, false);
        assertEquals(0.75f, ComposeTheme.scrimAlpha(0.75f), 0f);
        assertEquals(color(0x00102040), ComposeTheme.iconTint(color(0x00102040)));
    }

    /** A Compose sRGB color: the ARGB in the high 32 bits. */
    private static long color(int argb) {
        return (long) argb << 32;
    }
}
