package app.spicetify.extension.spotify.theme;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.Toast;
import app.spicetify.extension.spotify.settings.SpicetifySettingsScreen;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The Theme part of the Spicetify settings screen. */
public final class ThemeSection {
    private static final String[][] PRESETS = {
            {ThemePresets.STOCK, "Spotify default"},
            {ThemePresets.AMOLED, "AMOLED black"},
            {ThemePresets.MATERIAL_YOU, "Material You"},
            {ThemePresets.MATERIAL_YOU_BLACK, "Material You, black background"},
    };

    private ThemeSection() {}

    /**
     * @param context   the settings screen's themed context
     * @param onApplied runs after a theme was applied, to close the screen and recreate Spotify's activity
     */
    public static View create(Context context, boolean supported, Runnable onApplied) {
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        section.addView(SpicetifySettingsScreen.text(context, "Theme", true));
        if (!supported) {
            section.addView(SpicetifySettingsScreen.text(
                    context, "In-app themes need Android 14 or later.", false));
            return section;
        }
        section.addView(SpicetifySettingsScreen.text(
                context, "Current theme: " + ThemeState.load(context).label, false));
        for (String[] preset : PRESETS) {
            addButton(section, preset[1], () ->
                    apply(context, ThemeState.Selection.preset(preset[0], preset[1]), null, onApplied));
        }
        addButton(section, "Spicetify Marketplace", () -> MarketplaceScreen.open(context, onApplied));
        addButton(section, "Paste a Spicetify theme", () -> paste(context, onApplied));
        if (ThemeBackground.hasImage(context)) {
            Switch blur = new Switch(context);
            blur.setText("Blur background image");
            blur.setTextColor(Color.WHITE);
            blur.setChecked(ThemeBackground.blurEnabled(context));
            blur.setOnCheckedChangeListener((button, enabled) -> {
                ThemeBackground.setBlur(context, enabled);
                // Recreating the activity draws the background again with the new choice.
                onApplied.run();
            });
            section.addView(blur, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        section.addView(SpicetifySettingsScreen.text(
                context, "Some colors change after Spotify restarts.", false));
        return section;
    }

    /**
     * Saves the theme's background image, or clears it when {@code background} is null, then applies
     * a selection and reports the outcome. The image goes first because select passes
     * {@link ThemeBackground#hasImage} to Compose.
     */
    static void apply(Context context, ThemeState.Selection selection, byte[] background, Runnable onApplied) {
        try {
            if (background == null) {
                ThemeBackground.clear(context);
            } else {
                ThemeBackground.save(context, background);
            }
        } catch (IOException e) {
            error(context, "Couldn't save the background image: " + describe(e));
            return;
        }
        if (ThemeRuntime.select(context, selection)) {
            Toast.makeText(context, "Theme applied: " + selection.label, Toast.LENGTH_SHORT).show();
            onApplied.run();
        } else {
            error(context, "This device couldn't apply the theme.");
        }
    }

    static void addButton(LinearLayout section, String label, Runnable action) {
        Button button = new Button(section.getContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                Log.w("Spicetify", label + " failed", e);
            }
        });
        section.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static void paste(Context context, Runnable onApplied) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText text = new EditText(context);
        text.setHint("Paste a color.ini, or CSS with --spice-* colors");
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        text.setMinLines(6);
        EditText accent = new EditText(context);
        accent.setHint("Accent key (optional, for example mauve)");
        accent.setSingleLine(true);
        form.addView(text);
        form.addView(accent);
        new AlertDialog.Builder(context).setTitle("Paste a Spicetify theme").setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    try {
                        List<SpicetifyTheme.Scheme> schemes = SpicetifyTheme.parse(text.getText().toString());
                        chooseScheme(context, schemes, accent.getText().toString(), "Pasted theme", null, onApplied);
                    } catch (ThemeException e) {
                        error(context, e.getMessage());
                    }
                }).show();
    }

    /** Asks which scheme to use when there's more than one, then applies it. */
    static void chooseScheme(Context context, List<SpicetifyTheme.Scheme> schemes, String accentKey,
            String themeName, byte[] background, Runnable onApplied) {
        if (schemes.size() == 1) {
            applyScheme(context, schemes.get(0), accentKey, themeName, background, onApplied);
            return;
        }
        String[] names = new String[schemes.size()];
        for (int i = 0; i < names.length; i++) names[i] = schemes.get(i).name;
        new AlertDialog.Builder(context).setTitle("Choose a color scheme")
                .setItems(names, (dialog, which) ->
                        applyScheme(context, schemes.get(which), accentKey, themeName, background, onApplied))
                .setNegativeButton("Cancel", null).show();
    }

    static void applyScheme(Context context, SpicetifyTheme.Scheme scheme, String accentKey,
            String themeName, byte[] background, Runnable onApplied) {
        String key = accentKey == null || accentKey.trim().isEmpty() ? "button" : accentKey.trim().toLowerCase(Locale.ROOT);
        try {
            ThemeResolver.Result theme = ThemeResolver.resolve(scheme.colors, key);
            String label = themeName + " (" + scheme.name + ")";
            if (theme.colors.isEmpty()) {
                error(context, label + " has no colors Spotify can use.");
                return;
            }
            List<String> warnings = ThemeResolver.warnings(theme);
            if (!warnings.isEmpty()) Toast.makeText(context, String.join(" ", warnings), Toast.LENGTH_LONG).show();
            // The image only shows through a see-through page.
            Map<String, Integer> colors = background == null ? theme.colors : ThemeResolver.seeThrough(theme.colors);
            apply(context, new ThemeState.Selection(ThemeState.SCHEME, label, colors), background, onApplied);
        } catch (ThemeException e) {
            error(context, e.getMessage());
        }
    }

    static void error(Context context, String message) {
        new AlertDialog.Builder(context).setTitle("Theme not applied").setMessage(message)
                .setPositiveButton("OK", null).show();
    }

    /** {@code e.getMessage()}, or the exception's class name when there's no message to show. */
    static String describe(Throwable e) {
        String message = e.getMessage();
        return message != null ? message : e.getClass().getSimpleName();
    }

    /**
     * Shows a download's result on the main thread. Spotify's screen can be gone by then (the user
     * left it, or the activity was recreated), and showing a dialog on it would crash Spotify.
     */
    static void onMain(Runnable work) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                Log.w("Spicetify", "Download result could not be shown", e);
            }
        });
    }
}
