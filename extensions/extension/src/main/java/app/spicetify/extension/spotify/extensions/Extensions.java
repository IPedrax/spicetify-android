package app.spicetify.extension.spotify.extensions;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Android side of Spicetify's desktop extensions: ids stable across ports, and the table that
 * matches a desktop extension's manifest entry to its Android id.
 */
public final class Extensions {
    public static final String TRASH_BIN = "trash_bin";
    public static final String RANDOM_SONG = "random_song";
    public static final String SHUFFLE_PLUS = "shuffle_plus";
    public static final String HIDE_PODCASTS = "hide_podcasts";

    /** Ported desktop extensions, keyed by {@code owner/repo/main}, lowercased. */
    private static final Map<String, String> PORTS = ports();

    private Extensions() {}

    private static Map<String, String> ports() {
        Map<String, String> ports = new HashMap<>();
        ports.put(key("spicetify", "cli", "Extensions/trashbin.js"), TRASH_BIN);
        ports.put(key("spicetify", "cli", "Extensions/shuffle+.js"), SHUFFLE_PLUS);
        ports.put(key("theRealPadster", "spicetify-hide-podcasts", "hidePodcasts.js"), HIDE_PODCASTS);
        return ports;
    }

    /** The Android id ported from a desktop extension's {@code owner}, {@code repo} and manifest {@code main}, or null. */
    public static String portFor(String owner, String repo, String mainPath) {
        return PORTS.get(key(owner, repo, mainPath));
    }

    /** Case-insensitive, and one leading {@code ./} in {@code mainPath} does not affect the match. */
    private static String key(String owner, String repo, String mainPath) {
        String main = mainPath.startsWith("./") ? mainPath.substring(2) : mainPath;
        return (owner + "/" + repo + "/" + main).toLowerCase(Locale.ROOT);
    }
}
