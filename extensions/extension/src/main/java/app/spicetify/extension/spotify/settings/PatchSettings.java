package app.spicetify.extension.spotify.settings;

import android.content.Context;
import android.content.SharedPreferences;
import app.spicetify.extension.spotify.home.HomePins;
import app.spicetify.extension.spotify.localserver.ServerConfig;
import app.spicetify.extension.spotify.theme.ThemeRuntime;

public final class PatchSettings {
    private static final String FILE = "spicetify_patch_settings";
    private static final String CLEAN_SHARING = "clean_sharing";
    private static volatile SharedPreferences preferences;
    private static volatile Context applicationContext;

    private PatchSettings() {}

    public static void initialize(Context context) {
        applicationContext = context.getApplicationContext();
        preferences = applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (InstalledPatches.themeColors()) ThemeRuntime.install(context);
        if (InstalledPatches.homePins()) HomePins.initialize(context);
        if (InstalledPatches.serverFiles()) ServerConfig.initialize(context);
    }

    /** The application context Spotify's onCreate passed to {@link #initialize}, or null before then. */
    public static Context applicationContext() {
        return applicationContext;
    }

    public static boolean cleanSharingEnabled() {
        SharedPreferences current = preferences;
        return current == null || current.getBoolean(CLEAN_SHARING, true);
    }

    public static void setCleanSharingEnabled(boolean enabled) {
        SharedPreferences current = preferences;
        if (current == null) throw new IllegalStateException("Spicetify settings are not initialized.");
        current.edit().putBoolean(CLEAN_SHARING, enabled).apply();
    }
}
