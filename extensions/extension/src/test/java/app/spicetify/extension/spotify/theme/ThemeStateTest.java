package app.spicetify.extension.spotify.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class ThemeStateTest {
    private Application context;

    @Before
    public void clear() {
        context = RuntimeEnvironment.getApplication();
        context.deleteSharedPreferences("spicetify_theme");
    }

    @Test
    public void startsWithSpotifysOwnColors() {
        ThemeState.Selection selection = ThemeState.load(context);
        assertEquals(ThemePresets.STOCK, selection.kind);
        assertEquals("Spotify default", selection.label);
        assertTrue(selection.colors.isEmpty());
    }

    @Test
    public void savesSchemesWithTheirColors() {
        Map<String, Integer> colors = new LinkedHashMap<>();
        colors.put("main", 0xFF1E1E2E);
        colors.put("button", 0xFFCBA6F7);
        ThemeState.save(context, new ThemeState.Selection(ThemeState.SCHEME, "Catppuccin (mocha)", colors));

        ThemeState.Selection loaded = ThemeState.load(context);
        assertEquals(ThemeState.SCHEME, loaded.kind);
        assertEquals("Catppuccin (mocha)", loaded.label);
        assertEquals(colors, loaded.colors);
    }
}
