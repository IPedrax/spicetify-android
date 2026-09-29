package app.spicetify.extension.spotify.theme;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.database.DataSetObserver;
import android.os.Looper;
import android.util.Base64;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class MarketplaceScreenTest {
    private static final Executor DIRECT = Runnable::run;

    private static final Marketplace.Repo REPO_A =
            new Marketplace.Repo("ownerA", "repoA", "main", "https://github.com/ownerA/repoA", 100);
    private static final Marketplace.Repo REPO_B =
            new Marketplace.Repo("ownerB", "repoB", "main", "https://github.com/ownerB/repoB", 1);
    /** Galaxy's own color.ini, in short: one scheme. */
    private static final String GALAXY_COLOR_INI = "[base]\ntext = FFFFFF\nmain = 000000\ncard = 000000\nbutton = F1F1F1";

    private final Context context =
            new ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_Material);

    @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

    private final Map<String, String> responses = new HashMap<>();
    // Collaborators a test can swap before showScreen(), for example for a queue it runs by hand.
    private Executor background = DIRECT;
    private Executor downloads = DIRECT;
    private PreviewImages previews = new PreviewImages(url -> {
        throw new IOException("no images in tests");
    }, DIRECT);
    private PreviewImages.Downloader images = url -> {
        throw new IOException("no images in tests");
    };
    /** Every URL the fetcher was asked for, in order; a test's image fetcher can add its own. */
    private final List<String> requested = new ArrayList<>();

    private Marketplace.Fetcher fetcher() {
        return url -> {
            requested.add(url);
            String value = responses.get(url);
            if (value == null) throw new FileNotFoundException(url);
            return value;
        };
    }

    private static String themeJson(String name, String description) {
        return "{\"name\":\"" + name + "\",\"description\":\"" + description
                + "\",\"usercss\":\"u.css\",\"schemes\":\"color.ini\",\"preview\":\"preview.png\"}";
    }

    /** A search page with two repos, and one theme in each repo's manifest. */
    private void putTwoThemes() {
        responses.put(Marketplace.SEARCH_URL + "1", "{\"total_count\":2,\"items\":["
                + "{\"full_name\":\"ownerA/repoA\",\"default_branch\":\"main\","
                + "\"html_url\":\"https://github.com/ownerA/repoA\",\"stargazers_count\":100},"
                + "{\"full_name\":\"ownerB/repoB\",\"default_branch\":\"main\","
                + "\"html_url\":\"https://github.com/ownerB/repoB\",\"stargazers_count\":1}]}");
        responses.put(Marketplace.manifestUrl(REPO_A), themeJson("Aurora", "A vivid theme"));
        responses.put(Marketplace.manifestUrl(REPO_B), themeJson("Borealis", "A cool theme"));
    }

    private Dialog showScreen() {
        File cache = new File(tempFolder.getRoot(), "cache.json");
        MarketplaceLoader loader = new MarketplaceLoader(fetcher(), DIRECT, cache, () -> 0L);
        MarketplaceScreen.show(context, loader, previews, background, downloads, fetcher(), images, () -> {});
        idle();
        return ShadowDialog.getLatestDialog();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void tap(ListView list, int position) {
        list.performItemClick(list.getAdapter().getView(position, null, list), position, position);
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

    /** The texts of the visible text views and buttons under {@code view}. */
    private static List<String> visibleTexts(View view) {
        List<String> texts = new ArrayList<>();
        if (view.getVisibility() != View.VISIBLE) return texts;
        if (view instanceof TextView) texts.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) texts.addAll(visibleTexts(group.getChildAt(i)));
        }
        return texts;
    }

    private static String latestAlertMessage() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        return Shadows.shadowOf(dialog).getMessage().toString();
    }

    @Test
    public void listsThemesAndFiltersWithSearch() {
        putTwoThemes();
        Dialog dialog = showScreen();

        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        assertNotNull(list);
        ListAdapter adapter = list.getAdapter();
        assertEquals(3, adapter.getCount()); // Galaxy V2, pinned above them
        assertEquals("Aurora", ((Marketplace.Theme) adapter.getItem(1)).title);
        assertEquals("Borealis", ((Marketplace.Theme) adapter.getItem(2)).title);
        assertTrue(visibleTexts(adapter.getView(1, null, list)).contains("ownerA, 100 stars"));
        assertTrue(visibleTexts(adapter.getView(2, null, list)).contains("ownerB, 1 star"));

        EditText search = find(dialog.getWindow().getDecorView(), EditText.class);
        assertNotNull(search);
        search.setText("Aurora");

        assertEquals(1, list.getAdapter().getCount());
    }

    @Test
    public void aSearchWithoutMatchesSaysSo() {
        putTwoThemes();
        View screen = showScreen().getWindow().getDecorView();

        find(screen, EditText.class).setText("nothing like this");

        assertEquals(0, find(screen, ListView.class).getAdapter().getCount());
        assertTrue(visibleTexts(screen).contains("No themes match"));
        assertFalse(visibleTexts(screen).contains("Retry"));
    }

    @Test
    public void aLoadWithNoThemesSaysSoAndOffersRetry() {
        responses.put(Marketplace.SEARCH_URL + "1", "{\"total_count\":0,\"items\":[]}");
        View screen = showScreen().getWindow().getDecorView();

        List<String> texts = visibleTexts(screen);
        assertTrue(texts.contains("No themes found"));
        assertTrue(texts.contains("Retry"));
        assertFalse(texts.contains("Loading themes"));
    }

    @Test
    public void aFailedLoadShowsTheErrorAndRetryLoadsAgain() {
        List<Runnable> loads = new ArrayList<>();
        background = loads::add;
        View screen = showScreen().getWindow().getDecorView();
        ProgressBar spinner = find(screen, ProgressBar.class);
        assertTrue(visibleTexts(screen).contains("Loading themes"));
        assertEquals(View.VISIBLE, spinner.getVisibility());

        loads.remove(0).run(); // nothing answers, so the search fails
        idle();
        List<String> texts = visibleTexts(screen);
        assertTrue(texts.contains("Couldn't load the Marketplace: " + Marketplace.SEARCH_URL + "1"));
        assertTrue(texts.contains("Retry"));
        assertEquals(View.GONE, spinner.getVisibility());

        putTwoThemes();
        button(screen, "Retry").performClick();
        texts = visibleTexts(screen);
        assertTrue(texts.contains("Loading themes"));
        assertFalse(texts.contains("Retry"));
        assertEquals(View.VISIBLE, spinner.getVisibility());
        button(screen, "Refresh").performClick(); // ignored while the retry runs
        assertEquals(1, loads.size());

        loads.remove(0).run();
        idle();
        assertEquals(3, find(screen, ListView.class).getAdapter().getCount());
        texts = visibleTexts(screen);
        assertFalse(texts.contains("Loading themes"));
        assertFalse(texts.contains("Retry"));
        assertEquals(View.GONE, spinner.getVisibility());
    }

    @Test
    public void aRefreshKeepsTheListOnScreenUntilItIsDone() {
        putTwoThemes();
        View screen = showScreen().getWindow().getDecorView();
        ListAdapter adapter = find(screen, ListView.class).getAdapter();
        List<List<String>> shown = new ArrayList<>();
        adapter.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                List<String> titles = new ArrayList<>();
                for (int i = 0; i < adapter.getCount(); i++) titles.add(((Marketplace.Theme) adapter.getItem(i)).title);
                shown.add(titles);
            }
        });
        responses.put(Marketplace.manifestUrl(REPO_A),
                "[" + themeJson("Aurora", "A vivid theme") + "," + themeJson("Andromeda", "A far theme") + "]");

        button(screen, "Refresh").performClick();
        idle();

        // repoA's manifest arrives first, but the list only changes once the refresh is done.
        assertTrue(shown.size() >= 3); // the refresh's updates reached the screen
        for (List<String> titles : shown.subList(0, shown.size() - 1)) {
            assertEquals(Arrays.asList("Galaxy V2", "Aurora", "Borealis"), titles);
        }
        assertEquals(Arrays.asList("Galaxy V2", "Aurora", "Andromeda", "Borealis"), shown.get(shown.size() - 1));
    }

    @Test
    public void tappingAThemeDownloadsSchemesAndShowsTheChooser() {
        putTwoThemes();
        responses.put(Marketplace.resolve("color.ini", REPO_A, "main"),
                "[a]\nmain = 000000\n[b]\nmain = ffffff");
        Dialog dialog = showScreen();

        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        assertNotNull(list);
        tap(list, 1);
        idle();

        AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(chooser);
        assertEquals("Choose a color scheme", Shadows.shadowOf(chooser).getTitle());
        CharSequence[] items = Shadows.shadowOf(chooser).getItems();
        assertEquals(2, items.length);
        assertEquals("a", items[0]);
        assertEquals("b", items[1]);
    }

    @Test
    public void aTapDownloadsOnItsOwnExecutorAndIgnoresTapsUntilItIsDone() {
        putTwoThemes();
        responses.put(Marketplace.resolve("color.ini", REPO_A, "main"), "[a]\nmain = 000000\n[b]\nmain = ffffff");
        List<Runnable> loads = new ArrayList<>();
        List<Runnable> themeDownloads = new ArrayList<>();
        background = loads::add;
        downloads = themeDownloads::add;
        View screen = showScreen().getWindow().getDecorView();
        loads.remove(0).run();
        idle();
        button(screen, "Refresh").performClick(); // a load is running from here on
        ListView list = find(screen, ListView.class);

        tap(list, 1);
        tap(list, 2); // ignored: Aurora is still downloading
        assertEquals(1, themeDownloads.size());
        assertEquals(2, ShadowToast.shownToastCount());
        assertEquals("Loading Aurora", ShadowToast.getTextOfLatestToast());

        themeDownloads.remove(0).run();
        idle();
        assertEquals("Choose a color scheme", Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getTitle());
        assertEquals(1, loads.size()); // the chooser didn't wait for the refresh
        tap(list, 2);
        assertEquals(1, themeDownloads.size());
    }

    @Test
    public void aDownloadThatEndsAfterTheMarketplaceClosedShowsNothing() {
        putTwoThemes();
        responses.put(Marketplace.resolve("color.ini", REPO_A, "main"), "[a]\nmain = 000000\n[b]\nmain = ffffff");
        List<Runnable> themeDownloads = new ArrayList<>();
        downloads = themeDownloads::add;
        Dialog dialog = showScreen();
        tap(find(dialog.getWindow().getDecorView(), ListView.class), 1);

        dialog.dismiss();
        themeDownloads.remove(0).run();
        idle();

        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test
    public void closingTheMarketplaceDropsQueuedPreviews() {
        putTwoThemes();
        List<Runnable> queued = new ArrayList<>();
        List<String> downloaded = new ArrayList<>();
        previews = new PreviewImages(url -> {
            downloaded.add(url);
            throw new IOException("no images in tests");
        }, queued::add);
        Dialog dialog = showScreen();
        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        list.getAdapter().getView(0, null, list); // binding a row queues its preview

        dialog.dismiss();
        idle(); // Dialog calls its dismiss listener from a posted message
        for (Runnable task : queued) task.run();

        assertFalse(queued.isEmpty());
        assertTrue(downloaded.isEmpty());
    }

    @Test
    public void explainsAMissingColorIniAndOneWithoutSchemes() {
        putTwoThemes(); // repoA has no color.ini
        responses.put(Marketplace.resolve("color.ini", REPO_B, "main"), "; only a comment");
        ListView list = find(showScreen().getWindow().getDecorView(), ListView.class);

        tap(list, 1);
        idle();
        assertEquals("Aurora has no color schemes to use on Android.", latestAlertMessage());

        tap(list, 2);
        idle();
        assertEquals("Borealis: No color schemes found", latestAlertMessage());
    }

    @Test
    public void pinsGalaxyV2AboveTheGitHubResultsAndSearchFindsIt() {
        putTwoThemes();
        View screen = showScreen().getWindow().getDecorView();
        ListView list = find(screen, ListView.class);

        Marketplace.Theme galaxy = (Marketplace.Theme) list.getAdapter().getItem(0);
        assertEquals("Galaxy V2", galaxy.title);
        // Its stars aren't known, so its row names the author alone.
        assertTrue(visibleTexts(list.getAdapter().getView(0, null, list)).contains("harbassan"));

        find(screen, EditText.class).setText("galaxy");
        assertEquals(1, list.getAdapter().getCount());
        assertSame(galaxy, list.getAdapter().getItem(0));
    }

    @Test
    public void tappingGalaxyV2DownloadsItsImageAfterItsSchemesAndAppliesBoth() {
        putTwoThemes();
        responses.put(Marketplace.GALAXY_V2.schemesUrl, GALAXY_COLOR_INI);
        byte[] png = ThemeBackgroundTest.png();
        images = url -> {
            requested.add(url);
            return png;
        };
        ListView list = find(showScreen().getWindow().getDecorView(), ListView.class);

        tap(list, 0);
        idle();

        assertEquals(Arrays.asList(Marketplace.GALAXY_V2.schemesUrl, Marketplace.GALAXY_V2.backgroundUrl),
                requested.subList(requested.size() - 2, requested.size()));
        assertTrue(ThemeBackground.hasImage(context));
        // Unpatched, the extension has no role table, so select fails once the image is saved.
        assertEquals("This device couldn't apply the theme.", latestAlertMessage());
    }

    @Test
    public void aBackgroundImageThatFailsToDownloadAppliesNothing() {
        putTwoThemes();
        responses.put(Marketplace.GALAXY_V2.schemesUrl, GALAXY_COLOR_INI);
        images = url -> {
            throw new IOException("HTTP 500 for " + url);
        };
        ListView list = find(showScreen().getWindow().getDecorView(), ListView.class);

        tap(list, 0);
        idle();

        assertEquals("Couldn't download Galaxy V2's background image: HTTP 500 for "
                + Marketplace.GALAXY_V2.backgroundUrl, latestAlertMessage());
        assertFalse(ThemeBackground.hasImage(context));
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Android's own decoder, which reads an image's real size
    public void aThemeBringsTheImageItsScriptNamesAndAThemeWithoutOneClearsIt() {
        putTwoThemes();
        responses.put(Marketplace.manifestUrl(REPO_A), "{\"name\":\"Aurora\",\"description\":\"A vivid theme\","
                + "\"usercss\":\"u.css\",\"schemes\":\"color.ini\",\"include\":[\"missing.js\",\"hazy.js\"]}");
        responses.put(Marketplace.resolve("hazy.js", REPO_A, "main"), "const defImage = \"https://i.imgur.com/Wl2D0h0.png\";");
        responses.put(Marketplace.resolve("color.ini", REPO_A, "main"), GALAXY_COLOR_INI);
        responses.put(Marketplace.resolve("color.ini", REPO_B, "main"), GALAXY_COLOR_INI);
        responses.put(Marketplace.resolve("u.css", REPO_B, "main"), ".Root__main-view { background-color: transparent; }");
        byte[] png = ThemeBackgroundTest.png(1200, 675); // Galaxy's size
        images = url -> {
            requested.add(url);
            return png;
        };
        ListView list = find(showScreen().getWindow().getDecorView(), ListView.class);

        tap(list, 1);
        idle();
        // missing.js failing to download only means it names no image, and the script's image wins over user.css.
        assertEquals("https://i.imgur.com/Wl2D0h0.png", requested.get(requested.size() - 1));
        assertFalse(requested.contains(Marketplace.resolve("u.css", REPO_A, "main")));
        // Saved, so applyScheme made the colors see-through; unpatched, select then fails.
        assertTrue(ThemeBackground.hasImage(context));
        assertEquals("This device couldn't apply the theme.", latestAlertMessage());

        tap(list, 2);
        idle();
        assertFalse(ThemeBackground.hasImage(context));
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Android's own decoder, which reads an image's real size
    public void aDataUriImageIsSavedOnlyWhenItIsAtLeast480PxOnEachSide() throws IOException {
        putTwoThemes();
        byte[] png = ThemeBackgroundTest.png(480, 480);
        responses.put(Marketplace.resolve("color.ini", REPO_A, "main"), GALAXY_COLOR_INI);
        responses.put(Marketplace.resolve("u.css", REPO_A, "main"),
                "body { background: url(" + dataUri(ThemeBackgroundTest.png(480, 479)) + "); }");
        responses.put(Marketplace.resolve("color.ini", REPO_B, "main"), GALAXY_COLOR_INI);
        responses.put(Marketplace.resolve("u.css", REPO_B, "main"),
                ".Root__top-container { background-image: url(\"" + dataUri(png) + "\") !important; }");
        ListView list = find(showScreen().getWindow().getDecorView(), ListView.class);

        tap(list, 1); // a pixel short on one side
        idle();
        assertFalse(ThemeBackground.hasImage(context));

        tap(list, 2);
        idle();
        assertArrayEquals(png, Files.readAllBytes(new File(context.getFilesDir(), "spicetify_background").toPath()));
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Android's own decoder, which reads an image's real size
    public void aTextureTileOnTheWindowIsNoBackground() throws IOException {
        putTwoThemes();
        ThemeBackground.save(context, ThemeBackgroundTest.png(480, 480)); // from the theme before
        responses.put(Marketplace.resolve("color.ini", REPO_A, "main"), GALAXY_COLOR_INI);
        // Spotify Dark's shape: a 70x70 tile in a :root variable, repeated over the top container.
        responses.put(Marketplace.resolve("u.css", REPO_A, "main"), ":root { --bgDarkness3: url('"
                + dataUri(ThemeBackgroundTest.png(70, 70)) + "'); }\n.main-view-container, .Root__top-container "
                + "{ background-image: var(--bgDarkness3)!important; background-repeat: repeat!important; }");
        ListView list = find(showScreen().getWindow().getDecorView(), ListView.class);

        tap(list, 1);
        idle();

        // The theme applied without an image, and no toast said so.
        assertFalse(ThemeBackground.hasImage(context));
        assertEquals("This device couldn't apply the theme.", latestAlertMessage());
        assertEquals("Loading Aurora", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void aThemesOwnImageThatFailsToDownloadIsLeftOutWithAToast() throws IOException {
        putTwoThemes();
        ThemeBackground.save(context, ThemeBackgroundTest.png()); // from the theme before
        responses.put(Marketplace.resolve("color.ini", REPO_A, "main"), GALAXY_COLOR_INI);
        responses.put(Marketplace.resolve("u.css", REPO_A, "main"), ".Root { background: url(bg.jpg); }");
        images = url -> {
            throw new IOException("HTTP 500 for " + url);
        };
        ListView list = find(showScreen().getWindow().getDecorView(), ListView.class);

        tap(list, 1);
        idle();

        assertEquals("Couldn't load Aurora's background image: HTTP 500 for "
                + Marketplace.resolve("bg.jpg", REPO_A, "main"), ShadowToast.getTextOfLatestToast());
        // The theme still applied, without an image.
        assertFalse(ThemeBackground.hasImage(context));
        assertEquals("This device couldn't apply the theme.", latestAlertMessage());
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Android's own decoder, which refuses what isn't an image
    public void aThemesOwnImageAndroidCantReadIsLeftOutWithAToast() throws IOException {
        putTwoThemes();
        ThemeBackground.save(context, ThemeBackgroundTest.png(480, 480)); // from the theme before
        responses.put(Marketplace.resolve("color.ini", REPO_A, "main"), GALAXY_COLOR_INI);
        responses.put(Marketplace.resolve("u.css", REPO_A, "main"), ".Root { background: url(bg.jpg); }");
        images = url -> "<html>Not Found</html>".getBytes(StandardCharsets.UTF_8);
        ListView list = find(showScreen().getWindow().getDecorView(), ListView.class);

        tap(list, 1);
        idle();

        assertEquals("Couldn't load Aurora's background image: Not an image Android can read",
                ShadowToast.getTextOfLatestToast());
        // The theme still applied without an image, instead of stopping at ThemeBackground.save.
        assertFalse(ThemeBackground.hasImage(context));
        assertEquals("This device couldn't apply the theme.", latestAlertMessage());
    }

    private static String dataUri(byte[] png) {
        return "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP);
    }
}
