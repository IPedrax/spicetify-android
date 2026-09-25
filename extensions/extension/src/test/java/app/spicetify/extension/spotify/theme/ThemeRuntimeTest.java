package app.spicetify.extension.spotify.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ThemeRuntimeTest {
    @Test
    @Config(sdk = 24)
    public void olderAndroidIsNotSupportedAndNothingApplies() {
        Application context = RuntimeEnvironment.getApplication();
        assertFalse(ThemeRuntime.supported(context));
        ThemeRuntime.install(context);
        assertFalse(ThemeRuntime.select(context, ThemeState.Selection.preset(ThemePresets.AMOLED, "AMOLED black")));
        assertEquals(ThemePresets.STOCK, ThemeState.load(context).kind);
    }

    @Test
    @Config(sdk = 35)
    public void aFailedSelectionIsNotSaved() {
        Application context = RuntimeEnvironment.getApplication();
        context.deleteSharedPreferences("spicetify_theme");
        ThemeRuntime.install(context);
        // Unpatched, the extension has no role table, so no theme can take effect.
        assertFalse(ThemeRuntime.select(context, ThemeState.Selection.preset(ThemePresets.AMOLED, "AMOLED black")));
        assertEquals(ThemePresets.STOCK, ThemeState.load(context).kind);
    }

    @Test
    @Config(sdk = 35)
    public void presetsResolveToRoleColors() {
        Application context = RuntimeEnvironment.getApplication();
        assertEquals(Integer.valueOf(0xFF000000), ThemeRuntime.roleColors(context,
                ThemeState.Selection.preset(ThemePresets.AMOLED, "AMOLED black")).get("main"));
        assertEquals(0, ThemeRuntime.roleColors(context,
                ThemeState.Selection.preset(ThemePresets.STOCK, "Spotify default")).size());
    }
}
