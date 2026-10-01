package app.spicetify.extension.spotify.theme;

import static org.junit.Assert.assertArrayEquals;
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
import android.content.Intent;
import android.database.DataSetObserver;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.util.Base64;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import app.spicetify.extension.spotify.extensions.Extensions;
import app.spicetify.extension.spotify.settings.InstalledPatches;
import app.spicetify.extension.spotify.settings.SpicetifySettingsScreen;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
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
    private static final Marketplace.Repo CLI =
            new Marketplace.Repo("spicetify", "cli", "main", "https://github.com/spicetify/cli", 1);
    private static final Marketplace.Repo DUSK =
            new Marketplace.Repo("a", "dusk", "main", "https://github.com/a/dusk", 3);
    /** The cards pinned above the GitHub results on the Themes tab, in order. */
    private static final List<String> PINNED_TITLES = Arrays.asList("Spotify default", "AMOLED black",
            "Material You", "Material You, black background", "Galaxy V2", "Paste a Spicetify theme");
    private static final int PINNED = PINNED_TITLES.size();
    private static final int GALAXY = PINNED_TITLES.indexOf("Galaxy V2");
    /** Galaxy's own color.ini, in short: one scheme. */
    private static final String GALAXY_COLOR_INI = "[base]\ntext = FFFFFF\nmain = 000000\ncard = 000000\nbutton = F1F1F1";

    private final Context context =
            new ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_Material);

    @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

    private final Map<String, String> responses = new HashMap<>();
    // Collaborators a test can swap before showScreen(), for example for a queue it runs by hand.
    private Context screenContext = context;
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
    /** Work the fetcher does when it's asked for a URL, before it answers; each runs once. */
    private final Map<String, Runnable> onRequest = new HashMap<>();
    /** How many times the Marketplace told its caller it closed. */
    private final AtomicInteger closed = new AtomicInteger();

    @Before
    public void setUp() {
        // Touch mode is the window manager's, so it would outlive the test that turns it on.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
    }

    private Marketplace.Fetcher fetcher() {
        return url -> {
            requested.add(url);
            Runnable work = onRequest.remove(url);
            if (work != null) work.run();
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

    /**
     * An extensions search page with two repos: spicetify/cli, whose Trash Bin has an Android port,
     * and a/dusk, with a theme and a desktop-only extension.
     */
    private void putExtensions() {
        responses.put(Marketplace.searchUrl(Marketplace.EXTENSIONS_TOPIC) + "1", "{\"total_count\":2,\"items\":["
                + "{\"full_name\":\"a/dusk\",\"default_branch\":\"main\","
                + "\"html_url\":\"https://github.com/a/dusk\",\"stargazers_count\":3},"
                + "{\"full_name\":\"spicetify/cli\",\"default_branch\":\"main\","
                + "\"html_url\":\"https://github.com/spicetify/cli\",\"stargazers_count\":1}]}");
        responses.put(Marketplace.manifestUrl(DUSK), "[" + themeJson("Dusk", "A dim theme")
                + ",{\"name\":\"Lyrics\",\"description\":\"Lyrics beside the player\",\"main\":\"lyrics.js\"}]");
        responses.put(Marketplace.manifestUrl(CLI), "{\"name\":\"Trash Bin\",\"description\":\"Skip trashed songs\","
                + "\"main\":\"Extensions/trashbin.js\",\"authors\":[{\"name\":\"a\"}]}");
    }

    private Dialog showScreen() {
        File cache = new File(tempFolder.getRoot(), "cache.json");
        MarketplaceLoader loader = new MarketplaceLoader(fetcher(), DIRECT, cache, () -> 0L);
        MarketplaceScreen.show(screenContext, loader, previews, background, downloads, fetcher(), images, () -> {},
                closed::incrementAndGet);
        idle();
        return ShadowDialog.getLatestDialog();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * Shows the Marketplace with nothing to load and no network, so only its built-in cards; for tests
     * in other packages, such as Spicetify settings'. It has the shape of {@link MarketplaceScreen#open}.
     */
    public static void showOffline(Context context, Runnable onApplied, Runnable onClosed) {
        Marketplace.Fetcher offline = url -> {
            throw new FileNotFoundException(url);
        };
        PreviewImages.Downloader noImages = url -> {
            throw new IOException("no images in tests");
        };
        File cache = new File(context.getCacheDir(), "offline_marketplace.json");
        MarketplaceScreen.show(context, new MarketplaceLoader(offline, DIRECT, cache, () -> 0L),
                new PreviewImages(noImages, DIRECT), DIRECT, DIRECT, offline, noImages, onApplied, onClosed);
    }

    private static void tap(ListView list, int position) {
        list.performItemClick(list.getAdapter().getView(position, null, list), position, position);
    }

    private static List<String> titles(ListAdapter adapter) {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < adapter.getCount(); i++) titles.add(((Marketplace.Theme) adapter.getItem(i)).title);
        return titles;
    }

    /** The Themes tab's pinned titles, then {@code titles}. */
    private static List<String> listed(String... titles) {
        List<String> all = new ArrayList<>(PINNED_TITLES);
        all.addAll(Arrays.asList(titles));
        return all;
    }

    private static int position(ListView list, String title) {
        int position = titles(list.getAdapter()).indexOf(title);
        assertTrue(title + " is listed", position >= 0);
        return position;
    }

    /** A newly bound row for the item titled {@code title}. */
    private static View row(ListView list, String title) {
        return list.getAdapter().getView(position(list, title), null, list);
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

    /** The tab labeled {@code label}, Themes or Extensions. */
    private static TextView tab(View screen, String label) {
        TextView tab = labeled(screen, label);
        assertNotNull("a tab labeled " + label, tab);
        return tab;
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

    /** The colors of the visible plain color views under {@code view}, such as a preset card's strip, in order. */
    static List<Integer> colorBlocks(View view) {
        List<Integer> colors = new ArrayList<>();
        if (view.getVisibility() != View.VISIBLE) return colors;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) colors.addAll(colorBlocks(group.getChildAt(i)));
        } else if (view.getBackground() instanceof ColorDrawable) {
            colors.add(((ColorDrawable) view.getBackground()).getColor());
        }
        return colors;
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
        assertEquals(listed("Aurora", "Borealis"), titles(adapter));
        assertTrue(visibleTexts(adapter.getView(PINNED, null, list)).contains("ownerA, 100 stars"));
        assertTrue(visibleTexts(adapter.getView(PINNED + 1, null, list)).contains("ownerB, 1 star"));

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
        // No extensions either, but no failure: a notice would take the place of "No themes found".
        responses.put(Marketplace.searchUrl(Marketplace.EXTENSIONS_TOPIC) + "1", "{\"total_count\":0,\"items\":[]}");
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
        tab(screen, "Extensions").performClick(); // the error shows on both tabs
        texts = visibleTexts(screen);
        assertTrue(texts.contains("Couldn't load the Marketplace: " + Marketplace.SEARCH_URL + "1"));
        assertTrue(texts.contains("Retry"));
        tab(screen, "Themes").performClick();

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
        assertEquals(PINNED + 2, find(screen, ListView.class).getAdapter().getCount());
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
                shown.add(titles(adapter));
            }
        });
        responses.put(Marketplace.manifestUrl(REPO_A),
                "[" + themeJson("Aurora", "A vivid theme") + "," + themeJson("Andromeda", "A far theme") + "]");

        button(screen, "Refresh").performClick();
        idle();

        // repoA's manifest arrives first, but the list only changes once the refresh is done.
        assertTrue(shown.size() >= 3); // the refresh's updates reached the screen
        for (List<String> titles : shown.subList(0, shown.size() - 1)) {
            assertEquals(listed("Aurora", "Borealis"), titles);
        }
        assertEquals(listed("Aurora", "Andromeda", "Borealis"), shown.get(shown.size() - 1));
    }

    @Test
    public void tappingAThemeDownloadsSchemesAndShowsTheChooser() {
        putTwoThemes();
        responses.put(Marketplace.resolve("color.ini", REPO_A, "main"),
                "[a]\nmain = 000000\n[b]\nmain = ffffff");
        Dialog dialog = showScreen();

        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        assertNotNull(list);
        tap(list, PINNED);
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

        tap(list, PINNED);
        tap(list, PINNED + 1); // ignored: Aurora is still downloading
        assertEquals(1, themeDownloads.size());
        assertEquals(2, ShadowToast.shownToastCount());
        assertEquals("Loading Aurora", ShadowToast.getTextOfLatestToast());

        themeDownloads.remove(0).run();
        idle();
        assertEquals("Choose a color scheme", Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getTitle());
        assertEquals(1, loads.size()); // the chooser didn't wait for the refresh
        tap(list, PINNED + 1);
        assertEquals(1, themeDownloads.size());
    }

    @Test
    public void aDownloadThatEndsAfterTheMarketplaceClosedShowsNothing() {
        putTwoThemes();
        responses.put(Marketplace.resolve("color.ini", REPO_A, "main"), "[a]\nmain = 000000\n[b]\nmain = ffffff");
        List<Runnable> themeDownloads = new ArrayList<>();
        downloads = themeDownloads::add;
        Dialog dialog = showScreen();
        tap(find(dialog.getWindow().getDecorView(), ListView.class), PINNED);

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
        list.getAdapter().getView(GALAXY, null, list); // binding a row queues its preview

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

        tap(list, PINNED);
        idle();
        assertEquals("Aurora has no color schemes to use on Android.", latestAlertMessage());

        tap(list, PINNED + 1);
        idle();
        assertEquals("Borealis: No color schemes found", latestAlertMessage());
    }

    @Test
    public void pinsGalaxyV2AboveTheGitHubResultsAndSearchFindsIt() {
        putTwoThemes();
        View screen = showScreen().getWindow().getDecorView();
        ListView list = find(screen, ListView.class);

        Marketplace.Theme galaxy = (Marketplace.Theme) list.getAdapter().getItem(GALAXY);
        assertEquals("Galaxy V2", galaxy.title);
        // Its stars aren't known, so its row names the author alone.
        assertTrue(visibleTexts(list.getAdapter().getView(GALAXY, null, list)).contains("harbassan"));

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

        tap(list, GALAXY);
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

        tap(list, GALAXY);
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

        tap(list, PINNED);
        idle();
        // missing.js failing to download only means it names no image, and the script's image wins over user.css.
        assertEquals("https://i.imgur.com/Wl2D0h0.png", requested.get(requested.size() - 1));
        assertFalse(requested.contains(Marketplace.resolve("u.css", REPO_A, "main")));
        // Saved, so applyScheme made the colors see-through; unpatched, select then fails.
        assertTrue(ThemeBackground.hasImage(context));
        assertEquals("This device couldn't apply the theme.", latestAlertMessage());

        tap(list, PINNED + 1);
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

        tap(list, PINNED); // a pixel short on one side
        idle();
        assertFalse(ThemeBackground.hasImage(context));

        tap(list, PINNED + 1);
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

        tap(list, PINNED);
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

        tap(list, PINNED);
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

        tap(list, PINNED);
        idle();

        assertEquals("Couldn't load Aurora's background image: Not an image Android can read",
                ShadowToast.getTextOfLatestToast());
        // The theme still applied without an image, instead of stopping at ThemeBackground.save.
        assertFalse(ThemeBackground.hasImage(context));
        assertEquals("This device couldn't apply the theme.", latestAlertMessage());
    }

    @Test
    public void theThemesTabOpensFirstWithOnlyThemesAndThePresetsFirst() {
        putTwoThemes();
        putExtensions();
        View screen = showScreen().getWindow().getDecorView();
        ListView list = find(screen, ListView.class);

        assertTrue(tab(screen, "Themes").isSelected());
        assertFalse(tab(screen, "Extensions").isSelected());
        assertEquals("Search themes", find(screen, EditText.class).getHint().toString());
        // The presets and Galaxy V2, then the themes by stars, Dusk from the extensions search among them.
        assertEquals(listed("Aurora", "Dusk", "Borealis"), titles(list.getAdapter()));

        // Search filters the pinned cards like the rest.
        find(screen, EditText.class).setText("black");
        assertEquals(Arrays.asList("AMOLED black", "Material You, black background"), titles(list.getAdapter()));
    }

    @Test
    public void theExtensionsTabListsTheAndroidOnlyCardsThenAndroidExtensionsThenDesktopOnlyOnes() {
        putTwoThemes();
        putExtensions();
        // Two more: a desktop-only extension in a theme's repository, with the most stars, and Hide
        // podcasts, whose repository has more stars than Trash Bin's.
        responses.put(Marketplace.manifestUrl(REPO_A), "[" + themeJson("Aurora", "A vivid theme")
                + ",{\"name\":\"Visualizer\",\"description\":\"Bars that move\",\"main\":\"v.js\"}]");
        Marketplace.Repo podcasts = new Marketplace.Repo("theRealPadster", "spicetify-hide-podcasts", "main",
                "https://github.com/theRealPadster/spicetify-hide-podcasts", 2);
        responses.put(Marketplace.searchUrl(Marketplace.EXTENSIONS_TOPIC) + "1", "{\"total_count\":3,\"items\":["
                + "{\"full_name\":\"a/dusk\",\"default_branch\":\"main\","
                + "\"html_url\":\"https://github.com/a/dusk\",\"stargazers_count\":3},"
                + "{\"full_name\":\"theRealPadster/spicetify-hide-podcasts\",\"default_branch\":\"main\","
                + "\"html_url\":\"" + podcasts.url + "\",\"stargazers_count\":2},"
                + "{\"full_name\":\"spicetify/cli\",\"default_branch\":\"main\","
                + "\"html_url\":\"https://github.com/spicetify/cli\",\"stargazers_count\":1}]}");
        responses.put(Marketplace.manifestUrl(podcasts),
                "{\"name\":\"Hide Podcasts\",\"description\":\"No podcasts\",\"main\":\"hidePodcasts.js\"}");
        View screen = showScreen().getWindow().getDecorView();
        ListView list = find(screen, ListView.class);

        tab(screen, "Extensions").performClick();

        assertTrue(tab(screen, "Extensions").isSelected());
        assertFalse(tab(screen, "Themes").isSelected());
        assertEquals("Search extensions", find(screen, EditText.class).getHint().toString());
        // Each group by stars: Hide podcasts, then Trash Bin; then Visualizer, then Lyrics. Unavailable
        // songs is hidden, so it never joins Play a random song as a pinned card.
        assertEquals(Arrays.asList("Play a random song", "Hide Podcasts", "Trash Bin", "Visualizer", "Lyrics"),
                titles(list.getAdapter()));
        Marketplace.Theme random = (Marketplace.Theme) list.getAdapter().getItem(0);
        assertEquals(Marketplace.Kind.EXTENSION, random.kind);
        assertEquals(Extensions.RANDOM_SONG, random.androidId);
        assertEquals("Spicetify for Android", random.author);
        assertEquals(-1, random.stars);
        assertNull(random.previewUrl);
    }

    @Test
    public void theExtensionsTabHasNoUnavailableSongsCardWhileItIsHidden() {
        putExtensions();
        View screen = showScreen().getWindow().getDecorView();

        tab(screen, "Extensions").performClick();

        assertTrue(Extensions.isHidden(Extensions.UNAVAILABLE_SONGS));
        assertFalse(titles(find(screen, ListView.class).getAdapter()).contains("Unavailable songs"));
    }

    @Test
    public void searchStaysWithinTheTab() {
        putTwoThemes();
        putExtensions();
        View screen = showScreen().getWindow().getDecorView();
        ListView list = find(screen, ListView.class);
        EditText search = find(screen, EditText.class);

        search.setText("spotify"); // two presets and Play a random song say it
        assertEquals(Arrays.asList("Spotify default", "Material You"), titles(list.getAdapter()));
        tab(screen, "Extensions").performClick();
        assertEquals(Collections.singletonList("Play a random song"), titles(list.getAdapter()));

        search.setText("dim"); // only Dusk, a theme, says it
        assertEquals(Collections.emptyList(), titles(list.getAdapter()));
        assertTrue(visibleTexts(screen).contains("No extensions match"));
        tab(screen, "Themes").performClick();
        assertEquals(Collections.singletonList("Dusk"), titles(list.getAdapter()));
    }

    @Test
    public void switchingTabsKeepsTheSearchText() {
        putTwoThemes();
        putExtensions();
        View screen = showScreen().getWindow().getDecorView();
        ListView list = find(screen, ListView.class);
        EditText search = find(screen, EditText.class);
        search.setText("song");
        assertTrue(visibleTexts(screen).contains("No themes match"));

        tab(screen, "Extensions").performClick();
        assertEquals("song", search.getText().toString());
        assertEquals(Arrays.asList("Play a random song", "Trash Bin"), titles(list.getAdapter()));

        tab(screen, "Themes").performClick();
        assertEquals("song", search.getText().toString());
        assertEquals(Collections.emptyList(), titles(list.getAdapter()));
        assertTrue(visibleTexts(screen).contains("No themes match"));
    }

    @Test
    public void aTabStartsAtItsTop() {
        putTwoThemes();
        putExtensions();
        // As on a phone: in touch mode, a list keeps its position when its items change.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true);
        View screen = showScreen().getWindow().getDecorView();
        ListView list = find(screen, ListView.class);
        list.setSelection(PINNED); // down to the GitHub themes
        idle();
        assertEquals(PINNED, list.getFirstVisiblePosition());

        tab(screen, "Extensions").performClick();
        idle();

        assertEquals(0, list.getFirstVisiblePosition());
    }

    @Test
    public void theHintAndTheStatusFollowTheTab() {
        putTwoThemes();
        responses.put(Marketplace.searchUrl(Marketplace.EXTENSIONS_TOPIC) + "1", "{\"total_count\":0,\"items\":[]}");
        View screen = showScreen().getWindow().getDecorView();
        EditText search = find(screen, EditText.class);
        assertFalse(visibleTexts(screen).contains("Retry"));

        tab(screen, "Extensions").performClick();
        assertEquals("Search extensions", search.getHint().toString());
        List<String> texts = visibleTexts(screen);
        assertTrue(texts.contains("No extensions found"));
        assertTrue(texts.contains("Retry"));

        tab(screen, "Themes").performClick();
        assertEquals("Search themes", search.getHint().toString());
        texts = visibleTexts(screen);
        assertFalse(texts.contains("No extensions found"));
        assertFalse(texts.contains("Retry"));
    }

    @Test
    public void theExtensionsTabSaysLoadingExtensionsWhileOnlyThemesHaveArrived() {
        putTwoThemes();
        putExtensions();
        List<Runnable> loads = new ArrayList<>();
        background = loads::add;
        View screen = showScreen().getWindow().getDecorView();
        ListView list = find(screen, ListView.class);
        List<String> themes = new ArrayList<>();
        List<String> themesTexts = new ArrayList<>();
        List<String> extensions = new ArrayList<>();
        List<String> extensionsTexts = new ArrayList<>();
        // The extensions search starts once the theme repositories' manifests are all in.
        onRequest.put(Marketplace.searchUrl(Marketplace.EXTENSIONS_TOPIC) + "1", () -> {
            idle(); // their updates reach the screen
            themes.addAll(titles(list.getAdapter()));
            themesTexts.addAll(visibleTexts(screen));
            tab(screen, "Extensions").performClick();
            extensions.addAll(titles(list.getAdapter()));
            extensionsTexts.addAll(visibleTexts(screen));
        });

        loads.remove(0).run();

        assertEquals(listed("Aurora", "Borealis"), themes);
        // Dusk, a theme, comes with the extensions, so the Themes tab is still loading too.
        assertTrue(themesTexts.contains("Loading themes"));
        assertEquals(Collections.singletonList("Play a random song"), extensions);
        assertTrue(extensionsTexts.contains("Loading extensions"));
        assertFalse(extensionsTexts.contains("No extensions found"));
        idle();
        assertEquals(Arrays.asList("Play a random song", "Trash Bin", "Lyrics"), titles(list.getAdapter()));
        assertFalse(visibleTexts(screen).contains("Loading extensions"));
        tab(screen, "Themes").performClick();
        assertEquals(listed("Aurora", "Dusk", "Borealis"), titles(list.getAdapter()));
    }

    @Test
    public void aPresetCardShowsAStripOfItsColorsInsteadOfAnImage() {
        putTwoThemes();
        ListView list = find(showScreen().getWindow().getDecorView(), ListView.class);

        assertEquals(Arrays.asList(0xFF121212, 0xFF282828, 0xFF1ED760, 0xFFFFFFFF),
                colorBlocks(row(list, "Spotify default")));
        // AMOLED only sets the background; the roles it leaves out keep Spotify's colors.
        assertEquals(Arrays.asList(0xFF000000, 0xFF282828, 0xFF1ED760, 0xFFFFFFFF),
                colorBlocks(row(list, "AMOLED black")));
        Map<String, Integer> black = ThemePresets.materialYou(context, true);
        assertEquals(Arrays.asList(black.get("main"), black.get("card"), black.get("button"), black.get("text")),
                colorBlocks(row(list, "Material You, black background")));
        assertEquals(View.GONE, find(row(list, "AMOLED black"), ImageView.class).getVisibility());
        // Any other card shows its preview image, on its placeholder color, and no strip.
        assertEquals(Collections.singletonList(0xFF282828), colorBlocks(row(list, "Galaxy V2")));
    }

    @Test
    public void thePasteCardFollowsGalaxyV2AndOpensThePasteDialog() {
        putTwoThemes();
        ListView list = find(showScreen().getWindow().getDecorView(), ListView.class);
        assertEquals(GALAXY + 1, position(list, "Paste a Spicetify theme"));

        tap(list, position(list, "Paste a Spicetify theme"));

        AlertDialog paste = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(paste);
        assertEquals("Paste a Spicetify theme", Shadows.shadowOf(paste).getTitle());
        EditText colors = find(paste.getWindow().getDecorView(), EditText.class);
        assertEquals("Paste a color.ini, or CSS with --spice-* colors", colors.getHint().toString());

        // Apply still says why it can't use what was pasted.
        colors.setText("; only a comment");
        paste.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals("No color schemes found", latestAlertMessage());
    }

    @Test
    public void closingTheMarketplaceTellsItsCaller() {
        putTwoThemes();
        Dialog marketplace = showScreen();
        assertEquals(0, closed.get());

        marketplace.dismiss();
        idle(); // Dialog calls its dismiss listener from a posted message

        assertEquals(1, closed.get());
    }

    @Test
    public void tappingAPresetCardAppliesIt() throws IOException {
        putTwoThemes();
        ThemeBackground.save(context, ThemeBackgroundTest.png()); // from the theme before
        Dialog marketplace = showScreen();
        ListView list = find(marketplace.getWindow().getDecorView(), ListView.class);

        tap(list, position(list, "AMOLED black"));

        // A preset clears the image, then selects. Unpatched, select fails, as ThemeSectionTest
        // shows, so nothing is saved and the Marketplace stays open behind the error.
        assertFalse(ThemeBackground.hasImage(context));
        assertEquals("This device couldn't apply the theme.", latestAlertMessage());
        assertTrue(marketplace.isShowing());
    }

    @Test
    public void aRowLeavesItsKindToTheTab() {
        putTwoThemes();
        putExtensions();
        View screen = showScreen().getWindow().getDecorView();
        ListView list = find(screen, ListView.class);

        assertTrue(visibleTexts(row(list, "Dusk")).contains("a, 3 stars"));
        // Built-in cards have no stars, so they name the author alone.
        assertTrue(visibleTexts(row(list, "AMOLED black")).contains("Spicetify for Android"));
        tab(screen, "Extensions").performClick();
        assertTrue(visibleTexts(row(list, "Trash Bin")).contains("a, 1 star"));
        assertTrue(visibleTexts(row(list, "Lyrics")).contains("a, 3 stars · Desktop only"));
        assertTrue(visibleTexts(row(list, "Play a random song")).contains("Spicetify for Android"));
    }

    @Test
    public void anAndroidExtensionsCardSaysWhatItDoesOnAndroidAndADesktopOnlyOneKeepsItsManifestText() {
        putTwoThemes();
        putExtensions();
        responses.put(Marketplace.manifestUrl(CLI), "{\"name\":\"Shuffle+\",\"description\":\"True shuffle on desktop\","
                + "\"main\":\"Extensions/shuffle+.js\"}");
        View screen = showScreen().getWindow().getDecorView();
        ListView list = find(screen, ListView.class);
        tab(screen, "Extensions").performClick();

        List<String> shuffle = visibleTexts(row(list, "Shuffle+"));
        assertTrue(shuffle.toString(), shuffle.contains(Extensions.description(Extensions.SHUFFLE_PLUS)));
        assertFalse(shuffle.contains("True shuffle on desktop"));
        assertTrue(visibleTexts(row(list, "Lyrics")).contains("Lyrics beside the player"));
    }

    @Test
    public void anAndroidExtensionsSwitchTurnsItOnAndTheMarketplaceStaysOpen() {
        putTwoThemes();
        putExtensions();
        Dialog marketplace = showScreen();
        ListView list = find(marketplace.getWindow().getDecorView(), ListView.class);
        tab(marketplace.getWindow().getDecorView(), "Extensions").performClick();
        View row = row(list, "Trash Bin");
        Switch toggle = find(row, Switch.class);
        assertEquals(View.VISIBLE, toggle.getVisibility());
        assertFalse(toggle.isChecked());
        // A focusable view in a row keeps the list from taking taps on the rest of the row.
        assertFalse(row.hasExplicitFocusable());

        toggle.setChecked(true);

        assertTrue(Extensions.isOn(context, Extensions.TRASH_BIN));
        assertTrue(marketplace.isShowing());
    }

    @Test
    public void withoutTheExtensionsPatchTheSwitchIsDisabledAndTheDialogSaysWhy() {
        putTwoThemes();
        putExtensions();
        View screen = showScreen().getWindow().getDecorView();
        ListView list = find(screen, ListView.class);
        tab(screen, "Extensions").performClick();
        assertFalse(InstalledPatches.extensions()); // unpatched, as in every test
        assertFalse(find(row(list, "Trash Bin"), Switch.class).isEnabled());

        tap(list, position(list, "Trash Bin"));

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("Trash Bin", Shadows.shadowOf(dialog).getTitle());
        View content = dialog.getWindow().getDecorView();
        List<String> texts = visibleTexts(content);
        assertTrue(texts.contains(Extensions.description(Extensions.TRASH_BIN)));
        assertTrue(texts.contains("Add the Spicetify extensions patch in Morphe Manager to use this."));
        assertFalse(find(content, Switch.class).isEnabled());
    }

    @Test
    public void anExtensionsDialogHasItsSwitchAndTheControlsItRegistered() {
        putTwoThemes();
        Extensions.Controls before = Extensions.controls(Extensions.RANDOM_SONG);
        Extensions.register(Extensions.RANDOM_SONG,
                owner -> SpicetifySettingsScreen.text(owner, "Random song controls", false));
        try {
            Dialog marketplace = showScreen();
            ListView list = find(marketplace.getWindow().getDecorView(), ListView.class);
            tab(marketplace.getWindow().getDecorView(), "Extensions").performClick();

            tap(list, position(list, "Play a random song"));

            View content = ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView();
            assertTrue(visibleTexts(content).contains("Random song controls"));
            find(content, Switch.class).setChecked(true);
            assertTrue(Extensions.isOn(context, Extensions.RANDOM_SONG));
            assertTrue(marketplace.isShowing());
        } finally {
            Extensions.register(Extensions.RANDOM_SONG, before); // the registry outlives this test
        }
    }

    @Test
    public void aDesktopOnlyExtensionOpensItsGitHubPage() {
        putTwoThemes();
        putExtensions();
        // Spotify's settings screen wraps its activity, which can start another app's activity.
        screenContext = new ContextThemeWrapper(Robolectric.buildActivity(Activity.class).setup().get(),
                android.R.style.Theme_Material);
        View screen = showScreen().getWindow().getDecorView();
        ListView list = find(screen, ListView.class);
        tab(screen, "Extensions").performClick();
        assertEquals(View.GONE, find(row(list, "Lyrics"), Switch.class).getVisibility());

        tap(list, position(list, "Lyrics"));

        Intent intent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals("https://github.com/a/dusk", intent.getDataString());
    }

    @Test
    public void aNoticeShowsOnBothTabsWhileTheThemesStayListed() {
        putTwoThemes(); // and no answer for the extensions search
        View screen = showScreen().getWindow().getDecorView();
        ListView list = find(screen, ListView.class);

        String notice = "Extensions couldn't load: " + Marketplace.searchUrl(Marketplace.EXTENSIONS_TOPIC) + "1";
        assertTrue(visibleTexts(screen).contains(notice));
        assertEquals(listed("Aurora", "Borealis"), titles(list.getAdapter()));
        // With no extensions to list, the notice says why, in place of "No extensions found".
        tab(screen, "Extensions").performClick();
        List<String> texts = visibleTexts(screen);
        assertTrue(texts.contains(notice));
        assertFalse(texts.contains("No extensions found"));
        assertTrue(texts.contains("Retry"));

        putExtensions();
        button(screen, "Refresh").performClick();
        idle();
        assertFalse(visibleTexts(screen).contains(notice));
        assertEquals(Arrays.asList("Play a random song", "Trash Bin", "Lyrics"), titles(list.getAdapter()));
        tab(screen, "Themes").performClick();
        assertFalse(visibleTexts(screen).contains(notice));
        assertEquals(listed("Aurora", "Dusk", "Borealis"), titles(list.getAdapter()));
    }

    private static String dataUri(byte[] png) {
        return "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP);
    }
}
