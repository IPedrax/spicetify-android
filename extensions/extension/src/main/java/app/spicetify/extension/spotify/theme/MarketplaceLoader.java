package app.spicetify.extension.spotify.theme;

import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.json.JSONException;

/**
 * Loads the Spicetify Marketplace's list of themes and extensions: from the cache file when it is
 * fresh enough, otherwise from GitHub (the blacklist, then the search pages and each repository's
 * manifest, for themes and then for extensions), delivering items to a {@link Listener} as they
 * arrive.
 */
final class MarketplaceLoader {
    private static final String TAG = "Spicetify";
    /** GitHub's search API returns at most 1,000 results, 100 per page. */
    private static final int MAX_PAGES = 10;
    private static final long DEFAULT_MANIFESTS_TIMEOUT_MILLIS = 60_000L;
    static final long MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L;
    private static final String RATE_LIMITED = "GitHub's rate limit was reached. Try again in a few minutes.";

    /** Receives themes and extensions as they arrive, and any error that stopped loading. */
    interface Listener {
        /** {@code themes} is the full list gathered so far, sorted; {@code done} marks the last call. */
        void onThemes(List<Marketplace.Theme> themes, boolean done);

        void onError(String message);

        /** Status text about part of the list that couldn't load; it comes before the last {@link #onThemes}. */
        default void onNotice(String message) {}
    }

    private final Marketplace.Fetcher fetcher;
    private final Executor manifests;
    private final File cache;
    private final LongSupplier clock;
    private final long manifestsTimeoutMillis;

    MarketplaceLoader(Marketplace.Fetcher fetcher, Executor manifests, File cache, LongSupplier clock) {
        this(fetcher, manifests, cache, clock, DEFAULT_MANIFESTS_TIMEOUT_MILLIS);
    }

    /** Lets a caller choose how long to wait for manifests; the 4-argument constructor waits 60 seconds. */
    MarketplaceLoader(Marketplace.Fetcher fetcher, Executor manifests, File cache, LongSupplier clock,
            long manifestsTimeoutMillis) {
        this.fetcher = fetcher;
        this.manifests = manifests;
        this.cache = cache;
        this.clock = clock;
        this.manifestsTimeoutMillis = manifestsTimeoutMillis;
    }

    /**
     * Loads the list and reports it to {@code listener}, blocking until it is done; call this from a
     * background thread. Themes load first, so they show while the extensions load; each of the two
     * phases waits up to the manifest timeout. Never throws: any failure is reported through
     * {@link Listener#onError} instead.
     */
    void load(boolean refresh, Listener listener) {
        try {
            Marketplace.Cached cached = readCache();
            if (!refresh && cached != null && clock.getAsLong() - cached.savedAt < MAX_AGE_MILLIS) {
                listener.onThemes(cached.themes, true);
                return;
            }
            // Set by a failure that may have left this load's list short, so it isn't cached.
            AtomicBoolean failed = new AtomicBoolean(false);
            List<String> blacklist = readBlacklist(failed);
            Map<String, Marketplace.Repo> themeRepos;
            try {
                themeRepos = search(Marketplace.THEMES_TOPIC);
            } catch (Exception e) {
                reportSearchFailure(e, cached, listener);
                return;
            }
            List<Marketplace.Theme> themes = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean finished = new AtomicBoolean(false);
            boolean complete = fetchManifests(themeRepos.values(), 0, blacklist, listener, themes, finished, failed);
            // Then the extensions. When their search fails, the themes are still listed, with a notice, but not cached.
            String notice = null;
            Map<String, Marketplace.Repo> extensionRepos;
            try {
                extensionRepos = search(Marketplace.EXTENSIONS_TOPIC);
            } catch (Exception e) {
                Log.w(TAG, "Marketplace extension search failed", e);
                notice = "Extensions couldn't load: "
                        + (e instanceof Marketplace.RateLimitException ? RATE_LIMITED : describe(e));
                extensionRepos = new LinkedHashMap<>();
            }
            extensionRepos.keySet().removeAll(themeRepos.keySet()); // their manifests were read with the themes
            complete &= fetchManifests(extensionRepos.values(), themeRepos.size(), blacklist, listener, themes,
                    finished, failed) && notice == null;
            if (complete && !themes.isEmpty()) {
                writeCache(Marketplace.sorted(themes));
            } else {
                Log.w(TAG, "Marketplace list is incomplete or empty; not caching it");
            }
            synchronized (finished) {
                finished.set(true);
                if (notice != null) listener.onNotice(notice);
                // An incomplete list loses to the cache, even an old one, as a failed search does. The
                // fresh list is sorted under the lock, so no late manifest's update can list more themes.
                listener.onThemes(!complete && cached != null ? cached.themes : Marketplace.sorted(themes), true);
            }
        } catch (Throwable e) {
            Log.w(TAG, "Marketplace load failed", e);
            listener.onError("Couldn't load the Marketplace: " + describe(e));
        }
    }

