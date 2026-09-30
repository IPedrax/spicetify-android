package app.spicetify.extension.spotify.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import app.spicetify.extension.spotify.extensions.LibraryTest;
import app.spicetify.extension.spotify.extensions.PlayerBridgeTest;
import app.spicetify.extension.spotify.home.HomePins;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, shadows = HomePinsSettingsTest.Capabilities.class)
public class HomePinsSettingsTest {
    private static final String LOADING = "Loading your library";
    private static final String UNAVAILABLE = "Your library isn't available yet";
    private static final String A = "spotify:playlist:a";
    private static final String B = "spotify:playlist:b";
    private static final String C = "spotify:playlist:c";

    @Implements(value = InstalledPatches.class, isInAndroidSdk = false)
    public static class Capabilities {
        @Implementation public static boolean homePins() { return true; }
    }

    @Before public void initialize() {
        RuntimeEnvironment.getApplication().deleteSharedPreferences("spicetify_home_pins");
        HomePins.initialize(RuntimeEnvironment.getApplication());
    }

    @Test public void thePickerListsYourLibraryWithoutAVisitToHome() throws Exception {
        LibraryTest.attachLibrary(new String[]{"spotify:playlist:road", "Road trip", "spotify:image:r"},
                new String[]{"spotify:album:blue", "Blue", "spotify:image:b"},
                new String[]{"spotify:playlist:chill", "Chill", null},
                new String[]{"spotify:playlist:chill2", "Chill", null});

        View picker = openPicker();

        assertEquals(LOADING, note(picker).getText().toString());
        // A name that two share shows each one's uri too.
        awaitRows(picker, "Chill\nspotify:playlist:chill", "Chill\nspotify:playlist:chill2", "Liked Songs", "Road trip",
                "Blue");
        assertEquals(View.GONE, note(picker).getVisibility());
        assertNull("no dialog asks for a visit to Home", labeled(ShadowDialog.getLatestDialog().getWindow().getDecorView(),
                "No Home shortcuts loaded"));
    }

    @Test public void withoutTheBridgeThePickerShowsHomesTilesAndSaysYourLibraryIsntAvailableYet() throws Exception {
        PlayerBridgeTest.attachRouter(true); // Spotify destroyed its router, as on a logout
        observe(new String[]{"spotify:playlist:b", "spotify:playlist:a"}, new String[]{"Two", "One"});

        View picker = openPicker();

        awaitNote(picker, UNAVAILABLE);
        assertEquals(View.VISIBLE, note(picker).getVisibility());
        assertEquals(Arrays.asList("One", "Two"), rows(picker));
    }

    @Test public void searchNarrowsTheListAndWhatIsCheckedStaysChecked() throws Exception {
        PlayerBridgeTest.attachRouter(true);
        observe(new String[]{"spotify:playlist:mixb", "spotify:playlist:road", "spotify:playlist:mixa"},
                new String[]{"Mix B", "Road trip", "mix a"});
        View picker = openPicker();
        awaitNote(picker, UNAVAILABLE);
        EditText search = find(picker, EditText.class);
        ListView list = find(picker, ListView.class);

        search.setText("MIX");
        assertEquals(Arrays.asList("mix a", "Mix B"), rows(picker));
        list.performItemClick(null, 1, 1);
        search.setText("");

        assertEquals(Arrays.asList("mix a", "1. Mix B", "Road trip"), rows(picker));
        assertFalse(list.isItemChecked(0));
        assertTrue("checked while filtered", list.isItemChecked(1));
        assertFalse(list.isItemChecked(2));
        search.setText("road");
        assertEquals(Arrays.asList("Road trip"), rows(picker));
        assertFalse(list.isItemChecked(0));
        // Saved while the search hides it.
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("spotify:playlist:mixb", HomePins.choices().get(0).id);
        assertTrue(HomePins.choices().get(0).pinned);
        assertFalse(HomePins.choices().get(1).pinned);
    }

    @Test public void excessSelectionKeepsPickerOpenAndLeavesSavedPinsUntouched() throws Exception {
        JSONArray saved = new JSONArray();
        for (int i = 0; i < 64; i++) saved.put(new JSONObject().put("id", "spotify:playlist:" + i).put("label", "Playlist " + i));
        RuntimeEnvironment.getApplication().getSharedPreferences("spicetify_home_pins", 0)
                .edit().putString("pins", saved.toString()).commit();
        HomePins.initialize(RuntimeEnvironment.getApplication());
        observe(new String[]{"spotify:playlist:new"}, new String[]{"New playlist"});
        PlayerBridgeTest.attachRouter(true);
        View view = openPicker();
        awaitNote(view, UNAVAILABLE);
        AlertDialog picker = ShadowAlertDialog.getLatestAlertDialog();
        ListView list = find(view, ListView.class);
        assertEquals("New playlist", list.getAdapter().getItem(64));
        list.performItemClick(null, 64, 64);
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(picker.isShowing());
        assertEquals("Too many Home pins.", ShadowToast.getTextOfLatestToast());
        assertEquals(64, HomePins.choices().stream().filter(choice -> choice.pinned).count());
        list.performItemClick(null, 64, 64);
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(picker.isShowing());
        assertEquals("Pins saved. Restart Spotify to refresh Home.",
                Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getMessage().toString());
    }

