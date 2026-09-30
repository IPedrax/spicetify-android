package app.spicetify.extension.spotify.extensions;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import app.spicetify.extension.spotify.settings.SpicetifySettingsScreen;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Trash Bin (desktop {@code trashbin.js}): a song or artist the user throws away is skipped as
 * soon as it plays. The sets persist in SharedPreferences {@code "spicetify_trash"}, one JSON
 * string per category, in the desktop {@code {"songs":{uri:true},"artists":{uri:true}}} shape so
 * export and import need no conversion.
 * <p>
 * Threading: {@link #onState} arrives on the player bridge thread; the controls' buttons and the
 * track menu (a later task) run on the UI thread. {@link #SONGS} and {@link #ARTISTS} are
 * concurrent sets so both sides read and write them without extra locking, and {@link #handle} is
 * the single synchronized gate that decides whether a track gets skipped.
 */
final class TrashBin {
    private static final String PREFERENCES = "spicetify_trash";
    private static final String SONGS_KEY = "songs";
    private static final String ARTISTS_KEY = "artists";

    private static final Set<String> SONGS = ConcurrentHashMap.newKeySet();
    private static final Set<String> ARTISTS = ConcurrentHashMap.newKeySet();
    private static final Guard GUARD = new Guard();
    private static final PlayerBridge.StateListener LISTENER = TrashBin::onState;

    private static boolean loaded; // guarded by TrashBin.class, with handle()
    private static String lastHandledTrackUid; // guarded by TrashBin.class, with handle()
    private static boolean blockedNotified; // guarded by TrashBin.class, with handle()

    private TrashBin() {}

    static void register() {
        Extensions.register(Extensions.TRASH_BIN, TrashBin::controls);
        Extensions.onSwitch(Extensions.TRASH_BIN, TrashBin::onSwitch);
    }

    /**
     * Adds the state listener when turned on, and removes it when turned off. Off also resets the
     * skip state, so a track that was already handled before the switch cycled gets reconsidered.
     */
    private static void onSwitch(Context context, boolean on) {
        if (!on) {
            PlayerBridge.removeStateListener(LISTENER);
            reset();
            return;
        }
        if (!Extensions.isOn(context, Extensions.TRASH_BIN)) return; // lost a race with another switch
        ensureLoaded(context);
        PlayerBridge.addStateListener(LISTENER);
        // The stream may already be open because of another extension's listener, in which case no
        // new state arrives until the next track; evaluate the one already known right away.
        Esperanto.PlayerState state = PlayerBridge.lastState();
        if (state != null) handle(context, state, false);
    }

    private static synchronized void reset() {
        lastHandledTrackUid = null;
        blockedNotified = false;
        GUARD.reset();
    }

    // ---- Sets ----

    static boolean isSongTrashed(String uri) {
        ensureLoaded(Extensions.appContext());
        return uri != null && SONGS.contains(uri);
    }

    static boolean isArtistTrashed(String uri) {
        ensureLoaded(Extensions.appContext());
        return uri != null && ARTISTS.contains(uri);
    }

    /** Trashing the track {@link PlayerBridge#lastState()} reports as playing also skips it. */
    static void setSong(Context context, String uri, boolean trashed) {
        setTrashed(context, SONGS, SONGS_KEY, uri, trashed);
        if (!trashed || uri == null) return;
        Esperanto.PlayerState state = PlayerBridge.lastState();
        if (state != null && uri.equals(state.trackUri)) handle(context, state, true);
    }

    /** Trashing the artist of the track {@link PlayerBridge#lastState()} reports also skips it. */
    static void setArtist(Context context, String uri, boolean trashed) {
        setTrashed(context, ARTISTS, ARTISTS_KEY, uri, trashed);
        if (!trashed || uri == null) return;
        Esperanto.PlayerState state = PlayerBridge.lastState();
        if (state != null && state.artistUris != null && state.artistUris.contains(uri)) {
            handle(context, state, true);
        }
    }

    private static void setTrashed(Context context, Set<String> set, String key, String uri, boolean trashed) {
        ensureLoaded(context);
        if (uri == null) return;
        if (trashed) set.add(uri); else set.remove(uri);
        save(context, key, set);
    }

    static void clear(Context context) {
        ensureLoaded(context);
        SONGS.clear();
        ARTISTS.clear();
        save(context, SONGS_KEY, SONGS);
        save(context, ARTISTS_KEY, ARTISTS);
    }

    // ---- Import and export ----

    static String exportJson() {
        ensureLoaded(Extensions.appContext());
        try {
            JSONObject root = new JSONObject();
            root.put(SONGS_KEY, toJsonObject(SONGS));
            root.put(ARTISTS_KEY, toJsonObject(ARTISTS));
            return root.toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Merges {@code json}'s songs and artists into the existing sets; either key may be absent. */
    static void importJson(Context context, String json) throws JSONException {
        ensureLoaded(context);
        JSONObject root = new JSONObject(json);
        mergeInto(root.optJSONObject(SONGS_KEY), SONGS);
        mergeInto(root.optJSONObject(ARTISTS_KEY), ARTISTS);
        save(context, SONGS_KEY, SONGS);
        save(context, ARTISTS_KEY, ARTISTS);
    }

    private static void mergeInto(JSONObject object, Set<String> into) {
        if (object == null) return;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) into.add(keys.next());
    }

    private static JSONObject toJsonObject(Set<String> uris) throws JSONException {
        JSONObject object = new JSONObject();
        for (String uri : uris) object.put(uri, true);
        return object;
    }

    // ---- Auto skip ----

    /** Ads and episodes are left alone; otherwise true when the track or one of its artists is trashed. */
    static boolean shouldSkip(Esperanto.PlayerState state) {
        if (state == null || state.advertisement || state.episode) return false;
        if (state.trackUri != null && SONGS.contains(state.trackUri)) return true;
        if (state.artistUris != null) {
            for (String artistUri : state.artistUris) {
                if (artistUri != null && ARTISTS.contains(artistUri)) return true;
            }
        }
        return false;
    }

    /** On the player bridge thread. */
    private static void onState(Esperanto.PlayerState state) {
        try {
            handle(Extensions.appContext(), state, false);
        } catch (Throwable e) {
            Log.w("Spicetify", "Trash Bin couldn't process a player state", e);
        }
    }

    /**
     * The one gate a track goes through, whether it arrived from the state stream or from trashing
     * the playing song directly. {@code force} skips the "already handled" check: trashing the
     * playing song must skip it even though its track id was already handled as not trashed.
     */
    private static synchronized void handle(Context context, Esperanto.PlayerState state, boolean force) {
        if (state == null || state.trackUid == null) return;
        if (!force && state.trackUid.equals(lastHandledTrackUid)) return;
        lastHandledTrackUid = state.trackUid;
        if (!shouldSkip(state)) return;
        if (!GUARD.allow(System.currentTimeMillis())) {
            if (!blockedNotified) {
                blockedNotified = true;
                notifyBlocked(context);
            }
            return;
        }
        blockedNotified = false;
        skip(context, state.trackUri);
    }

    private static void notifyBlocked(Context context) {
        String line = "Stopped: 5 skips in 10 seconds";
        Extensions.status(context, Extensions.TRASH_BIN, line);
        onMain(() -> Toast.makeText(context, line, Toast.LENGTH_LONG).show());
    }

    private static void skip(Context context, String trackUri) {
        PlayerBridge.call(Esperanto.CONTEXT_PLAYER, "SkipNext", Esperanto.skipNext(), new PlayerBridge.Result() {
            @Override
            public void done(byte[] body) {
                try {
                    int error = Esperanto.parseResult(body);
                    Extensions.status(context, Extensions.TRASH_BIN,
                            error == Esperanto.FORBIDDEN ? "Spotify refused the skip" : "Skipped " + trackUri);
                } catch (Throwable e) {
                    Log.w("Spicetify", "Trash Bin couldn't read the skip result", e);
                }
            }

            @Override
            public void failed(String reason) {
                // The router was lost mid skip; nothing more to report here.
            }
        });
    }

    // ---- Persistence ----

    private static synchronized void ensureLoaded(Context context) {
        if (loaded || context == null) return;
        readInto(context, SONGS_KEY, SONGS);
        readInto(context, ARTISTS_KEY, ARTISTS);
        loaded = true;
    }

    private static void readInto(Context context, String key, Set<String> into) {
        try {
            JSONObject saved = new JSONObject(preferences(context).getString(key, "{}"));
            Iterator<String> keys = saved.keys();
            while (keys.hasNext()) into.add(keys.next());
        } catch (JSONException malformed) {
            Log.w("Spicetify", "Couldn't read trashed " + key, malformed);
        }
    }

    private static void save(Context context, String key, Set<String> uris) {
        try {
            preferences(context).edit().putString(key, toJsonObject(uris).toString()).apply();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    // ---- Controls ----

    private static View controls(Context context) {
        ensureLoaded(context);
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        TextView counts = SpicetifySettingsScreen.text(context, countsLine(), false);
        section.addView(counts);

        section.addView(button(context, "Clear", () -> {
            clear(context);
            counts.setText(countsLine());
        }));
        section.addView(button(context, "Export", () -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Trash Bin", exportJson()));
            Toast.makeText(context, "Trash list copied", Toast.LENGTH_SHORT).show();
        }));
        section.addView(button(context, "Import", () -> showImportDialog(context, counts)));
        return section;
    }

    private static Button button(Context context, String label, Runnable action) {
        Button result = new Button(context);
        result.setText(label);
        result.setAllCaps(false);
        result.setOnClickListener(view -> safely(label, action));
        result.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return result;
    }

    private static void showImportDialog(Context context, TextView counts) {
        EditText field = new EditText(context);
        field.setHint("Paste exported trash JSON");
        new AlertDialog.Builder(context).setTitle("Import trash").setView(field)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Import", (dialog, which) -> safely("Import", () -> {
                    try {
                        importJson(context, field.getText().toString());
                    } catch (JSONException e) {
                        Toast.makeText(context, "Couldn't import: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    counts.setText(countsLine());
                })).show();
    }

    private static String countsLine() {
        return SONGS.size() + " songs, " + ARTISTS.size() + " artists";
    }

    /** Every click handler runs through here, so one that throws is logged instead of reaching Spotify. */
    private static void safely(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            Log.w("Spicetify", "Trash Bin " + what + " failed", e);
        }
    }

    private static void onMain(Runnable work) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                work.run();
            } catch (Throwable e) {
                Log.w("Spicetify", "Trash Bin toast could not be shown", e);
            }
        });
    }

    /** False once 5 skips have happened within the last 10 seconds; a quiet 10 seconds resets it. */
    static final class Guard {
        private static final int MAX_SKIPS = 5;
        private static final long WINDOW_MILLIS = 10_000;
        private final long[] times = new long[MAX_SKIPS];
        private int count;

        synchronized boolean allow(long nowMillis) {
            int kept = 0;
            for (int i = 0; i < count; i++) {
                if (times[i] >= nowMillis - WINDOW_MILLIS) times[kept++] = times[i];
            }
            count = kept;
            if (count >= MAX_SKIPS) return false;
            times[count++] = nowMillis;
            return true;
        }

        synchronized void reset() {
            count = 0;
        }
    }
}
