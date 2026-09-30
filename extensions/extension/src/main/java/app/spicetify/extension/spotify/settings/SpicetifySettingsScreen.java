package app.spicetify.extension.spotify.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import app.spicetify.extension.spotify.extensions.Extensions;
import app.spicetify.extension.spotify.extensions.Library;
import app.spicetify.extension.spotify.extensions.PlayerBridge;
import app.spicetify.extension.spotify.home.HomePins;
import app.spicetify.extension.spotify.theme.MarketplaceScreen;
import app.spicetify.extension.spotify.theme.ThemeRuntime;
import app.spicetify.extension.spotify.theme.ThemeSection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

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
    private static final String LIBRARY_UNAVAILABLE = "Your library isn't available yet";
    private static final int BACKGROUND = Color.rgb(18, 18, 18);

    private final Context context;
    private final Activity host;
    private final MarketplaceOpener marketplace;

    /** Opens the Marketplace, as {@link MarketplaceScreen#open} does; {@code onClosed} runs once it closes. */
    interface MarketplaceOpener {
        void open(Context context, Runnable onApplied, Runnable onClosed);
    }

    private SpicetifySettingsScreen(Activity activity, MarketplaceOpener marketplace) {
        // The deleted Activity set Theme.Material; Spotify's own theme restyles framework widgets.
        this.context = new ContextThemeWrapper(activity, android.R.style.Theme_Material);
        this.host = activity;
        this.marketplace = marketplace;
    }

    public static void open(Activity activity) {
        open(activity, MarketplaceScreen::open);
    }

    /** Spicetify settings whose Marketplace button and current theme open {@code marketplace}. */
    static void open(Activity activity, MarketplaceOpener marketplace) {
        // Showing a dialog on a finishing activity throws, which would crash Spotify again.
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new SpicetifySettingsScreen(activity, marketplace).show();
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
        LinearLayout extensions = new LinearLayout(context);
        extensions.setOrientation(LinearLayout.VERTICAL);
        if (themeInstalled) {
            Runnable onApplied = () -> {
                // Recreating the activity reloads its resources with the new overlay.
                dialog.dismiss();
                host.recreate();
            };
            // The Marketplace button first, then the current theme. Both open the Marketplace, and
            // closing it lists the extensions again, since one may have been switched there.
            content.addView(ThemeSection.create(context, ThemeRuntime.supported(context), onApplied,
                    () -> marketplace.open(context, onApplied, () -> {
                        if (InstalledPatches.extensions()) listExtensions(extensions);
                    })));
        }

        if (InstalledPatches.extensions()) {
            content.addView(text(context, "Extensions", true));
            listExtensions(extensions);
            content.addView(extensions);
        }

        if (sharingInstalled) {
            addSwitch(content, "Clean sharing links", PatchSettings.cleanSharingEnabled(),
                    PatchSettings::setCleanSharingEnabled);
            content.addView(text(context, "Remove tracking parameters from Spotify links you share. "
                    + "Timestamps and playback context are preserved. Changes apply immediately.", false));
        }

        if (InstalledPatches.homePins()) {
            content.addView(text(context, "Home shortcuts", true));
            content.addView(text(context, "Choose shortcuts to show first on Home, in the order you pick them. "
                    + "Restart Spotify after changing pins.", false));
            Button choose = new Button(context);
            choose.setText("Choose pinned shortcuts");
            choose.setOnClickListener(view -> guarded(this::chooseHomePins));
            content.addView(choose);
            addSwitch(content, "Show only my pins", HomePins.onlyPins(), HomePins::setOnlyPins);
            content.addView(text(context, "Hide Spotify's other shortcuts on Home. Restart Spotify to apply.", false));
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

    /**
     * Fills {@code list} with the player bridge's line while it has a problem, then a row for each
     * extension that is on: its title and latest status. A row opens the extension's Marketplace
     * dialog, and its switch there fills the list again.
     */
    private void listExtensions(LinearLayout list) {
        list.removeAllViews();
        String problem = PlayerBridge.problemLine();
        if (problem != null) list.addView(text(context, problem, false));
        List<String> on = Extensions.enabled(context);
        if (on.isEmpty()) {
            list.addView(text(context,
                    "No extensions are on. Turn them on in the Marketplace's Extensions tab.", false));
        }
        for (String id : on) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(context, Extensions.title(id), false);
            title.setTextColor(Color.WHITE);
            title.setTextSize(16);
            title.setPadding(0, dp(8), 0, 0);
            row.addView(title);
            TextView status = text(context, Extensions.latestStatus(id), false);
            status.setPadding(0, 0, 0, dp(8));
            row.addView(status);
            onTap(row, () -> MarketplaceScreen.openExtension(context, id, () -> listExtensions(list)));
            list.addView(row);
        }
    }

    /**
     * The Home shortcuts picker: a search field over one checkable list of the pins, Home's tiles and
     * the library, which HomePins orders. It opens with the pins and Home's tiles, and the library
     * joins them once the bridge reads it. Picks are kept by uri in the order they were ticked, after
     * the pins in theirs, and a ticked row shows its place. A search never loses or reorders one, and
     * Save pins them in that order.
     */
    private void chooseHomePins() {
        List<HomePins.Choice> choices = new ArrayList<>(HomePins.choices());
        Set<String> picked = new LinkedHashSet<>();
        for (HomePins.Choice choice : choices) if (choice.pinned) picked.add(choice.id);
        List<HomePins.Choice> shown = new ArrayList<>();
        EditText search = new EditText(context);
        search.setHint("Search");
        search.setSingleLine(true);
        TextView note = text(context, "Loading your library", false);
        ListView list = new ListView(context);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        ArrayAdapter<String> rows = new ArrayAdapter<>(context, android.R.layout.simple_list_item_multiple_choice);
        list.setAdapter(rows);
        Runnable show = () -> {
            // A name that two choices share shows each one's uri too, so they can be told apart.
            Map<String, Integer> named = new HashMap<>();
            for (HomePins.Choice choice : choices) named.merge(choice.label, 1, Integer::sum);
            List<String> order = new ArrayList<>(picked);
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            shown.clear();
            List<String> texts = new ArrayList<>();
            for (HomePins.Choice choice : choices) {
                if (!choice.label.toLowerCase(Locale.ROOT).contains(query)) continue;
                shown.add(choice);
                String text = named.get(choice.label) > 1 ? choice.label + "\n" + choice.id : choice.label;
                int place = order.indexOf(choice.id);
                texts.add(place < 0 ? text : (place + 1) + ". " + text);
            }
            rows.clear();
            rows.addAll(texts);
            list.clearChoices();
            for (int i = 0; i < shown.size(); i++) list.setItemChecked(i, picked.contains(shown.get(i).id));
        };
        show.run();
        list.setOnItemClickListener((parent, row, position, id) -> guarded(() -> {
            if (list.isItemChecked(position)) picked.add(shown.get(position).id);
            else picked.remove(shown.get(position).id);
            show.run(); // the places after it change
        }));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable text) {
                guarded(show);
            }
        });
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(8), dp(24), 0);
        layout.addView(search);
        layout.addView(note);
        layout.addView(list);

        AlertDialog picker = new AlertDialog.Builder(context).setTitle("Pinned Home shortcuts").setView(layout)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null).create();
        picker.setOnShowListener(ignored -> picker.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> guarded(() -> {
                    try {
                        HomePins.setPinned(new ArrayList<>(picked));
                    } catch (IllegalArgumentException changedSelection) {
                        Toast.makeText(context, changedSelection.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    picker.dismiss();
                    new AlertDialog.Builder(context).setMessage("Pins saved. Restart Spotify to refresh Home.")
                            .setPositiveButton("OK", null).show();
                })));
        picker.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        picker.show();
        Library.fetch(context, new Library.Callback() {
            @Override
            public void loaded(List<Library.Item> items) {
                choices.clear();
                choices.addAll(HomePins.choices(items));
                // A Home tile that Home dropped meanwhile can't be pinned, so its pick goes too.
                Set<String> listed = new HashSet<>();
                for (HomePins.Choice choice : choices) listed.add(choice.id);
                picked.retainAll(listed);
                note.setVisibility(View.GONE);
                show.run();
            }

            @Override
            public void failed(String reason) {
                note.setText(LIBRARY_UNAVAILABLE);
            }
        });
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

    /**
     * Makes {@code row} ripple when tapped and run {@code action}. A failure is logged instead of
     * reaching Spotify.
     */
    public static void onTap(View row, Runnable action) {
        TypedValue ripple = new TypedValue();
        row.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setOnClickListener(view -> guarded(action));
    }

    /** Adds a switch in this page's style to {@code content}. A change runs {@code change}, guarded. */
    private void addSwitch(LinearLayout content, String label, boolean checked, Consumer<Boolean> change) {
        Switch toggle = new Switch(context);
        toggle.setText(label);
        toggle.setTextSize(18);
        toggle.setTextColor(Color.WHITE);
        toggle.setMinHeight(dp(56));
        toggle.setSwitchPadding(dp(24));
        toggle.setThumbTintList(new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[0]},
                new int[] {Color.rgb(30, 215, 96), Color.LTGRAY}));
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((button, on) -> guarded(() -> change.accept(on)));
        content.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Runs a tap or a switch's change, logging a failure instead of letting it reach Spotify. */
    private static void guarded(Runnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            Log.w("Spicetify", "A Spicetify settings action failed", e);
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
