package app.spicetify.extension.spotify.theme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class ThemeSectionTest {
    private final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_Material);

    @Test
    public void explainsTheAndroidRequirementWhenUnsupported() {
        List<String> texts = texts(ThemeSection.create(context, false, () -> {}));
        assertTrue(texts.contains("In-app themes need Android 14 or later."));
        assertFalse(texts.contains("AMOLED black"));
    }

    @Test
    public void offersThePresetsWhenSupported() {
        List<String> texts = texts(ThemeSection.create(context, true, () -> {}));
        assertTrue(texts.contains("Current theme: Spotify default"));
        assertTrue(texts.contains("Spotify default"));
        assertTrue(texts.contains("AMOLED black"));
        assertTrue(texts.contains("Material You"));
        assertTrue(texts.contains("Material You, black background"));
    }

    @Test
    public void aThemeThatCantBeAppliedDoesNotCloseTheScreen() {
        AtomicBoolean applied = new AtomicBoolean();
        View section = ThemeSection.create(context, true, () -> applied.set(true));
        button(section, "AMOLED black").performClick();
        assertFalse(applied.get());
    }

    private static List<String> texts(View view) {
        List<String> texts = new ArrayList<>();
        if (view instanceof TextView) texts.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) texts.addAll(texts(group.getChildAt(i)));
        }
        return texts;
    }

    private static Button button(View view, String label) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(label)) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = button(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
