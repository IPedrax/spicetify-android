package app.spicetify.extension.spotify.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.database.DataSetObserver;
import android.os.Looper;
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

    private Marketplace.Fetcher fetcher() {
        return url -> {
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
        MarketplaceScreen.show(context, loader, previews, background, downloads, fetcher(), () -> {});
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
        assertEquals(2, adapter.getCount());
        assertEquals("Aurora", ((Marketplace.Theme) adapter.getItem(0)).title);
        assertEquals("Borealis", ((Marketplace.Theme) adapter.getItem(1)).title);
        assertTrue(visibleTexts(adapter.getView(0, null, list)).contains("ownerA, 100 stars"));
        assertTrue(visibleTexts(adapter.getView(1, null, list)).contains("ownerB, 1 star"));

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
        assertEquals(2, find(screen, ListView.class).getAdapter().getCount());
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
            assertEquals(Arrays.asList("Aurora", "Borealis"), titles);
        }
        assertEquals(Arrays.asList("Aurora", "Andromeda", "Borealis"), shown.get(shown.size() - 1));
    }

    @Test
    public void tappingAThemeDownloadsSchemesAndShowsTheChooser() {
        putTwoThemes();
        responses.put(Marketplace.resolve("color.ini", REPO_A, "main"),
                "[a]\nmain = 000000\n[b]\nmain = ffffff");
        Dialog dialog = showScreen();

        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        assertNotNull(list);
        tap(list, 0);
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

        tap(list, 0);
        tap(list, 1); // ignored: Aurora is still downloading
        assertEquals(1, themeDownloads.size());
        assertEquals(2, ShadowToast.shownToastCount());
        assertEquals("Loading Aurora", ShadowToast.getTextOfLatestToast());

        themeDownloads.remove(0).run();
        idle();
        assertEquals("Choose a color scheme", Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getTitle());
        assertEquals(1, loads.size()); // the chooser didn't wait for the refresh
        tap(list, 1);
        assertEquals(1, themeDownloads.size());
    }

    @Test
    public void aDownloadThatEndsAfterTheMarketplaceClosedShowsNothing() {
        putTwoThemes();
        responses.put(Marketplace.resolve("color.ini", REPO_A, "main"), "[a]\nmain = 000000\n[b]\nmain = ffffff");
        List<Runnable> themeDownloads = new ArrayList<>();
        downloads = themeDownloads::add;
        Dialog dialog = showScreen();
        tap(find(dialog.getWindow().getDecorView(), ListView.class), 0);

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

        tap(list, 0);
        idle();
        assertEquals("Aurora has no color schemes to use on Android.", latestAlertMessage());

        tap(list, 1);
        idle();
        assertEquals("Borealis: No color schemes found", latestAlertMessage());
    }
}
