package app.spicetify.extension.spotify.theme;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/** Loads the selected theme into Spotify's resources at startup, and applies new selections. */
public final class ThemeRuntime {
    private static final String TAG = "Spicetify";

    private ThemeRuntime() {}

    /** In-app themes need Android 14 and a system overlay manager. */
    public static boolean supported(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && ThemeOverlay.isAvailable(context);
    }

    /** Injection point, from PatchSettings.initialize in SpotifyApplication.onCreate. */
    public static void install(Context context) {
        try {
            if (!supported(context)) return;
            Application application = (Application) context.getApplicationContext();
            ThemeOverlay.applyTo(application.getResources());
            application.registerActivityLifecycleCallbacks(new Callbacks());
            // Android deletes an app's own overlays when the app is installed again (Morphe found the
            // same), so the saved theme is registered on every start. Material You gets the current
            // wallpaper colors this way too.
            register(application, ThemeState.load(application));
        } catch (Exception e) {
            Log.w(TAG, "Theme could not be loaded", e);
        }
    }

    /** Applies and saves a selection. Returns false, and saves nothing, when it can't be applied. */
    public static boolean select(Context context, ThemeState.Selection selection) {
        try {
            // The role map is empty only when the theme patch didn't inject its table.
            if (!supported(context) || ThemeRoleMap.load().isEmpty()) return false;
            register(context, selection);
            ThemeState.save(context, selection);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Theme could not be applied", e);
            return false;
        }
    }

    /** Registers the overlay for a selection, or removes it for Spotify's own colors. */
    private static void register(Context context, ThemeState.Selection selection) throws IOException {
        Map<String, Integer> values = ThemeRoleMap.overlayValues(ThemeRoleMap.load(), roleColors(context, selection));
        // Compose reads the same values from memory, so it follows the theme even if the overlay fails.
        // Spike: a background image makes the page background see-through.
        ComposeTheme.update(values, ThemeBackground.hasImage(context));
        if (values.isEmpty()) {
            ThemeOverlay.unregister(context);
        } else {
            ThemeOverlay.register(context, values);
        }
    }

    static Map<String, Integer> roleColors(Context context, ThemeState.Selection selection) {
        switch (selection.kind) {
            case ThemePresets.AMOLED:
                return ThemePresets.amoled();
            case ThemePresets.MATERIAL_YOU:
                return ThemePresets.materialYou(context, false);
            case ThemePresets.MATERIAL_YOU_BLACK:
                return ThemePresets.materialYou(context, true);
            case ThemeState.SCHEME:
                return selection.colors;
            default:
                return Collections.emptyMap();
        }
    }

    /**
     * Loads the theme into each activity's resources before any of its views exist, and draws the
     * spike background image after, once the activity's own onCreate can no longer overwrite it.
     */
    @TargetApi(Build.VERSION_CODES.Q)
    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityPreCreated(Activity activity, Bundle state) {
            try {
                ThemeOverlay.applyTo(activity.getResources());
            } catch (RuntimeException e) {
                Log.w(TAG, "Theme could not be loaded into " + activity.getClass().getName(), e);
            }
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) {}

        @Override
        public void onActivityPostCreated(Activity activity, Bundle state) {
            try {
                ThemeBackground.applyTo(activity);
            } catch (RuntimeException e) {
                Log.w(TAG, "Background could not be applied to " + activity.getClass().getName(), e);
            }
        }

        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityResumed(Activity activity) {}
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        @Override public void onActivityDestroyed(Activity activity) {}
    }
}