    /** Null when there is no cache file yet, or it can't be read as a cache. */
    private Marketplace.Cached readCache() {
        try {
            return Marketplace.fromJson(readFile(cache));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Marketplace treats a blacklist it can't read as an empty one. A failure other than a 404 sets
     * {@code failed}, so a list that may show blacklisted repositories isn't cached.
     */
    private List<String> readBlacklist(AtomicBoolean failed) {
        try {
            return Marketplace.parseBlacklist(fetcher.get(Marketplace.BLACKLIST_URL));
        } catch (Exception e) {
            if (!(e instanceof FileNotFoundException)) failed.set(true);
            Log.w(TAG, "Marketplace blacklist could not be read; nothing is filtered", e);
            return Collections.emptyList();
        }
    }

    /**
     * The repositories with {@code topic}, keyed by owner/name in GitHub's order. Pages 1, 2, ...
     * until the items seen reach the total, a page is empty, or the page cap. A repository that moved
     * to the next page between two requests is kept once.
     */
    private Map<String, Marketplace.Repo> search(String topic) throws IOException, JSONException {
        Map<String, Marketplace.Repo> repos = new LinkedHashMap<>();
        int seen = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            Marketplace.Page result = Marketplace.parseSearch(fetcher.get(Marketplace.searchUrl(topic) + page));
            if (result.count == 0) break;
            for (Marketplace.Repo repo : result.repos) repos.putIfAbsent(repo.owner + "/" + repo.name, repo);
            seen += result.count;
            if (seen >= result.total) break;
        }
        return repos;
    }

    /** A cache, even an old one, beats an error; otherwise report why the search failed. */
    private void reportSearchFailure(Exception e, Marketplace.Cached cached, Listener listener) {
        if (cached != null) {
            listener.onThemes(cached.themes, true);
        } else if (e instanceof Marketplace.RateLimitException) {
            listener.onError(RATE_LIMITED);
        } else {
            listener.onError("Couldn't load the Marketplace: " + describe(e));
        }
    }

    /**
     * Fetches every non-blacklisted repository's manifest on {@link #manifests}, in parallel,
     * adding results to {@code themes} and reporting each arrival to {@code listener} until
     * {@code finished} is set. Repositories are numbered in order from {@code firstIndex}. A manifest
     * that fails for a reason other than being missing or too large sets {@code failed}. Returns
     * whether the list is complete: every task finished before the timeout, and nothing set
     * {@code failed}.
     */
    private boolean fetchManifests(Collection<Marketplace.Repo> repos, int firstIndex, List<String> blacklist,
            Listener listener, List<Marketplace.Theme> themes, AtomicBoolean finished, AtomicBoolean failed)
            throws InterruptedException {
        List<Runnable> tasks = new ArrayList<>();
        int next = firstIndex;
        for (Marketplace.Repo repo : repos) {
            int repoIndex = next++;
            if (!Marketplace.isBlacklisted(repo.url, blacklist)) {
                tasks.add(() -> fetchManifest(repo, repoIndex, themes, listener, finished, failed));
            }
        }
        CountDownLatch latch = new CountDownLatch(tasks.size());
        for (Runnable task : tasks) {
            manifests.execute(() -> {
                try {
                    task.run();
                } catch (Throwable e) {
                    // Anything that escapes a pool thread ends Spotify's process.
                    Log.w(TAG, "Marketplace manifest task failed", e);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            return latch.await(manifestsTimeoutMillis, TimeUnit.MILLISECONDS) && !failed.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * A missing manifest, one over the size cap, or one Marketplace can't use contributes nothing;
     * other failures are logged, and other I/O failures set {@code failed}, because they say
     * nothing about the repository.
     */
    private void fetchManifest(Marketplace.Repo repo, int repoIndex, List<Marketplace.Theme> themes, Listener listener,
            AtomicBoolean finished, AtomicBoolean failed) {
        if (finished.get()) return; // The load already ended; don't download for nothing.
        boolean added = false;
        try {
            String json = fetcher.get(Marketplace.manifestUrl(repo));
            added = themes.addAll(Marketplace.parseManifest(json, repo, repoIndex));
        } catch (FileNotFoundException | Marketplace.TooLargeException | JSONException ignored) {
            // No manifest at that URL, one over the size cap, or not one Marketplace can use.
        } catch (IOException e) {
            failed.set(true);
            Log.w(TAG, "Marketplace manifest failed: " + repo.url, e);
        } catch (Exception | StackOverflowError e) {
            // StackOverflowError: JSON nested deeper than the parser's stack.
            Log.w(TAG, "Marketplace manifest failed: " + repo.url, e);
        }
        if (!added) return; // Nothing new to report.
        synchronized (finished) {
            if (finished.get()) return; // The terminal onThemes(..., true) already went out; don't follow it.
            try {
                listener.onThemes(Marketplace.sorted(themes), false);
            } catch (RuntimeException e) {
                Log.w(TAG, "Marketplace listener failed", e);
            }
        }
    }

    /** Writes to a temporary file and renames it, so a crash never leaves half a cache. */
    private void writeCache(List<Marketplace.Theme> themes) {
        File tmp = new File(cache.getPath() + ".tmp");
        try {
            writeFile(tmp, Marketplace.toJson(themes, clock.getAsLong()));
            if (!tmp.renameTo(cache)) throw new IOException("Could not replace " + cache);
        } catch (Exception e) {
            Log.w(TAG, "Marketplace cache could not be written", e);
        }
    }

    private static String readFile(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            for (int read; offset < bytes.length && (read = input.read(bytes, offset, bytes.length - offset)) > 0; ) {
                offset += read;
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeFile(File file, String content) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** {@code e.getMessage()}, or the exception's class name when there's no message to show. */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message != null ? message : e.getClass().getSimpleName();
    }
}
