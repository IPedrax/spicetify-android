package app.spicetify.extension.spotify.theme;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** The theme selected in Spicetify settings, saved on this device. */
public final class ThemeState {
    public static final String SCHEME = "scheme";
    private static final String FILE = "spicetify_theme";

    public static final class Selection {
        /** One of the {@link ThemePresets} kinds, or {@link #SCHEME}. */
        public final String kind;
        public final String label;
        /** Role colors of a scheme; empty for presets, which are computed when applied. */
        public final Map<String, Integer> colors;

        public Selection(String kind, String label, Map<String, Integer> colors) {
            this.kind = kind;
            this.label = label;
            this.colors = Collections.unmodifiableMap(new LinkedHashMap<>(colors));
        }

        public static Selection preset(String kind, String label) {
            return new Selection(kind, label, Collections.emptyMap());
        }
    }

    private ThemeState() {}

    public static Selection load(Context context) {
        SharedPreferences preferences = preferences(context);
        Map<String, Integer> colors = new LinkedHashMap<>();
        try {
            JSONObject saved = new JSONObject(preferences.getString("colors", "{}"));
            for (Iterator<String> keys = saved.keys(); keys.hasNext(); ) {
                String key = keys.next();
                colors.put(key, (int) saved.getLong(key));
            }
        } catch (JSONException corrupted) {
            colors.clear();
        }
        return new Selection(preferences.getString("kind", ThemePresets.STOCK),
                preferences.getString("label", "Spotify default"), colors);
    }

    static void save(Context context, Selection selection) {
        JSONObject colors = new JSONObject();
        try {
            for (Map.Entry<String, Integer> color : selection.colors.entrySet()) {
                colors.put(color.getKey(), color.getValue() & 0xFFFFFFFFL);
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        preferences(context).edit().putString("kind", selection.kind).putString("label", selection.label)
                .putString("colors", colors.toString()).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
