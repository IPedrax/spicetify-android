package app.spicetify.extension.spotify.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import app.spicetify.extension.spotify.home.HomePins;
import java.util.ArrayList;
import java.util.List;

/**
 * Spicetify settings, shown as a full-screen dialog over Spotify's current activity.
 * <p>
 * A root mount install keeps Spotify's stock manifest registered, so an activity the patch adds
 * to the manifest never exists for the system, and starting it crashed Spotify with
 * {@code ActivityNotFoundException}. A dialog needs no manifest entry. Spotify recreates its
 * activity on rotation, which closes the dialog; the settings row opens it again.
 */
public final class SpicetifySettingsScreen {
    static final String CLOSE_TAG = "spicetify_settings_close";
    private static final int BACKGROUND = Color.rgb(18, 18, 18);

    private final Activity activity;

    private SpicetifySettingsScreen(Activity activity) {
        this.activity = activity;
    }

    public static void open(Activity activity) {
        // Showing a dialog on a finishing activity throws, which would crash Spotify again.
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new SpicetifySettingsScreen(activity).show();
    }

    private void show() {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setFitsSystemWindows(true);
        root.addView(header(dialog));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(content(), new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        dialog.setContentView(root);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }

    private LinearLayout header(Dialog dialog) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton close = new ImageButton(activity);
        close.setTag(CLOSE_TAG);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription("Close Spicetify settings");
        close.setOnClickListener(view -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = text("Spicetify", true);
        title.setTextSize(20);
        header.addView(title);
        return header;
    }

    private LinearLayout content() {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        content.setPadding(padding, dp(16), padding, padding);

        boolean sharingInstalled = InstalledPatches.cleanSharing();
        boolean themeInstalled = InstalledPatches.themeColors();
        if (sharingInstalled) {
            Switch cleanSharing = new Switch(activity);
            cleanSharing.setText("Clean sharing links");
            cleanSharing.setTextSize(18);
            cleanSharing.setTextColor(Color.WHITE);
            cleanSharing.setMinHeight(dp(56));
            cleanSharing.setSwitchPadding(dp(24));
            cleanSharing.setThumbTintList(new ColorStateList(
                    new int[][] {new int[] {android.R.attr.state_checked}, new int[0]},
                    new int[] {Color.rgb(30, 215, 96), Color.LTGRAY}));
            cleanSharing.setChecked(PatchSettings.cleanSharingEnabled());
            cleanSharing.setOnCheckedChangeListener((button, enabled) ->
                    PatchSettings.setCleanSharingEnabled(enabled));
            content.addView(cleanSharing, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(text("Remove tracking parameters from Spotify links you share. "
                    + "Timestamps and playback context are preserved. Changes apply immediately.", false));
        }

        if (themeInstalled) {
            TextView heading = text("Theme colors", true);
            heading.setPadding(0, dp(24), 0, 0);
            content.addView(heading);
            content.addView(text("Your colors were selected in Morphe Manager. "
                    + "Change those options and repatch Spotify to use different colors.", false));
        }

        if (InstalledPatches.homePins()) {
            content.addView(text("Home shortcuts", true));
            content.addView(text("Choose which shortcuts appear first when Spotify includes them on Home. "
                    + "Return to Home once to load the choices. Restart Spotify after changing pins.", false));
            Button choose = new Button(activity);
            choose.setText("Choose pinned shortcuts");
            choose.setOnClickListener(view -> chooseHomePins());
            content.addView(choose);
        }

        if (InstalledPatches.serverFiles()) {
            content.addView(new ServerFilesSettings(activity));
        }

        if (!sharingInstalled && !themeInstalled && !InstalledPatches.homePins()
                && !InstalledPatches.serverFiles()) {
            content.addView(text("No configurable Spicetify patches are installed.", false));
        }
        return content;
    }

    private void chooseHomePins() {
        List<HomePins.Choice> choices = HomePins.choices();
        if (choices.isEmpty()) {
            new AlertDialog.Builder(activity).setTitle("No Home shortcuts loaded")
                    .setMessage("Return to Home and let its shortcuts load, then open this menu again.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[choices.size()];
        boolean[] selected = new boolean[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            HomePins.Choice choice = choices.get(i);
            boolean duplicate = false;
            for (HomePins.Choice other : choices) {
                if (!other.id.equals(choice.id) && other.label.equals(choice.label)) duplicate = true;
            }
            labels[i] = duplicate ? choice.label + "\n" + choice.id : choice.label;
            selected[i] = choice.pinned;
        }
        AlertDialog picker = new AlertDialog.Builder(activity).setTitle("Pinned Home shortcuts")
                .setMultiChoiceItems(labels, selected, (dialog, index, checked) -> selected[index] = checked)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null).create();
        picker.setOnShowListener(ignored -> picker.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    List<String> ids = new ArrayList<>();
                    for (int i = 0; i < choices.size(); i++) if (selected[i]) ids.add(choices.get(i).id);
                    try {
                        HomePins.setPinned(ids);
                    } catch (IllegalArgumentException changedSelection) {
                        Toast.makeText(activity, changedSelection.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    picker.dismiss();
                    new AlertDialog.Builder(activity).setMessage("Pins saved. Restart Spotify to refresh Home.")
                            .setPositiveButton("OK", null).show();
                }));
        picker.show();
    }

    private TextView text(String value, boolean heading) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(heading ? Color.WHITE : Color.rgb(179, 179, 179));
        view.setTextSize(heading ? 18 : 14);
        view.setPadding(0, dp(8), 0, dp(8));
        if (heading) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
