package app.spicetify.extension.spotify.theme;

import app.spicetify.extension.spotify.extensions.Extensions;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * The Spicetify Marketplace's theme and extension listing, read from GitHub by the rules
 * Marketplace uses (spicetify/marketplace, FetchRemotes.ts and Utils.ts at ec6f772).
 */
final class Marketplace {
    static final String THEMES_TOPIC = "spicetify-themes";
    static final String EXTENSIONS_TOPIC = "spicetify-extensions";
    static final String SEARCH_URL = searchUrl(THEMES_TOPIC);
    static final String BLACKLIST_URL =
            "https://raw.githubusercontent.com/spicetify/marketplace/main/resources/blacklist.json";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final long DOWNLOAD_DEADLINE_MILLIS = 60_000L;
    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();
    /** Caps on untrusted manifest data; 50 items keep {@code repoIndex * 1000 + i} unique. */
    private static final int MAX_ITEMS = 50;
    private static final int MAX_NAME_CHARS = 100;
    private static final int MAX_DESCRIPTION_CHARS = 300;

    /** GitHub's search-by-topic URL for one page of results; append the page number. */
    static String searchUrl(String topic) {
        return "https://api.github.com/search/repositories?q=topic%3A" + topic + "&sort=stars&order=desc&per_page=100&page=";
    }

    interface Fetcher {
        String get(String url) throws IOException;
    }

    /** GitHub refused a request because of its rate limit. */
    static final class RateLimitException extends IOException {
        RateLimitException(String url) {
            super("GitHub's rate limit was reached for " + url);
        }
    }

    /** An answer over the size cap: a permanent answer, like a 404, that trying again won't change. */
    static final class TooLargeException extends IOException {
        TooLargeException(String url) {
            super("Too large: " + url);
        }
    }

    /** A download still running at its deadline. */
    private static final class TooSlowException extends IOException {
        TooSlowException(String url, IOException cause) {
            super("Too slow: " + url, cause);
        }
    }

    static final Fetcher HTTP = url -> new String(download(url, MAX_BYTES), StandardCharsets.UTF_8);

    /** Reads a URL, refusing answers larger than {@code maxBytes} or still arriving after a minute. */
    static byte[] download(String url, int maxBytes) throws IOException {
        return download(url, maxBytes, DOWNLOAD_DEADLINE_MILLIS);
    }

