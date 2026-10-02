package app.spicetify.extension.spotify.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import app.spicetify.extension.spotify.extensions.Extensions;
import app.spicetify.extension.spotify.extensions.ExtensionsTest;
import app.spicetify.extension.spotify.extensions.PlayerBridgeTest;
import app.spicetify.extension.spotify.theme.MarketplaceScreenTest;
import app.spicetify.extension.spotify.theme.ThemeRuntime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class SpicetifySettingsScreenTest {
    private final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

    /** Every patch that has settings, installed. */
    @Implements(value = InstalledPatches.class, isInAndroidSdk = false)
    public static class AllInstalled {
        @Implementation public static boolean cleanSharing() { return true; }
        @Implementation public static boolean themeColors() { return true; }
        @Implementation public static boolean homePins() { return true; }
        @Implementation public static boolean serverFiles() { return true; }
        @Implementation public static boolean extensions() { return true; }
    }

    /** Only the extensions patch, installed. */
    @Implements(value = InstalledPatches.class, isInAndroidSdk = false)
    public static class OnlyExtensions {
        @Implementation public static boolean extensions() { return true; }
    }

    /** A device where in-app themes can't apply, as on Android 13 and older. */
    @Implements(value = ThemeRuntime.class, isInAndroidSdk = false)
    public static class ThemesUnsupported {
        @Implementation public static boolean supported(Context context) { return false; }
    }

    @Before
    public void setUp() {
        // The player bridge is process-wide, so each test starts with it connected and nothing wrong.
        PlayerBridgeTest.attachRouter(false);
    }

    @Test
    public void opensAsADialogWithoutStartingAnActivity() {
        SpicetifySettingsScreen.open(activity);

        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void showsTheTitleAndOnlyInstalledPatchControls() {
        SpicetifySettingsScreen.open(activity);

        List<String> texts = texts(ShadowDialog.getLatestDialog().getWindow().getDecorView());
        assertTrue(texts.contains("Spicetify"));
        // An unpatched extension reports no installed patches.
        assertTrue(texts.contains("No configurable Spicetify patches are installed."));
        assertFalse(texts.contains("Clean sharing links"));
        assertFalse(texts.contains("Spicetify Marketplace"));
    }

    @Test
    public void backDismissesTheDialog() {
        SpicetifySettingsScreen.open(activity);
        Dialog dialog = ShadowDialog.getLatestDialog();

        dialog.onBackPressed();

        assertFalse(dialog.isShowing());
    }

    @Test
    public void closeButtonDismissesTheDialog() {
        SpicetifySettingsScreen.open(activity);
        Dialog dialog = ShadowDialog.getLatestDialog();

        dialog.getWindow().getDecorView().findViewWithTag(SpicetifySettingsScreen.CLOSE_TAG).performClick();

        assertFalse(dialog.isShowing());
    }

    @Test
    public void ignoresAFinishingActivity() {
        activity.finish();

        SpicetifySettingsScreen.open(activity);

        assertNull(ShadowDialog.getLatestDialog());
    }

    @Test
    @Config(shadows = AllInstalled.class)
    public void theMarketplaceButtonComesFirstThenTheThemeAndTheExtensionsThenTheOtherSettings() {
        List<String> texts = texts(page());

        assertEquals("Spicetify Marketplace", texts.get(texts.indexOf("Spicetify") + 1));
        int last = texts.indexOf("Spicetify Marketplace");
        List<String> order = Arrays.asList("Theme", "Extensions", "Clean sharing links", "Home shortcuts", "Server files");
        for (String next : order) {
            int at = texts.indexOf(next);
            assertTrue(next + " comes next in " + texts, at > last);
            last = at;
        }
        // The sharing switch kept its description.
        assertTrue(texts.get(texts.indexOf("Clean sharing links") + 1).startsWith("Remove tracking parameters"));
        assertFalse(texts.contains("No configurable Spicetify patches are installed."));
    }

    @Test
    @Config(shadows = OnlyExtensions.class)
    public void withOnlyTheExtensionsPatchTheMarketplaceButtonOpensTheMarketplaceOnItsExtensionsTab() {
        SpicetifySettingsScreen.open(activity, MarketplaceScreenTest::showOffline);
        View page = ShadowDialog.getLatestDialog().getWindow().getDecorView();

        // The Marketplace button tops the page as it does with Theme colors, and no Theme section follows.
        List<String> texts = texts(page);
        int title = texts.indexOf("Spicetify");
        assertEquals(Arrays.asList("Spicetify Marketplace", "Extensions"), texts.subList(title + 1, title + 3));
        assertFalse(texts.contains("Theme"));
        Button button = (Button) labeled(page, "Spicetify Marketplace");
        assertEquals(0xFF1ED760, button.getBackgroundTintList().getDefaultColor()); // Spotify's green
        assertEquals(Color.BLACK, button.getCurrentTextColor());
        assertSame(Typeface.DEFAULT_BOLD, button.getTypeface());

        button.performClick();

        // Themes need the Theme colors patch, so it opens where the extensions are, and theme cards say so.
        View marketplace = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertTrue(labeled(marketplace, "Extensions").isSelected());
        labeled(marketplace, "Themes").performClick();
        ListView list = find(marketplace, ListView.class);
        assertTrue(texts(list.getAdapter().getView(0, null, list)).contains("Needs the Theme colors patch"));
    }

    @Test
    @Config(shadows = {AllInstalled.class, ThemesUnsupported.class})
    public void whereThemesCantApplyTheMarketplaceButtonStillOpensTheMarketplaceOnItsExtensionsTab() {
        SpicetifySettingsScreen.open(activity, MarketplaceScreenTest::showOffline);
        View page = ShadowDialog.getLatestDialog().getWindow().getDecorView();

        List<String> texts = texts(page);
        int title = texts.indexOf("Spicetify");
        assertEquals(Arrays.asList("Spicetify Marketplace", "Theme", "In-app themes need Android 14 or later.",
                "Extensions"), texts.subList(title + 1, title + 5));

        labeled(page, "Spicetify Marketplace").performClick();

        View marketplace = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertTrue(labeled(marketplace, "Extensions").isSelected());
        labeled(marketplace, "Themes").performClick();
        ListView list = find(marketplace, ListView.class);
        assertTrue(texts(list.getAdapter().getView(0, null, list)).contains("Needs Android 14 or later"));
    }

    @Test
    @Config(shadows = AllInstalled.class)
    public void theExtensionsSectionListsOnlyTheOnesThatAreOnAndARowOpensItsDialog() {
        switches().edit().putBoolean(Extensions.TRASH_BIN, true).putBoolean(Extensions.HIDE_PODCASTS, true).commit();
        ExtensionsTest.report(Extensions.TRASH_BIN, "Skipped spotify:track:x");
        ExtensionsTest.report(Extensions.HIDE_PODCASTS, "Hid 3 items");
        View page = page();

        // Each one's title and latest status, under the heading, and nothing for the ones that are off.
        List<String> texts = texts(page);
        int heading = texts.indexOf("Extensions");
        assertEquals(Arrays.asList("Trash Bin", "Skipped spotify:track:x", "Hide podcasts", "Hid 3 items"),
                texts.subList(heading + 1, heading + 5));
        assertEquals("Clean sharing links", texts.get(heading + 5));

        ((View) labeled(page, "Trash Bin").getParent()).performClick();

        // The Marketplace's dialog for Trash Bin, with its switch on.
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("Trash Bin", Shadows.shadowOf(dialog).getTitle());
        View content = dialog.getWindow().getDecorView();
        assertTrue(texts(content).contains(Extensions.description(Extensions.TRASH_BIN)));
        Switch toggle = find(content, Switch.class);
        assertTrue(toggle.isChecked());
        assertTrue(toggle.isEnabled());

        // Turned off there, it leaves the page's list.
        toggle.setChecked(false);
        texts = texts(page);
        assertFalse(texts.contains("Trash Bin"));
        assertEquals("Hide podcasts", texts.get(texts.indexOf("Extensions") + 1));
    }

    @Test
    @Config(shadows = AllInstalled.class)
    public void theExtensionsSectionNeverListsAHiddenOneEvenWithItsSwitchOn() {
        switches().edit().putBoolean(Extensions.UNAVAILABLE_SONGS, true).putBoolean(Extensions.TRASH_BIN, true)
                .commit();

        List<String> texts = texts(page());

        assertFalse(texts.contains("Unavailable songs"));
        assertEquals("Trash Bin", texts.get(texts.indexOf("Extensions") + 1));
    }

    @Test
    @Config(shadows = AllInstalled.class)
    public void withNoExtensionOnTheSectionSaysWhereToTurnThemOn() {
        List<String> texts = texts(page());

        assertEquals("No extensions are on. Turn them on in the Marketplace's Extensions tab.",
                texts.get(texts.indexOf("Extensions") + 1));
    }

    @Test
    @Config(shadows = AllInstalled.class)
    public void theBridgeLineShowsOnlyWhileTheBridgeHasAProblem() {
        for (String text : texts(page())) assertFalse(text, text.startsWith("Player bridge"));

        PlayerBridgeTest.attachRouter(true); // Spotify destroyed its router, as on a logout
        List<String> texts = texts(page());

        assertEquals("Player bridge: waiting for Spotify", texts.get(texts.indexOf("Extensions") + 1));
    }

    @Test
    @Config(shadows = AllInstalled.class)
    public void closingTheMarketplaceListsTheExtensionsAgainWhicheverWayItWasOpened() {
        SpicetifySettingsScreen.open(activity, MarketplaceScreenTest::showOffline);
        View page = ShadowDialog.getLatestDialog().getWindow().getDecorView();

        // Turned on in the Marketplace from its button, it's listed once the Marketplace closes.
        labeled(page, "Spicetify Marketplace").performClick();
        switchRandomSongAndClose(true);
        List<String> texts = texts(page);
        assertEquals("Play a random song", texts.get(texts.indexOf("Extensions") + 1));

        // Turned off in the Marketplace from the current theme, it leaves the list the same way.
        ((View) labeled(page, "Spotify default").getParent()).performClick();
        switchRandomSongAndClose(false);
        texts = texts(page);
        assertEquals("No extensions are on. Turn them on in the Marketplace's Extensions tab.",
                texts.get(texts.indexOf("Extensions") + 1));
    }

    @Test
    @Config(shadows = AllInstalled.class)
    public void aSharingSwitchThatCantSaveIsLoggedInsteadOfThrowingIntoSpotify() {
        // Nothing initialized PatchSettings here, so saving the switch throws.
        assertNull(PatchSettings.applicationContext());
        Switch sharing = (Switch) labeled(page(), "Clean sharing links");

        sharing.setChecked(!sharing.isChecked()); // would throw into Spotify's switch handling without the guard

        boolean logged = false;
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("Spicetify")) {
            if (item.throwable instanceof IllegalStateException) logged = true;
        }
        assertTrue("the failure is logged", logged);
    }

    /** Turns Play a random song on or off from its card in the Marketplace on screen, then closes it. */
    private static void switchRandomSongAndClose(boolean on) {
        Dialog marketplace = ShadowDialog.getLatestDialog();
        View screen = marketplace.getWindow().getDecorView();
        labeled(screen, "Extensions").performClick();
        ListView list = find(screen, ListView.class);
        View card = list.getAdapter().getView(0, null, list);
        assertNotNull(labeled(card, "Play a random song"));
        find(card, Switch.class).setChecked(on);
        marketplace.dismiss();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // Dialog calls its dismiss listener from a posted message
    }

    /** Opens Spicetify settings and returns its window's views. */
    private View page() {
        SpicetifySettingsScreen.open(activity);
        return ShadowDialog.getLatestDialog().getWindow().getDecorView();
    }

    /** The extensions' switches, kept where {@link Extensions#isOn} reads them. */
    private SharedPreferences switches() {
        return activity.getSharedPreferences("spicetify_extensions", Context.MODE_PRIVATE);
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

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type);
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
}
