package app.spicetify.extension.spotify.extensions;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Android side of Spicetify's desktop extensions: ids stable across ports, the table that
 * matches a desktop extension's manifest entry to its Android id, and each extension's switch,
 * name, description and Marketplace controls.
 */
public final class Extensions {
    public static final String TRASH_BIN = "trash_bin";
    public static final String RANDOM_SONG = "random_song";
    public static final String SHUFFLE_PLUS = "shuffle_plus";
    public static final String HIDE_PODCASTS = "hide_podcasts";

    private static final String PREFERENCES = "spicetify_extensions";
    /** Ported desktop extensions, keyed by {@code owner/repo/main}, lowercased. */
    private static final Map<String, String> PORTS = ports();
    private static final Map<String, Controls> CONTROLS = Collections.synchronizedMap(new HashMap<>());

    /** An extension's own buttons and options, shown in its Marketplace dialog under its switch. */
    public interface Controls {
        View create(Context context);
    }

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

    /** Whether the user turned extension {@code id} on; every extension starts off. */
    public static boolean isOn(Context context, String id) {
        return preferences(context).getBoolean(id, false);
    }

    public static void setOn(Context context, String id, boolean on) {
        preferences(context).edit().putBoolean(id, on).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** The name extension {@code id} goes by on Android, or null for an unknown id. */
    public static String title(String id) {
        switch (id) {
            case TRASH_BIN: return "Trash Bin";
            case RANDOM_SONG: return "Play a random song";
            case SHUFFLE_PLUS: return "Shuffle+";
            case HIDE_PODCASTS: return "Hide podcasts";
            default: return null;
        }
    }

    /** What extension {@code id} does on Android, or null for an unknown id. */
    public static String description(String id) {
        switch (id) {
            case TRASH_BIN: return "Throw songs and artists in the trash from their menus, and Spotify skips them.";
            case RANDOM_SONG: return "Play a random song from all of Spotify, or from your library.";
            case SHUFFLE_PLUS: return "Shuffle what's playing in a truly random order.";
            case HIDE_PODCASTS: return "Remove podcasts and episodes from Home, Search and your Library's filters.";
            default: return null;
        }
    }

    /** Gives extension {@code id} its controls, replacing any it had; null leaves it with only its switch. */
    public static void register(String id, Controls controls) {
        CONTROLS.put(id, controls);
    }

    /** The controls registered for extension {@code id}, or null when it has only its switch. */
    public static Controls controls(String id) {
        return CONTROLS.get(id);
    }
}
