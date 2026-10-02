package app.spicetify.extension.spotify.theme;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.widget.TextView;
import android.widget.Toast;
import app.spicetify.extension.spotify.settings.SpicetifySettingsScreen;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The top of the Spicetify settings screen: the Marketplace button, then the Theme section with the
 * current theme. It also holds the dialogs that apply a theme, which the Marketplace uses.
 */
public final class ThemeSection {
    private ThemeSection() {}

    /**
     * The Marketplace button, in Spotify's green. It shows on any Android version, since the
     * Marketplace's Extensions tab is where extensions turn on.
     */
    public static void addMarketplaceButton(LinearLayout page, Runnable openMarketplace) {
        Button marketplace = addButton(page, "Spicetify Marketplace", openMarketplace);
        marketplace.setBackgroundTintList(ColorStateList.valueOf(MarketplaceScreen.GREEN));
        marketplace.setTextColor(Color.BLACK);
        marketplace.setTypeface(null, Typeface.BOLD);
    }

    /**
     * The current theme, and the blur switch while an image is set. On Android 13 and older, only
     * why there are no themes.
     *
     * @param context         the settings screen's themed context
     * @param onApplied       runs after a theme was applied, to close the screen and recreate Spotify's activity
     * @param openMarketplace opens the Marketplace, which the current theme does when tapped
     */
    public static View create(Context context, boolean supported, Runnable onApplied, Runnable openMarketplace) {
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        section.addView(SpicetifySettingsScreen.text(context, "Theme", true));
        if (!supported) {
            section.addView(SpicetifySettingsScreen.text(
                    context, "In-app themes need Android 14 or later.", false));
            return section;
        }
        ThemeState.Selection current = ThemeState.load(context);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        MarketplaceScreen.paintStrip(MarketplaceScreen.addStrip(card), current);
        TextView title = SpicetifySettingsScreen.text(context, name(current), false);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        card.addView(title);
        SpicetifySettingsScreen.onTap(card, openMarketplace);
        section.addView(card);
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
     * A theme's name in settings. A scheme, saved by {@link #applyScheme} as "Galaxy V2 (Galaxy)",
     * reads "Galaxy V2, scheme Galaxy"; a preset keeps its label.
     */
    static String name(ThemeState.Selection theme) {
        // ponytail: the scheme is the last " (", so a scheme name with " (" of its own splits there.
        int open = theme.label.lastIndexOf(" (");
        if (!ThemeState.SCHEME.equals(theme.kind) || open < 0 || !theme.label.endsWith(")")) return theme.label;
        return theme.label.substring(0, open) + ", scheme " + theme.label.substring(open + 2, theme.label.length() - 1);
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

    static Button addButton(LinearLayout section, String label, Runnable action) {
        Button button = new Button(section.getContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> {
            try {
                action.run();
            } catch (Throwable e) {
                Log.w("Spicetify", label + " failed", e);
            }
        });
        section.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    /** Asks for a pasted color.ini or CSS and an optional accent key, then applies one of its schemes. */
    static void paste(Context context, Runnable onApplied) {
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
                    } catch (Throwable e) {
                        // Anything else would reach Spotify through the dialog's click handling.
                        Log.w("Spicetify", "Couldn't apply the pasted theme", e);
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
