package app.spicetify.extension.spotify.theme;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class ThemePresetsTest {
    @Test
    public void amoledIsBlackWithDerivedSurfaces() {
        Map<String, Integer> roles = ThemePresets.amoled();
        assertEquals(Integer.valueOf(0xFF000000), roles.get("main"));
        assertEquals(Integer.valueOf(0xFF0F0F0F), roles.get("main-elevated"));
        assertEquals(Integer.valueOf(0xFF1A1A1A), roles.get("highlight-elevated"));
    }

    @Test
    public void materialYouFillsTheOpaqueRoles() {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals(13, ThemePresets.materialYou(context, false).size());
        Map<String, Integer> black = ThemePresets.materialYou(context, true);
        assertEquals(13, black.size());
        assertEquals(Integer.valueOf(0xFF000000), black.get("main"));
    }
}
