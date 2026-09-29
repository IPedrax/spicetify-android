package app.spicetify.extension.spotify.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class MarketplaceLoaderTest {
    private static final Executor DIRECT = Runnable::run;
    private static final long ONE_HOUR_MILLIS = 60 * 60 * 1000L;
    /** The shape of Marketplace's resources/blacklist.json, comment strings included. */
    private static final String BLACKLIST =
            "{\"repos\":[\"// for old versions:\",\"// for new bl syntax:\",\"https://github.com/bad/*\"]}";

    private static final Marketplace.Repo ONE =
            new Marketplace.Repo("a", "one", "main", "https://github.com/a/one", 30);
    private static final Marketplace.Repo TWO =
            new Marketplace.Repo("bad", "two", "main", "https://github.com/bad/two", 20);
    private static final Marketplace.Repo THREE =
            new Marketplace.Repo("c", "three", "main", "https://github.com/c/three", 10);
    private static final Marketplace.Repo FOUR =
            new Marketplace.Repo("d", "four", "main", "https://github.com/d/four", 20);
    private static final Marketplace.Repo FIVE =
            new Marketplace.Repo("e", "five", "main", "https://github.com/e/five", 10);
    private static final String EXTENSIONS_SEARCH = Marketplace.searchUrl(Marketplace.EXTENSIONS_TOPIC);

    @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

    private final Map<String, String> responses = new HashMap<>();
    private final List<String> requested = Collections.synchronizedList(new ArrayList<>());
    private final long[] now = {10_000_000L};
    private File cacheFile;

    @Before
    public void setUp() {
        cacheFile = new File(tempFolder.getRoot(), "marketplace.json");
        // No extension repositories unless a test adds some, so a test about themes gets a complete list.
        responses.put(EXTENSIONS_SEARCH + "1", searchJson(0, Collections.emptyList()));
    }

    private Marketplace.Fetcher fetcher() {
        return url -> {
            requested.add(url);
            String value = responses.get(url);
            if (value == null) throw new FileNotFoundException(url);
            if ("RATE".equals(value)) throw new Marketplace.RateLimitException(url);
            if ("FAIL".equals(value)) throw new IOException("Connection reset");
            if ("HUGE".equals(value)) throw new Marketplace.TooLargeException(url);
            return value;
        };
    }

    private Marketplace.Cached cached() throws Exception {
        return Marketplace.fromJson(new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8));
    }

    private MarketplaceLoader loader() {
        return new MarketplaceLoader(fetcher(), DIRECT, cacheFile, () -> now[0]);
    }

    /** Uses the 5-argument constructor so a test can pick a short manifest-wait timeout. */
    private MarketplaceLoader loader(Executor manifests, long manifestsTimeoutMillis) {
        return new MarketplaceLoader(fetcher(), manifests, cacheFile, () -> now[0], manifestsTimeoutMillis);
    }

    private static String repoJson(Marketplace.Repo repo) {
        return "{\"full_name\":\"" + repo.owner + "/" + repo.name + "\",\"default_branch\":\"" + repo.branch + "\","
                + "\"html_url\":\"" + repo.url + "\",\"stargazers_count\":" + repo.stars + "}";
    }

    private static String searchJson(int total, List<Marketplace.Repo> repos) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < repos.size(); i++) {
            if (i > 0) items.append(',');
            items.append(repoJson(repos.get(i)));
        }
        return "{\"total_count\":" + total + ",\"items\":[" + items + "]}";
    }

    private static String themeJson(String name) {
        return "{\"name\":\"" + name + "\",\"description\":\"d\",\"usercss\":\"u.css\",\"schemes\":\"c.ini\"}";
    }

    private static String extensionJson(String name) {
        return "{\"name\":\"" + name + "\",\"description\":\"d\",\"main\":\"x.js\"}";
    }

    /** Blacklist, a 3-repo search page, and manifests for everything but the blacklisted repo. */
    private void putFreshData() {
        responses.put(Marketplace.BLACKLIST_URL, BLACKLIST);
        responses.put(Marketplace.SEARCH_URL + "1", searchJson(3, Arrays.asList(ONE, TWO, THREE)));
        responses.put(Marketplace.manifestUrl(ONE), themeJson("One"));
        responses.put(Marketplace.manifestUrl(THREE), "[" + themeJson("Three A") + "," + themeJson("Three B") + "]");
    }

    /**
     * Theme repositories ONE and THREE, and extension repositories FOUR and FIVE. THREE is tagged with
     * both topics, and the blacklisted TWO turns up among the extensions.
     */
    private void putBothTopics() {
        responses.put(Marketplace.BLACKLIST_URL, BLACKLIST);
        responses.put(Marketplace.SEARCH_URL + "1", searchJson(2, Arrays.asList(ONE, THREE)));
        responses.put(EXTENSIONS_SEARCH + "1", searchJson(4, Arrays.asList(FOUR, TWO, THREE, FIVE)));
        responses.put(Marketplace.manifestUrl(ONE), themeJson("One"));
        responses.put(Marketplace.manifestUrl(THREE), themeJson("Three"));
        responses.put(Marketplace.manifestUrl(FOUR), extensionJson("Four"));
        responses.put(Marketplace.manifestUrl(FIVE), extensionJson("Five"));
    }

    private static List<String> titles(List<Marketplace.Theme> themes) {
        List<String> titles = new ArrayList<>();
        for (Marketplace.Theme theme : themes) titles.add(theme.title);
        return titles;
    }

    private static List<Marketplace.Kind> kinds(List<Marketplace.Theme> themes) {
        List<Marketplace.Kind> kinds = new ArrayList<>();
        for (Marketplace.Theme theme : themes) kinds.add(theme.kind);
        return kinds;
    }

    private static final class Recorder implements MarketplaceLoader.Listener {
        List<Marketplace.Theme> lastThemes;
        boolean lastDone;
        int themeCalls;
        String error;
        String notice;
        /** Whether the notice came while the terminal call was still to come. */
        boolean noticeBeforeDone;
        /** Every list before the terminal one, in the order they came. */
        final List<List<Marketplace.Theme>> updates = new ArrayList<>();

        @Override
        public void onThemes(List<Marketplace.Theme> themes, boolean done) {
            lastThemes = themes;
            lastDone = done;
            themeCalls++;
            if (!done) updates.add(themes);
        }

        @Override
        public void onError(String message) {
            error = message;
        }

        @Override
        public void onNotice(String message) {
            notice = message;
            noticeBeforeDone = !lastDone;
        }
    }

    /** Collects submitted manifest tasks instead of running them, so a test can run them by hand. */
    private static final class StoringExecutor implements Executor {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }
    }

    @Test
    public void maxAgeIsSixHours() {
        assertEquals(6 * 60 * 60 * 1000L, MarketplaceLoader.MAX_AGE_MILLIS);
    }

    @Test
    public void freshLoad_skipsBlacklistedRepoAndCachesTheResult() throws Exception {
        putFreshData();

        Recorder listener = new Recorder();
        loader().load(false, listener);

        assertNull(listener.error);
        assertTrue(listener.lastDone);
        assertEquals(3, listener.themeCalls); // one onThemes(false) per accepted repo, then the final onThemes(true)
        List<Marketplace.Theme> themes = listener.lastThemes;
        assertEquals(3, themes.size());
        assertEquals("One", themes.get(0).title);
        assertEquals("Three A", themes.get(1).title);
        assertEquals("Three B", themes.get(2).title);
        assertFalse(requested.contains(Marketplace.manifestUrl(TWO)));

        String json = new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8);
        Marketplace.Cached cached = Marketplace.fromJson(json);
        assertEquals(3, cached.themes.size());
        assertEquals(now[0], cached.savedAt);
    }

    @Test
    public void freshCache_isReturnedWithoutAnyNetworkRequest() throws Exception {
        putFreshData();
        loader().load(false, new Recorder()); // populates the cache

        now[0] += ONE_HOUR_MILLIS;
        responses.clear();
        requested.clear();
        Recorder listener = new Recorder();
        loader().load(false, listener);

        assertNull(listener.error);
        assertTrue(listener.lastDone);
        assertEquals(3, listener.lastThemes.size());
        assertTrue(requested.isEmpty());
    }

    @Test
    public void refreshWithRateLimitedSearch_fallsBackToTheOldCache_withoutError() throws Exception {
        putFreshData();
        MarketplaceLoader loader = loader();
        loader.load(false, new Recorder()); // populates the cache

        now[0] += ONE_HOUR_MILLIS;
        responses.clear();
        responses.put(Marketplace.SEARCH_URL + "1", "RATE");
        Recorder listener = new Recorder();
        loader.load(true, listener);

        assertNull(listener.error);
        assertTrue(listener.lastDone);
        assertEquals(3, listener.lastThemes.size());
    }

    @Test
    public void noCacheAndRateLimitedSearch_reportsTheRateLimitMessage() {
        responses.put(Marketplace.BLACKLIST_URL, BLACKLIST);
        responses.put(Marketplace.SEARCH_URL + "1", "RATE");

        Recorder listener = new Recorder();
        loader().load(false, listener);

        assertEquals("GitHub's rate limit was reached. Try again in a few minutes.", listener.error);
        assertEquals(0, listener.themeCalls);
    }

    @Test
    public void missingBlacklist_stillLoadsThemes() throws Exception {
        // No BLACKLIST_URL response: the fetch throws, so nothing is blacklisted this time.
        responses.put(Marketplace.SEARCH_URL + "1", searchJson(3, Arrays.asList(ONE, TWO, THREE)));
        responses.put(Marketplace.manifestUrl(ONE), themeJson("One"));
        responses.put(Marketplace.manifestUrl(THREE), "[" + themeJson("Three A") + "," + themeJson("Three B") + "]");

        Recorder listener = new Recorder();
        loader().load(false, listener);

        assertNull(listener.error);
        assertTrue(listener.lastDone);
        assertEquals(3, listener.lastThemes.size());
        assertTrue(requested.contains(Marketplace.manifestUrl(TWO))); // not filtered without a blacklist
        assertEquals(3, cached().themes.size()); // a 404 is an answer, so the list is cached
    }

    @Test
    public void aBlacklistThatFailedToDownload_leavesTheListUncached() {
        putFreshData();
        responses.put(Marketplace.BLACKLIST_URL, "FAIL");

        Recorder listener = new Recorder();
        loader().load(false, listener);

        assertNull(listener.error);
        assertEquals(3, listener.lastThemes.size());
        assertTrue(requested.contains(Marketplace.manifestUrl(TWO))); // nothing was filtered
        assertFalse(cacheFile.exists());
    }

    @Test
    public void searchPaging_stopsOncePagesReachTheTotalCount() {
        List<Marketplace.Repo> repos = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            repos.add(new Marketplace.Repo("owner" + i, "repo" + i, "main",
                    "https://github.com/owner" + i + "/repo" + i, 150 - i));
        }
        responses.put(Marketplace.SEARCH_URL + "1", searchJson(150, repos.subList(0, 100)));
        responses.put(Marketplace.SEARCH_URL + "2", searchJson(150, repos.subList(100, 150)));

        Recorder listener = new Recorder();
        loader().load(false, listener);

        assertNull(listener.error);
        assertTrue(requested.contains(Marketplace.SEARCH_URL + "1"));
        assertTrue(requested.contains(Marketplace.SEARCH_URL + "2"));
        assertFalse(requested.contains(Marketplace.SEARCH_URL + "3"));
    }

    @Test
    public void searchPaging_countsArchivedRepositoriesTowardTheTotal() {
        String archived = "{\"full_name\":\"old/theme\",\"default_branch\":\"main\","
                + "\"html_url\":\"https://github.com/old/theme\",\"stargazers_count\":5,\"archived\":true}";
        responses.put(Marketplace.SEARCH_URL + "1", "{\"total_count\":2,\"items\":[" + repoJson(ONE) + "," + archived + "]}");
        responses.put(Marketplace.manifestUrl(ONE), themeJson("One"));

        Recorder listener = new Recorder();
        loader().load(false, listener);

        assertNull(listener.error);
        assertEquals(1, listener.lastThemes.size());
        assertFalse(requested.contains(Marketplace.SEARCH_URL + "2"));
        assertFalse(requested.contains("https://raw.githubusercontent.com/old/theme/main/manifest.json"));
    }

    @Test
    public void aRepositoryOnTwoSearchPages_isListedOnce() {
        // Stars changed between the two requests, so THREE moved from page 1 to page 2.
        responses.put(Marketplace.SEARCH_URL + "1", searchJson(3, Arrays.asList(ONE, THREE)));
        responses.put(Marketplace.SEARCH_URL + "2", searchJson(3, Arrays.asList(THREE)));
        responses.put(Marketplace.manifestUrl(ONE), themeJson("One"));
        responses.put(Marketplace.manifestUrl(THREE), themeJson("Three"));

        Recorder listener = new Recorder();
        loader().load(false, listener);

        assertNull(listener.error);
        assertEquals(2, listener.lastThemes.size());
        assertEquals(1, Collections.frequency(requested, Marketplace.manifestUrl(THREE)));
    }

    @Test
    public void aDeeplyNestedManifest_contributesNothing_andTheOtherRepositoryStillLoads() {
        char[] nested = new char[1_000_000];
        Arrays.fill(nested, '[');
        responses.put(Marketplace.SEARCH_URL + "1", searchJson(2, Arrays.asList(ONE, THREE)));
        responses.put(Marketplace.manifestUrl(ONE), new String(nested));
        responses.put(Marketplace.manifestUrl(THREE), themeJson("Three"));

        Recorder listener = new Recorder();
        loader().load(false, listener);

        assertNull(listener.error);
        assertTrue(listener.lastDone);
        assertEquals(1, listener.lastThemes.size());
        assertEquals("Three", listener.lastThemes.get(0).title);
    }

    @Test
    public void aMissingManifest_isStillCached_andSendsNoUpdate() throws Exception {
        responses.put(Marketplace.SEARCH_URL + "1", searchJson(2, Arrays.asList(ONE, THREE)));
        responses.put(Marketplace.manifestUrl(ONE), themeJson("One")); // THREE has no manifest: 404

        Recorder listener = new Recorder();
        loader().load(false, listener);

        assertEquals(2, listener.themeCalls); // One's update, then the final call; nothing for THREE
        assertEquals(1, cached().themes.size());
    }

    @Test
    public void aManifestThatFailed_leavesTheOlderCacheInPlace_andDeliversIt() throws Exception {
        putFreshData();
        loader().load(false, new Recorder()); // populates the cache
        long savedAt = now[0];

        now[0] += ONE_HOUR_MILLIS;
        responses.put(Marketplace.manifestUrl(THREE), "FAIL");
        Recorder listener = new Recorder();
        loader().load(true, listener);

        assertTrue(listener.lastDone);
        assertEquals(3, listener.lastThemes.size()); // the cached list, not the one theme that arrived
        assertEquals(savedAt, cached().savedAt);
        assertEquals(3, cached().themes.size());
    }

    @Test
    public void manifestsTimeout_withACache_deliversTheCachedList() throws Exception {
        putFreshData();
        loader().load(false, new Recorder()); // populates the cache

        now[0] += ONE_HOUR_MILLIS;
        Recorder listener = new Recorder();
        loader(new StoringExecutor(), 50).load(true, listener);

        assertNull(listener.error);
        assertTrue(listener.lastDone);
        assertEquals(3, listener.lastThemes.size());
    }

    @Test
    public void aManifestOverTheSizeCap_isLeftOut_andTheListIsStillCached() throws Exception {
        responses.put(Marketplace.SEARCH_URL + "1", searchJson(2, Arrays.asList(ONE, THREE)));
        responses.put(Marketplace.manifestUrl(ONE), "HUGE");
        responses.put(Marketplace.manifestUrl(THREE), themeJson("Three"));

        Recorder listener = new Recorder();
        loader().load(false, listener);

        assertNull(listener.error);
        assertEquals(1, listener.lastThemes.size());
        assertEquals(1, cached().themes.size());
        assertEquals("Three", cached().themes.get(0).title);
    }

    @Test
    public void anEmptyList_isNotCached() {
        responses.put(Marketplace.SEARCH_URL + "1", searchJson(0, Collections.emptyList()));

        Recorder listener = new Recorder();
        loader().load(false, listener);

        assertNull(listener.error);
        assertTrue(listener.lastDone);
        assertTrue(listener.lastThemes.isEmpty());
        assertFalse(cacheFile.exists());
    }

    @Test
    public void manifestsTimeout_reportsWhatArrivedAndDoesNotCacheIt() throws Exception {
        responses.put(Marketplace.SEARCH_URL + "1", searchJson(2, Arrays.asList(ONE, THREE)));
        responses.put(Marketplace.manifestUrl(ONE), themeJson("One"));
        responses.put(Marketplace.manifestUrl(THREE), themeJson("Three"));

        StoringExecutor executor = new StoringExecutor();
        Recorder listener = new Recorder();
        loader(executor, 50).load(false, listener);

        assertNull(listener.error);
        assertEquals(1, listener.themeCalls);
        assertTrue(listener.lastDone);
        assertTrue(listener.lastThemes.isEmpty());
        assertFalse("the timed-out run must not cache a partial list", cacheFile.exists());

        for (Runnable task : executor.tasks) {
            task.run();
        }

        assertEquals("no callback should follow the terminal one", 1, listener.themeCalls);
        assertFalse("a task that starts after the load ended downloads nothing", requested.contains(Marketplace.manifestUrl(ONE)));
    }

    @Test
    public void bothTopics_readEachManifestOnce_andListBothKindsByStars() throws Exception {
        putBothTopics();

        Recorder listener = new Recorder();
        loader().load(false, listener);

        assertNull(listener.error);
        assertNull(listener.notice);
        assertTrue(listener.lastDone);
        int manifests = 0;
        for (String url : requested) {
            if (url.endsWith("/manifest.json")) manifests++;
        }
        assertEquals("THREE's manifest is read once, and blacklisted TWO's never", 4, manifests);
        // Most stars first. FIVE ties with THREE and follows it: extension orders come after theme orders.
        assertEquals(Arrays.asList("One", "Four", "Three", "Five"), titles(listener.lastThemes));
        assertEquals(Arrays.asList(Marketplace.Kind.THEME, Marketplace.Kind.EXTENSION, Marketplace.Kind.THEME,
                Marketplace.Kind.EXTENSION), kinds(listener.lastThemes));
        assertEquals(4, cached().themes.size());
    }

    @Test
    public void themes_arriveBeforeAnyExtension() {
        putBothTopics();

        Recorder listener = new Recorder();
        loader().load(false, listener);

        // The extension search waits until every theme manifest is in.
        assertEquals(Arrays.asList(Marketplace.BLACKLIST_URL, Marketplace.SEARCH_URL + "1",
                Marketplace.manifestUrl(ONE), Marketplace.manifestUrl(THREE), EXTENSIONS_SEARCH + "1",
                Marketplace.manifestUrl(FOUR), Marketplace.manifestUrl(FIVE)), requested);
        List<List<String>> updates = new ArrayList<>();
        for (List<Marketplace.Theme> update : listener.updates) updates.add(titles(update));
        assertEquals(Arrays.asList(Arrays.asList("One"), Arrays.asList("One", "Three"),
                Arrays.asList("One", "Four", "Three"), Arrays.asList("One", "Four", "Three", "Five")), updates);
    }

    @Test
    public void aRateLimitedExtensionSearch_reportsTheThemesWithANotice_andDoesNotCache() {
        putBothTopics();
        responses.put(EXTENSIONS_SEARCH + "1", "RATE"); // what GitHub's HTTP 403 becomes

        Recorder listener = new Recorder();
        loader().load(false, listener);

        assertNull(listener.error);
        assertTrue(listener.lastDone);
        assertEquals(Arrays.asList("One", "Three"), titles(listener.lastThemes));
        assertEquals("Extensions couldn't load: GitHub's rate limit was reached. Try again in a few minutes.",
                listener.notice);
        assertTrue("the notice comes before the terminal call", listener.noticeBeforeDone);
        assertFalse(cacheFile.exists());
    }

    @Test
    public void aFailedExtensionSearch_withACache_deliversTheCachedList_andTheNotice() throws Exception {
        putBothTopics();
        loader().load(false, new Recorder()); // populates the cache
        long savedAt = now[0];

        now[0] += ONE_HOUR_MILLIS;
        responses.put(EXTENSIONS_SEARCH + "1", "FAIL");
        Recorder listener = new Recorder();
        loader().load(true, listener);

        assertNull(listener.error);
        assertTrue(listener.lastDone);
        assertEquals(4, listener.lastThemes.size()); // the cached list, extensions included
        assertEquals("Extensions couldn't load: Connection reset", listener.notice);
        assertEquals(savedAt, cached().savedAt);
    }

    @Test
    public void anExtensionPhaseTimeout_keepsTheThemes_andDoesNotCacheThem() {
        putBothTopics();
        List<Runnable> extensionTasks = new ArrayList<>();
        // Theme manifests run at once; extension manifests wait for the test, so only the second phase times out.
        Executor themesNowExtensionsLater = task -> {
            if (requested.contains(EXTENSIONS_SEARCH + "1")) {
                extensionTasks.add(task);
            } else {
                task.run();
            }
        };

        Recorder listener = new Recorder();
        loader(themesNowExtensionsLater, 50).load(false, listener);

        assertNull(listener.error);
        assertTrue(listener.lastDone);
        assertEquals(Arrays.asList("One", "Three"), titles(listener.lastThemes));
        assertFalse("the timed-out run must not cache a partial list", cacheFile.exists());
        assertEquals("FOUR and FIVE waited", 2, extensionTasks.size());

        int calls = listener.themeCalls;
        for (Runnable task : extensionTasks) {
            task.run();
        }

        assertEquals("no callback should follow the terminal one", calls, listener.themeCalls);
        assertFalse("a task that starts after the load ended downloads nothing",
                requested.contains(Marketplace.manifestUrl(FOUR)));
    }

    @Test
    public void aCacheRoundTrip_keepsBothKinds() {
        putBothTopics();
        loader().load(false, new Recorder()); // populates the cache

        now[0] += ONE_HOUR_MILLIS;
        responses.clear();
        requested.clear();
        Recorder listener = new Recorder();
        loader().load(false, listener);

        assertTrue(requested.isEmpty());
        assertEquals(Arrays.asList("One", "Four", "Three", "Five"), titles(listener.lastThemes));
        assertEquals(Arrays.asList(Marketplace.Kind.THEME, Marketplace.Kind.EXTENSION, Marketplace.Kind.THEME,
                Marketplace.Kind.EXTENSION), kinds(listener.lastThemes));
        assertEquals(Marketplace.resolve("x.js", FOUR, "main"), listener.lastThemes.get(1).mainUrl);
    }
}