    /**
     * Reads a URL, refusing answers larger than {@code maxBytes} or still arriving after
     * {@code deadlineMillis}. The timeouts restart with every byte, so a watchdog disconnects the
     * call at the deadline, which also stops a host that trickles the headers.
     */
    static byte[] download(String url, int maxBytes, long deadlineMillis) throws IOException {
        long started = System.nanoTime();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "spicetify-android-patches");
        AtomicBoolean cutOff = new AtomicBoolean();
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            cutOff.set(true); // first, so the download thread sees it when the disconnect makes it fail
            connection.disconnect();
        }, deadlineMillis, TimeUnit.MILLISECONDS);
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) throw new FileNotFoundException(url);
            if (status == 403 || status == 429) throw new RateLimitException(url);
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status + " for " + url);
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) > 0; ) {
                    output.write(buffer, 0, read);
                    if (output.size() > maxBytes) throw new TooLargeException(url);
                    if (System.nanoTime() - started > deadlineMillis * 1_000_000) throw new TooSlowException(url, null);
                }
                return output.toByteArray();
            }
        } catch (IOException e) {
            // Once the watchdog cut the call off, that's why it failed, whatever the exception says.
            if (cutOff.get() && !(e instanceof TooSlowException)) throw new TooSlowException(url, e);
            throw e;
        } finally {
            watchdog.cancel(false);
            connection.disconnect();
        }
    }

    /** One daemon thread for every download's deadline; it ends after a minute idle. */
    private static ScheduledThreadPoolExecutor watchdog() {
        ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "Spicetify download watchdog");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.setKeepAliveTime(1, TimeUnit.MINUTES); // before allowCoreThreadTimeOut, which needs a keep-alive
        watchdog.allowCoreThreadTimeOut(true);
        watchdog.setRemoveOnCancelPolicy(true); // a finished download leaves nothing queued
        return watchdog;
    }

    static final class Repo {
        final String owner;
        final String name;
        final String branch;
        final String url;
        final int stars;

        Repo(String owner, String name, String branch, String url, int stars) {
            this.owner = owner;
            this.name = name;
            this.branch = branch;
            this.url = url;
            this.stars = stars;
        }
    }

    /** Which of the two Marketplace listings an item belongs to. */
    enum Kind { THEME, EXTENSION }

    static final class Theme {
        final String title;
        final String description;
        final String author;
        /** Null when the manifest names no preview. */
        final String previewUrl;
        /** Null for extensions. */
        final String schemesUrl;
        final String repoUrl;
        /** Negative when unknown. */
        final int stars;
        /** Position in GitHub's star order, then in the manifest. */
        final int order;
        /**
         * An image to draw behind Spotify with the theme; null for every GitHub result, whose own
         * image, if it has one, is found in its include JS or user.css.
         */
        final String backgroundUrl;
        /** Null when the theme came from a cache written before it was kept, or for extensions. */
        final String usercssUrl;
        /** The JavaScript files the theme includes, in manifest order; empty for extensions. */
        final List<String> includeUrls;
        final Kind kind;
        /** Resolved link to the extension's script; null for themes. */
        final String mainUrl;
        /** This extension's Android id, when a port exists; null for themes and unported extensions. */
        final String androidId;

        Theme(String title, String description, String author, String previewUrl, String schemesUrl,
                String repoUrl, int stars, int order, String backgroundUrl, String usercssUrl, List<String> includeUrls,
                Kind kind, String mainUrl, String androidId) {
            this.title = title;
            this.description = description;
            this.author = author;
            this.previewUrl = previewUrl;
            this.schemesUrl = schemesUrl;
            this.repoUrl = repoUrl;
            this.stars = stars;
            this.order = order;
            this.backgroundUrl = backgroundUrl;
            this.usercssUrl = usercssUrl;
            this.includeUrls = includeUrls;
            this.kind = kind;
            this.mainUrl = mainUrl;
            this.androidId = androidId;
        }
    }

    /** Pinned above the GitHub results: Galaxy as the desktop theme shows it, over its background image. */
    static final Theme GALAXY_V2 = new Theme("Galaxy V2",
            "Galaxy's colors over its fullscreen background image, the way the desktop theme shows it.", "harbassan",
            "https://raw.githubusercontent.com/harbassan/spicetify-galaxy/main/preview_playlist.png",
            "https://raw.githubusercontent.com/harbassan/spicetify-galaxy/main/color.ini",
            "https://github.com/harbassan/spicetify-galaxy", -1, -1,
            "https://raw.githubusercontent.com/harbassan/spicetify-galaxy/main/assets/default_bg.jpg",
            null, Collections.emptyList(), Kind.THEME, null, null);

    static final class Page {
        final List<Repo> repos;
        /** Items on the page, archived ones included, so paging still reaches {@link #total}. */
        final int count;
        final int total;

        Page(List<Repo> repos, int count, int total) {
            this.repos = repos;
            this.count = count;
            this.total = total;
        }
    }

    static final class Cached {
        final List<Theme> themes;
        final long savedAt;

        Cached(List<Theme> themes, long savedAt) {
            this.themes = themes;
            this.savedAt = savedAt;
        }
    }

    private Marketplace() {}

    /** A search page, leaving out archived repositories as Marketplace does by default. */
    static Page parseSearch(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray items = root.getJSONArray("items");
        List<Repo> repos = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String[] fullName = item.getString("full_name").split("/", 2);
            if (fullName.length != 2 || item.optBoolean("archived")) continue;
            repos.add(new Repo(fullName[0], fullName[1], item.getString("default_branch"),
                    item.getString("html_url"), item.optInt("stargazers_count")));
        }
        return new Page(repos, items.length(), root.getInt("total_count"));
    }

    /**
     * Marketplace's blacklist.json, {@code {"repos": [URL patterns]}}; a missing "repos" is an empty
     * list. Entries that aren't strings are ignored, and strings that aren't URLs, such as the
     * file's comments, never match.
     */
    static List<String> parseBlacklist(String json) throws JSONException {
        JSONArray array = new JSONObject(json).optJSONArray("repos");
        List<String> patterns = new ArrayList<>();
        if (array == null) return patterns;
        for (int i = 0; i < array.length(); i++) {
            Object entry = array.opt(i);
            if (entry instanceof String) patterns.add((String) entry);
        }
        return patterns;
    }

    /** Marketplace's matchesBlacklistPattern: case-insensitive; "*" matches one path segment. */
    static boolean isBlacklisted(String url, List<String> patterns) {
        String target = url.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            String lower = pattern.toLowerCase(Locale.ROOT);
            if (lower.indexOf('*') < 0) {
                if (target.equals(lower)) return true;
                continue;
            }
            String[] parts = lower.split("\\*", -1);
            StringBuilder regex = new StringBuilder(Pattern.quote(parts[0]));
            for (int i = 1; i < parts.length; i++) regex.append("[^/]+").append(Pattern.quote(parts[i]));
            if (target.matches(regex.toString())) return true;
        }
        return false;
    }

    static String manifestUrl(Repo repo) {
        return resolve("manifest.json", repo, repo.branch);
    }

    /** Absolute URLs stay as they are; paths resolve against the repository's raw files. */
    static String resolve(String path, Repo repo, String branch) {
        if (path.startsWith("http")) return path;
        return "https://raw.githubusercontent.com/" + repo.owner + "/" + repo.name + "/" + branch + "/" + path;
    }

    /**
     * Theme and extension items of a manifest (one object or an array). A theme needs name,
     * description and usercss, as Marketplace requires, plus schemes, without which there is
     * nothing to use on Android; an extension needs name, description and main. An item that
     * qualifies as both becomes two entries, the theme first. Only the first 50 items count, and
     * long names and descriptions are cut short.
     */
    static List<Theme> parseManifest(String json, Repo repo, int repoIndex) throws JSONException {
        String text = json.startsWith("\ufeff") ? json.substring(1) : json;
        Object root = new JSONTokener(text.trim()).nextValue();
        JSONArray items;
        if (root instanceof JSONArray) {
            items = (JSONArray) root;
        } else if (root instanceof JSONObject) {
            items = new JSONArray().put(root);
        } else {
            return Collections.emptyList();
        }
        List<Theme> themes = new ArrayList<>();
        for (int i = 0; i < items.length() && i < MAX_ITEMS; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String title = string(item, "name");
            String description = string(item, "description");
            if (title.isEmpty() || description.isEmpty()) continue;
            String cappedTitle = cap(title, MAX_NAME_CHARS);
            String cappedDescription = cap(description, MAX_DESCRIPTION_CHARS);
            String branch = string(item, "branch");
            if (branch.isEmpty()) branch = repo.branch;
            String authorName = cap(author(item, repo), MAX_NAME_CHARS);
            String preview = string(item, "preview");
            String previewUrl = preview.isEmpty() ? null : resolve(preview, repo, branch);
            int order = repoIndex * 1000 + i;

            String usercss = string(item, "usercss");
            String schemes = string(item, "schemes");
            if (!usercss.isEmpty() && !schemes.isEmpty()) {
                List<String> includes = new ArrayList<>();
                for (String include : strings(item.opt("include"))) includes.add(resolve(include, repo, branch));
                themes.add(new Theme(cappedTitle, cappedDescription, authorName, previewUrl,
                        resolve(schemes, repo, branch), repo.url, repo.stars, order, null,
                        resolve(usercss, repo, branch), includes, Kind.THEME, null, null));
            }
            String main = string(item, "main");
            if (!main.isEmpty()) {
                themes.add(new Theme(cappedTitle, cappedDescription, authorName, previewUrl, null,
                        repo.url, repo.stars, order, null, null, Collections.emptyList(),
                        Kind.EXTENSION, resolve(main, repo, branch), Extensions.portFor(repo.owner, repo.name, main)));
            }
        }
        return themes;
    }

    /** org.json's optString turns JSON null into "null"; only real strings count here. */
    private static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? ((String) value).trim() : "";
    }

    /** A string, or the strings in an array, trimmed; empty ones and anything else are left out. */
    private static List<String> strings(Object value) {
        JSONArray array = value instanceof JSONArray ? (JSONArray) value : new JSONArray().put(value);
        List<String> strings = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object entry = array.opt(i);
            String string = entry instanceof String ? ((String) entry).trim() : "";
            if (!string.isEmpty()) strings.add(string);
        }
        return strings;
    }

    /** Cuts to at most {@code maxChars}, one fewer when the cut would split a surrogate pair. */
    private static String cap(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        return value.substring(0, Character.isHighSurrogate(value.charAt(maxChars - 1)) ? maxChars - 1 : maxChars);
    }

    private static String author(JSONObject item, Repo repo) {
        JSONArray authors = item.optJSONArray("authors");
        JSONObject first = authors == null ? null : authors.optJSONObject(0);
        String name = first == null ? "" : string(first, "name");
        return name.isEmpty() ? repo.owner : name;
    }

    /** GitHub's star order, then manifest order. */
    static List<Theme> sorted(Collection<Theme> themes) {
        List<Theme> list = new ArrayList<>(themes);
        Collections.sort(list, (a, b) -> Integer.compare(a.order, b.order));
        return list;
    }

    static List<Theme> filter(List<Theme> themes, String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return themes;
        List<Theme> matches = new ArrayList<>();
        for (Theme theme : themes) {
            String haystack = (theme.title + "\n" + theme.author + "\n" + theme.description).toLowerCase(Locale.ROOT);
            if (haystack.contains(needle)) matches.add(theme);
        }
        return matches;
    }

    static String toJson(List<Theme> themes, long savedAt) throws JSONException {
        JSONArray array = new JSONArray();
        for (Theme theme : themes) {
            array.put(new JSONObject().put("title", theme.title).put("description", theme.description)
                    .put("author", theme.author).put("preview", theme.previewUrl == null ? JSONObject.NULL : theme.previewUrl)
                    .put("schemes", theme.schemesUrl == null ? JSONObject.NULL : theme.schemesUrl).put("repo", theme.repoUrl)
                    .put("stars", theme.stars).put("order", theme.order)
                    .put("usercss", theme.usercssUrl).put("include", new JSONArray(theme.includeUrls))
                    .put("kind", theme.kind == Kind.EXTENSION ? "extension" : "theme")
                    .put("main", theme.mainUrl == null ? JSONObject.NULL : theme.mainUrl)
                    .put("android", theme.androidId == null ? JSONObject.NULL : theme.androidId));
        }
        return new JSONObject().put("savedAt", savedAt).put("themes", array).toString();
    }

    /**
     * A cache written before usercss and include were kept still loads, with nothing to find an
     * image in; one written before kind, main and android were kept loads every item as a theme.
     */
    static Cached fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray array = root.getJSONArray("themes");
        List<Theme> themes = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            String preview = string(item, "preview");
            String usercss = string(item, "usercss");
            String schemes = string(item, "schemes");
            String main = string(item, "main");
            String android = string(item, "android");
            themes.add(new Theme(item.getString("title"), item.getString("description"), item.getString("author"),
                    preview.isEmpty() ? null : preview, schemes.isEmpty() ? null : schemes, item.getString("repo"),
                    item.getInt("stars"), item.getInt("order"), null,
                    usercss.isEmpty() ? null : usercss, strings(item.opt("include")),
                    "extension".equals(string(item, "kind")) ? Kind.EXTENSION : Kind.THEME,
                    main.isEmpty() ? null : main, android.isEmpty() ? null : android));
        }
        return new Cached(themes, root.getLong("savedAt"));
    }
}
