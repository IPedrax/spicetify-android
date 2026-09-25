package app.spicetify.extension.spotify.theme;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import app.spicetify.extension.spotify.settings.SpicetifySettingsScreen;

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
                    apply(context, ThemeState.Selection.preset(preset[0], preset[1]), onApplied));
        }
        section.addView(SpicetifySettingsScreen.text(
                context, "Some colors change after Spotify restarts.", false));
        return section;
    }

    /** Applies a selection, then reports the outcome. */
    static void apply(Context context, ThemeState.Selection selection, Runnable onApplied) {
        if (ThemeRuntime.select(context, selection)) {
            Toast.makeText(context, "Theme applied: " + selection.label, Toast.LENGTH_SHORT).show();
            onApplied.run();
        } else {
            new AlertDialog.Builder(context).setTitle("Theme not applied")
                    .setMessage("This device couldn't apply the theme.")
                    .setPositiveButton("OK", null).show();
        }
    }

    static void addButton(LinearLayout section, String label, Runnable action) {
        Button button = new Button(section.getContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        section.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