    @Test public void picksAreSavedInTheOrderTheyWereTickedAndHomeShowsThemSo() throws Exception {
        View picker = openWithoutTheLibrary();
        ListView list = find(picker, ListView.class);

        list.performItemClick(null, 2, 2);
        list.performItemClick(null, 0, 0);
        list.performItemClick(null, 1, 1);

        assertEquals(Arrays.asList("2. Alpha", "3. Bravo", "1. Charlie"), rows(picker));
        save();
        assertEquals(Arrays.asList(C, A, B), pinned());
        ArrayList<Object> home = new ArrayList<>();
        for (String uri : new String[]{A, B, C}) home.add(new p.goz0(new p.nnz0(uri, 2, uri, "", false, uri)));
        List<String> shown = new ArrayList<>();
        for (Object row : HomePins.plan("home-shortcuts", home)) shown.add(((p.goz0) row).a.d);
        assertEquals("Home shows pin 1 first", Arrays.asList(C, A, B), shown);
    }

    @Test public void thePinsKeepTheirOrderAndNewPicksComeAfterThem() throws Exception {
        View picker = openWithoutTheLibrary(B, A);
        assertEquals(Arrays.asList("1. Bravo", "2. Alpha", "Charlie"), rows(picker));

        find(picker, ListView.class).performItemClick(null, 2, 2);

        assertEquals(Arrays.asList("1. Bravo", "2. Alpha", "3. Charlie"), rows(picker));
        save();
        assertEquals(Arrays.asList(B, A, C), pinned());
    }

    @Test public void untickingRemovesAPickAndRenumbersTheRestWhichTheNextPickerShows() throws Exception {
        View picker = openWithoutTheLibrary(B, A, C);

        find(picker, ListView.class).performItemClick(null, 0, 0);

        assertEquals(Arrays.asList("Bravo", "1. Alpha", "2. Charlie"), rows(picker));
        save();
        assertEquals(Arrays.asList(A, C), pinned());
        View again = openPicker();
        awaitNote(again, UNAVAILABLE);
        assertEquals(Arrays.asList("1. Alpha", "2. Charlie", "Bravo"), rows(again));
    }

    @Test public void aSearchNeverChangesThePickOrder() throws Exception {
        View picker = openWithoutTheLibrary();
        EditText search = find(picker, EditText.class);
        ListView list = find(picker, ListView.class);

        list.performItemClick(null, 2, 2);
        search.setText("alp");
        assertEquals(Arrays.asList("Alpha"), rows(picker));
        list.performItemClick(null, 0, 0);
        search.setText("");

        assertEquals(Arrays.asList("2. Alpha", "Bravo", "1. Charlie"), rows(picker));
        save();
        assertEquals(Arrays.asList(C, A), pinned());
    }

    @Test public void aPickThatLeavesTheListWhenTheLibraryComesIsDropped() throws Exception {
        LibraryTest.attachLibrary(new String[]{"spotify:playlist:road", "Road trip", null});
        observe(new String[]{A, B}, new String[]{"Alpha", "Bravo"});
        View picker = openPicker();
        find(picker, ListView.class).performItemClick(null, 1, 1);
        observe(new String[]{A}, new String[]{"Alpha"}); // Home refreshed without Bravo

        awaitRows(picker, "Alpha", "Liked Songs", "Road trip");
        save();

        assertEquals("Pins saved. Restart Spotify to refresh Home.",
                Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getMessage().toString());
        assertTrue(pinned().isEmpty());
    }

    /** Opens the picker over Home's tiles Alpha, Bravo and Charlie, with no library, once {@code pins} are pinned. */
    private View openWithoutTheLibrary(String... pins) throws Exception {
        PlayerBridgeTest.attachRouter(true);
        observe(new String[]{A, B, C}, new String[]{"Alpha", "Bravo", "Charlie"});
        HomePins.setPinned(Arrays.asList(pins));
        View picker = openPicker();
        awaitNote(picker, UNAVAILABLE);
        return picker;
    }

    private static void save() {
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    }

    /** The pins, in pin order. */
    private static List<String> pinned() {
        List<String> ids = new ArrayList<>();
        for (HomePins.Choice choice : HomePins.choices()) if (choice.pinned) ids.add(choice.id);
        return ids;
    }

    /** Opens Spicetify settings, taps "Choose pinned shortcuts", and returns the picker's views. */
    private View openPicker() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SpicetifySettingsScreen.open(activity);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        choose(ShadowDialog.getLatestDialog().getWindow().getDecorView()).performClick();
        return ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView();
    }

    /** Home's tiles, as its shortcut model hands them to HomePins. */
    private static void observe(String[] ids, String[] titles) throws Exception {
        java.lang.reflect.Method capture = HomePins.class.getDeclaredMethod("captureAndOrder", String[].class, String[].class);
        capture.setAccessible(true);
        capture.invoke(null, ids, titles);
    }

    /** Runs the main looper, where the library's answer lands, until the picker lists {@code expected}. */
    private static void awaitRows(View picker, String... expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!rows(picker).equals(Arrays.asList(expected))) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("no rows " + Arrays.asList(expected) + " within 5 s: " + rows(picker),
                    System.currentTimeMillis() < deadline);
            Thread.sleep(10);
        }
    }

    /** Runs the main looper until the picker's note reads {@code text}. */
    private static void awaitNote(View picker, String text) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!text.equals(note(picker).getText().toString())) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("no note \"" + text + "\" within 5 s", System.currentTimeMillis() < deadline);
            Thread.sleep(10);
        }
    }

    private static List<String> rows(View picker) {
        ListView list = find(picker, ListView.class);
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < list.getAdapter().getCount(); i++) rows.add(String.valueOf(list.getAdapter().getItem(i)));
        return rows;
    }

    /** The line under the search field, whichever of its two texts it shows. */
    private static TextView note(View picker) {
        TextView note = labeled(picker, LOADING);
        if (note == null) note = labeled(picker, UNAVAILABLE);
        assertNotNull("the picker has no note under its search field", note);
        return note;
    }

    private Button choose(View view) {
        if (view instanceof Button && "Choose pinned shortcuts".contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button result = choose(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
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
}
