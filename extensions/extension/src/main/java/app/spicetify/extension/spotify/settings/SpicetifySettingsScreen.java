package app.spicetify.extension.spotify.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
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
import app.spicetify.extension.spotify.extensions.Extensions;
import app.spicetify.extension.spotify.home.HomePins;
import app.spicetify.extension.spotify.theme.ThemeRuntime;
import app.spicetify.extension.spotify.theme.ThemeSection;
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

    private final Context context;
    private final Activity host;

    private SpicetifySettingsScreen(Activity activity) {
        // The deleted Activity set Theme.Material; Spotify's own theme restyles framework widgets.
        this.context = new ContextThemeWrapper(activity, android.R.style.Theme_Material);
        this.host = activity;
    }

    public static void open(Activity activity) {
        // Showing a dialog on a finishing activity throws, which would crash Spotify again.
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new SpicetifySettingsScreen(activity).show();
    }

    private void show() {
        Dialog dialog = new Dialog(context, android.R.style.Theme_Material_NoActionBar);
        dialog.setTitle("Spicetify");
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setFitsSystemWindows(true);
        root.addView(header(dialog));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(content(dialog), new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        dialog.setContentView(root);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }

    private LinearLayout header(Dialog dialog) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton close = new ImageButton(context);
        close.setTag(CLOSE_TAG);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        close.setBackgroundResource(ripple.resourceId);
        close.setContentDescription("Close Spicetify settings");
        close.setOnClickListener(view -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = text(context, "Spicetify", true);
        title.setTextSize(20);
        header.addView(title);
        return header;
    }

    private LinearLayout content(Dialog dialog) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        content.setPadding(padding, dp(16), padding, padding);

        boolean sharingInstalled = InstalledPatches.cleanSharing();
        boolean themeInstalled = InstalledPatches.themeColors();
        if (sharingInstalled) {
            Switch cleanSharing = new Switch(context);
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
            content.addView(text(context, "Remove tracking parameters from Spotify links you share. "
                    + "Timestamps and playback context are preserved. Changes apply immediately.", false));
        }

        if (themeInstalled) {
            content.addView(ThemeSection.create(context, ThemeRuntime.supported(context), () -> {
                // Recreating the activity reloads its resources with the new overlay.
                dialog.dismiss();
                host.recreate();
            }));
        }

        if (InstalledPatches.extensions()) {
            content.addView(text(context, "Extensions", true));
            for (String line : Extensions.statusLines()) content.addView(text(context, line, false));
            content.addView(text(context, "Turn extensions on in the Spicetify Marketplace.", false));
        }

        if (InstalledPatches.homePins()) {
            content.addView(text(context, "Home shortcuts", true));
            content.addView(text(context, "Choose which shortcuts appear first when Spotify includes them on Home. "
                    + "Return to Home once to load the choices. Restart Spotify after changing pins.", false));
            Button choose = new Button(context);
            choose.setText("Choose pinned shortcuts");
            choose.setOnClickListener(view -> chooseHomePins());
            content.addView(choose);
        }

        if (InstalledPatches.serverFiles()) {
            content.addView(new ServerFilesSettings(context));
        }

        if (!sharingInstalled && !themeInstalled && !InstalledPatches.homePins()
                && !InstalledPatches.serverFiles() && !InstalledPatches.extensions()) {
            content.addView(text(context, "No configurable Spicetify patches are installed.", false));
        }
        return content;
    }

    private void chooseHomePins() {
        List<HomePins.Choice> choices = HomePins.choices();
        if (choices.isEmpty()) {
            new AlertDialog.Builder(context).setTitle("No Home shortcuts loaded")
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
        AlertDialog picker = new AlertDialog.Builder(context).setTitle("Pinned Home shortcuts")
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
                        Toast.makeText(context, changedSelection.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    picker.dismiss();
                    new AlertDialog.Builder(context).setMessage("Pins saved. Restart Spotify to refresh Home.")
                            .setPositiveButton("OK", null).show();
                }));
        picker.show();
    }

    public static TextView text(Context context, String value, boolean heading) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(heading ? Color.WHITE : Color.rgb(179, 179, 179));
        view.setTextSize(heading ? 18 : 14);
        int padding = Math.round(8 * context.getResources().getDisplayMetrics().density);
        view.setPadding(0, padding, 0, padding);
        if (heading) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
