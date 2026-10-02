package app.spicetify.extension.spotify.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class ThemeSectionTest {
    private final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_Material);

    @Test
    public void explainsTheAndroidRequirementWhenUnsupported() throws IOException {
        ThemeBackground.save(context, ThemeBackgroundTest.png()); // and offers no blur switch even so

        // No Marketplace button and no current theme either.
        assertEquals(Arrays.asList("Theme", "In-app themes need Android 14 or later."),
                texts(ThemeSection.create(context, false, () -> {}, () -> {})));
    }

    @Test
    public void showsOnlyTheCurrentTheme() {
        View section = ThemeSection.create(context, true, () -> {}, () -> {});

        // The Marketplace button is the settings page's own, above this section.
        assertEquals(Arrays.asList("Theme", "Spotify default", "Some colors change after Spotify restarts."),
                texts(section));
        // Spotify's own colors, as on the Marketplace's Spotify default card.
        assertEquals(Arrays.asList(0xFF121212, 0xFF282828, 0xFF1ED760, 0xFFFFFFFF),
                MarketplaceScreenTest.colorBlocks(section));
    }

    @Test
    public void theCurrentThemeNamesItsSchemeAndShowsItsColors() {
        Map<String, Integer> colors = new LinkedHashMap<>();
        colors.put("main", 0xFF0B0B2B);
        colors.put("card", 0xFF1B1B3B);
        colors.put("button", 0xFFE0E0FF);
        colors.put("text", 0xFFF0F0F0);
        // As a theme with a background image saves it: main and card see-through.
        ThemeState.save(context, new ThemeState.Selection(ThemeState.SCHEME, "Galaxy V2 (Galaxy)",
                ThemeResolver.seeThrough(colors)));

        View section = ThemeSection.create(context, true, () -> {}, () -> {});

        assertTrue(texts(section).contains("Galaxy V2, scheme Galaxy"));
        // The strip shows each color whole, not the image's gap.
        assertEquals(Arrays.asList(0xFF0B0B2B, 0xFF1B1B3B, 0xFFE0E0FF, 0xFFF0F0F0),
                MarketplaceScreenTest.colorBlocks(section));
    }

    @Test
    public void aSchemeIsNamedAfterItsThemeAndAPresetByItsLabel() {
        assertEquals("Galaxy V2, scheme Galaxy", ThemeSection.name(scheme("Galaxy V2 (Galaxy)")));
        assertEquals("Pasted theme, scheme mocha", ThemeSection.name(scheme("Pasted theme (mocha)")));
        // A theme's own name can have parentheses: the scheme is the last pair.
        assertEquals("Nord (Spicetify), scheme dark", ThemeSection.name(scheme("Nord (Spicetify) (dark)")));
        assertEquals("AMOLED black",
                ThemeSection.name(ThemeState.Selection.preset(ThemePresets.AMOLED, "AMOLED black")));
        assertEquals("Spotify default", ThemeSection.name(ThemeState.load(context)));
    }

    @Test
    public void theCurrentThemeOpensTheMarketplace() {
        AtomicInteger opened = new AtomicInteger();
        View section = ThemeSection.create(context, true, () -> {}, opened::incrementAndGet);

        ((View) labeled(section, "Spotify default").getParent()).performClick();
        assertEquals(1, opened.get());
    }

    @Test
    public void aButtonWhoseActionFailsIsLoggedInsteadOfCrashing() {
        LinearLayout section = new LinearLayout(context);
        ThemeSection.addButton(section, "Broken", () -> {
            throw new NoClassDefFoundError("broken"); // an Error, as a changed Spotify class would throw
        });
        button(section, "Broken").performClick(); // would throw into Spotify's click handling without the guard
    }

    @Test
    public void aThemeThatCantBeAppliedDoesNotCloseTheScreen() {
        AtomicBoolean applied = new AtomicBoolean();
        ThemeSection.apply(context, ThemeState.Selection.preset(ThemePresets.AMOLED, "AMOLED black"), null,
                () -> applied.set(true));
        assertFalse(applied.get());

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        assertEquals("Theme not applied", Shadows.shadowOf(dialog).getTitle());
    }

    @Test
    public void applySchemeThatResolvesFineEndsInTheNotAppliedDialog() {
        AtomicBoolean applied = new AtomicBoolean();
        SpicetifyTheme.Scheme scheme = SpicetifyTheme.parse("[mocha]\nmain = 1e1e2e").get(0);
        ThemeSection.applyScheme(context, scheme, null, "Pasted theme", null, () -> applied.set(true));
        assertFalse(applied.get());

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("Theme not applied", Shadows.shadowOf(dialog).getTitle());
        assertEquals("This device couldn't apply the theme.", Shadows.shadowOf(dialog).getMessage());
    }

    @Test
    public void applySchemeWithAMissingAccentKeyShowsTheResolversMessage() {
        SpicetifyTheme.Scheme scheme = SpicetifyTheme.parse("[mocha]\nmain = 1e1e2e").get(0);
        ThemeSection.applyScheme(context, scheme, "peach", "Pasted theme", null, () -> {});

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("Theme not applied", Shadows.shadowOf(dialog).getTitle());
        assertEquals("Accent key \"peach\" is not in this color scheme.", Shadows.shadowOf(dialog).getMessage());
    }

    @Test
    public void applySchemeWithNoColorsShowsAnErrorAndDoesNotApply() {
        SpicetifyTheme.Scheme scheme = SpicetifyTheme.parse("[turntable]\n").get(0);
        ThemeSection.applyScheme(context, scheme, null, "Pasted theme", null, () -> {});

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("Theme not applied", Shadows.shadowOf(dialog).getTitle());
        assertEquals("Pasted theme (turntable) has no colors Spotify can use.", Shadows.shadowOf(dialog).getMessage());
        assertEquals(ThemePresets.STOCK, ThemeState.load(context).kind);
    }

    @Test
    public void chooseSchemeWithTwoSchemesListsThemInOrder() {
        List<SpicetifyTheme.Scheme> schemes = SpicetifyTheme.parse("[mocha]\nmain = 1e1e2e\n[latte]\nmain = eff1f5");
        ThemeSection.chooseScheme(context, schemes, null, "Pasted theme", null, () -> {});

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("Choose a color scheme", Shadows.shadowOf(dialog).getTitle());
        CharSequence[] items = Shadows.shadowOf(dialog).getItems();
        assertEquals(2, items.length);
        assertEquals("mocha", items[0]);
        assertEquals("latte", items[1]);
    }

    @Test
    public void chooseSchemeWithOneSchemeAppliesItWithoutAChooser() {
        List<SpicetifyTheme.Scheme> schemes = SpicetifyTheme.parse("[mocha]\nmain = 1e1e2e");
        ThemeSection.chooseScheme(context, schemes, null, "Pasted theme", null, () -> {});

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("Theme not applied", Shadows.shadowOf(dialog).getTitle());
    }

    @Test
    public void aPresetClearsTheBackgroundImage() throws IOException {
        ThemeBackground.save(context, ThemeBackgroundTest.png());
        ThemeSection.apply(context, ThemeState.Selection.preset(ThemePresets.AMOLED, "AMOLED black"), null, () -> {});
        assertFalse(ThemeBackground.hasImage(context));
    }

    @Test
    public void aSchemeWithAnImageSavesItBeforeApplying() {
        SpicetifyTheme.Scheme scheme = SpicetifyTheme.parse("[base]\nmain = 000000\ncard = 000000").get(0);
        ThemeSection.applyScheme(context, scheme, null, "Galaxy V2", ThemeBackgroundTest.png(), () -> {});

        assertTrue(ThemeBackground.hasImage(context));
        // Unpatched, the extension has no role table, so select fails once the image is saved.
        assertEquals("This device couldn't apply the theme.",
                Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getMessage());
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Android's own decoder, which refuses what isn't an image
    public void anImageThatCantBeSavedShowsWhyAndAppliesNothing() {
        SpicetifyTheme.Scheme scheme = SpicetifyTheme.parse("[base]\nmain = 000000").get(0);
        ThemeSection.applyScheme(context, scheme, null, "Galaxy V2",
                "<html>Not Found</html>".getBytes(StandardCharsets.UTF_8), () -> {});

        // The latest dialog: select never ran, or it would say this device couldn't apply the theme.
        assertEquals("Couldn't save the background image: Not an image Android can read",
                Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getMessage());
        assertFalse(ThemeBackground.hasImage(context));
    }

    @Test
    public void offersTheBlurSwitchOnlyWhileAnImageIsSet() throws IOException {
        assertFalse(texts(ThemeSection.create(context, true, () -> {}, () -> {})).contains("Blur background image"));
        ThemeBackground.save(context, ThemeBackgroundTest.png());
        assertTrue(texts(ThemeSection.create(context, true, () -> {}, () -> {})).contains("Blur background image"));
    }

    private static ThemeState.Selection scheme(String label) {
        return new ThemeState.Selection(ThemeState.SCHEME, label, Collections.emptyMap());
    }

    private static TextView labeled(View view, String label) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(label)) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = labeled(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
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
