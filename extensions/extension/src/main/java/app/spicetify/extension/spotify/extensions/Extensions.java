package app.spicetify.extension.spotify.extensions;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import app.spicetify.extension.spotify.settings.PatchSettings;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Android side of Spicetify's desktop extensions: ids stable across ports, the table that
 * matches a desktop extension's manifest entry to its Android id, and each extension's switch,
 * name, description, Marketplace controls, actions and status.
 */
public final class Extensions {
    public static final String TRASH_BIN = "trash_bin";
    public static final String RANDOM_SONG = "random_song";
    public static final String SHUFFLE_PLUS = "shuffle_plus";
    public static final String HIDE_PODCASTS = "hide_podcasts";

    private static final String PREFERENCES = "spicetify_extensions";
    /** The Android extensions, in the order Spicetify settings lists their status. */
    private static final String[] IDS = {TRASH_BIN, RANDOM_SONG, SHUFFLE_PLUS, HIDE_PODCASTS};
    private static final String LOG = "spicetify_extensions.log";
    private static final int LOG_LIMIT = 256 * 1024;
    private static final int LOG_KEEP = 128 * 1024;
    /** Ported desktop extensions, keyed by {@code owner/repo/main}, lowercased. */
    private static final Map<String, String> PORTS = ports();
    private static final Map<String, Controls> CONTROLS = Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, Action> ACTIONS = new ConcurrentHashMap<>();
    private static final Map<String, SwitchListener> SWITCHES = new ConcurrentHashMap<>();
    private static final Map<String, String> STATUS = new ConcurrentHashMap<>();
    /** One daemon thread for the log file, parked while idle, so lines land in the order they came. */
    private static final ExecutorService LOG_WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Spicetify extensions log");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Context appContext;
    private static boolean registered; // guarded by Extensions.class

    /** An extension's own buttons and options, shown in its Marketplace dialog under its switch. */
    public interface Controls {
        View create(Context context);
    }

    /**
     * Something an extension does on request, from a menu item or a button in its controls. It runs
     * on the main thread (the menu click), so an action that waits on the bridge or the network must
     * return at once and do its work on the bridge thread.
     */
    interface Action {
        void run(Context context);
    }

    /**
     * Hears extension {@code id} turned on or off. {@link #setOn} calls it on the UI thread, and
     * {@link #startEnabled} calls it with true on the bridge thread each time Spotify's core starts,
     * so the two can overlap: a listener should check {@link #isOn} again before it starts anything.
     */
    interface SwitchListener {
        void onSwitch(Context context, boolean on);
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

    /** Saves the switch, then tells the extension's listener. */
    public static void setOn(Context context, String id, boolean on) {
        ensureRegistered();
        preferences(context).edit().putBoolean(id, on).apply();
        SwitchListener listener = SWITCHES.get(id);
        if (listener != null) tell(listener, context, on);
    }

    /** Gives extension {@code id} the listener that {@link #setOn} and {@link #startEnabled} call. */
    static void onSwitch(String id, SwitchListener listener) {
        SWITCHES.put(id, listener);
    }

    /** Starts every extension that is on. The bridge calls this once Spotify's core is up. */
    static void startEnabled(Context context) {
        ensureRegistered();
        for (Map.Entry<String, SwitchListener> entry : SWITCHES.entrySet()) {
            if (isOn(context, entry.getKey())) tell(entry.getValue(), context, true);
        }
    }

    /** A failing listener is logged, so it can't undo the switch or stop the other extensions. */
    private static void tell(SwitchListener listener, Context context, boolean on) {
        try {
            listener.onSwitch(context, on);
        } catch (Throwable e) {
            Log.w("Spicetify", "An extension switch listener failed", e);
        }
    }

    /** The switches, and options such as Hide podcasts' audiobook option. */
    static SharedPreferences preferences(Context context) {
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
        ensureRegistered();
        return CONTROLS.get(id);
    }

    static void registerAction(String id, Action action) {
        ACTIONS.put(id, action);
    }

    /** The action registered as {@code id}, or null. */
    static Action action(String id) {
        ensureRegistered();
        return ACTIONS.get(id);
    }

    /** Registers each extension's controls, actions and switch listener, the first time anything asks. */
    static synchronized void ensureRegistered() {
        if (registered) return;
        registered = true;
        // Each extension adds its register() call here, in a try of its own, so one that throws
        // can't stop the others registering or reach Spotify through setOn or controls:
        try {
            TrashBin.register();
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't register Trash Bin", e);
        }
        try {
            RandomSong.register();
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't register Play a random song", e);
        }
        try {
            ShufflePlus.register();
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't register Shuffle+", e);
        }
        try {
            HidePodcasts.register();
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't register Hide podcasts", e);
        }
    }

    /**
     * Keeps {@code line} as extension {@code id}'s latest status, then appends it, timestamped, to
     * {@code files/spicetify_extensions.log} on the log's own thread, so a hook that reports never
     * waits on the disk. The file exists because logcat is filtered on the test phone. Without a
     * context the line is only kept. Never throws.
     */
    static void status(Context context, String id, String line) {
        try {
            STATUS.put(id, line);
            if (context == null) return;
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            String entry = iso.format(new Date()) + " " + id + ": " + line + "\n";
            LOG_WRITER.execute(() -> {
                try {
                    append(new File(context.getFilesDir(), LOG), entry);
                } catch (Throwable e) {
                    Log.w("Spicetify", "Couldn't write the extensions log", e);
                }
            });
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't record the status of " + id, e);
        }
    }

    /** On the log's thread. Past 256 KB, the log keeps its last 128 KB, starting at a whole line. */
    private static void append(File log, String entry) throws IOException {
        try (FileOutputStream out = new FileOutputStream(log, true)) {
            out.write(entry.getBytes(StandardCharsets.UTF_8));
        }
        if (log.length() <= LOG_LIMIT) return;
        try (RandomAccessFile file = new RandomAccessFile(log, "rw")) {
            byte[] tail = new byte[LOG_KEEP];
            file.seek(file.length() - LOG_KEEP);
            file.readFully(tail);
            int cut = 0;
            while (cut < tail.length && tail[cut] != '\n') cut++;
            int start = cut < tail.length ? cut + 1 : 0;
            file.seek(0);
            file.write(tail, start, tail.length - start);
            file.setLength(tail.length - start);
        }
    }

    /** The bridge's line, then each extension that is on with its latest status, for Spicetify settings. */
    public static List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add(PlayerBridge.statusLine());
        Context context = appContext();
        if (context == null) return lines;
        for (String id : IDS) {
            if (!isOn(context, id)) continue;
            String last = STATUS.get(id);
            lines.add(title(id) + ": " + (last == null ? "on" : last));
        }
        return lines;
    }

    /**
     * Spotify's application context. The bridge sets it when Spotify's core starts. Before that,
     * or if the bridge couldn't find it, it's the one {@link PatchSettings} got in Spotify's onCreate.
     */
    static Context appContext() {
        Context found = appContext;
        return found != null ? found : PatchSettings.applicationContext();
    }

    static void setAppContext(Context context) {
        appContext = context;
    }
}
