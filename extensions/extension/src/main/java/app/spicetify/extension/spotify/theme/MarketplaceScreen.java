package app.spicetify.extension.spotify.theme;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import app.spicetify.extension.spotify.extensions.Extensions;
import app.spicetify.extension.spotify.settings.InstalledPatches;
import app.spicetify.extension.spotify.settings.SpicetifySettingsScreen;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The Spicetify Marketplace: a full-screen browser reached from Spicetify settings, with a Themes tab
 * and an Extensions tab. The presets, Galaxy V2 and a card to paste a theme are pinned above the
 * community themes, and Play a random song above the community extensions, where those with an
 * Android version come first. A tapped preset applies, and the paste card opens
 * {@link ThemeSection#paste}. A tapped theme's color scheme and any background image download and go
 * to {@link ThemeSection#chooseScheme}. An extension with an Android version has a switch and a
 * dialog, which Spicetify settings opens too, and any other extension links to its GitHub page.
 * Where no theme can apply, it opens on the Extensions tab, and each theme card is greyed and says why.
 */
public final class MarketplaceScreen {
    private static final String BACKGROUND_COLOR = "#121212";
    private static final String PLACEHOLDER_COLOR = "#282828";
    /** Spotify's green, on its buttons and its selected filter chips. */
    static final int GREEN = 0xFF1ED760;
    /** The presets, pinned first as cards: kind, label, and what it looks like. */
    private static final String[][] PRESETS = {
            {ThemePresets.STOCK, "Spotify default", "Spotify's own colors."},
            {ThemePresets.AMOLED, "AMOLED black", "A black background, with menus and sheets slightly lighter."},
            {ThemePresets.MATERIAL_YOU, "Material You",
                    "Your wallpaper's palette. It updates when Spotify starts after a wallpaper change."},
            {ThemePresets.MATERIAL_YOU_BLACK, "Material You, black background",
                    "The wallpaper's accents on a black background."},
    };
    /** The author of the cards built into the Marketplace. */
    private static final String BUILT_IN_AUTHOR = "Spicetify for Android";
    /** The roles a strip of a theme's colors shows, and Spotify's own colors for them. */
    private static final String[] STRIP_ROLES = {"main", "card", "button", "text"};
    private static final int[] STOCK_STRIP = {0xFF121212, 0xFF282828, GREEN, 0xFFFFFFFF};
    /**
     * A theme's own image smaller than this on either side is a texture tile, like Spotify Dark's
     * 70x70 ones, not a background; the real ones start at Galaxy's 1200x675.
     */
    private static final int MIN_BACKGROUND_PX = 480;

    private static final ExecutorService BACKGROUND = pool("Spicetify Marketplace", 1);
    private static final ExecutorService DOWNLOADS = pool("Spicetify theme download", 1);
    private static final ExecutorService MANIFESTS = pool("Spicetify manifests", 4);
    private static final ExecutorService PREVIEW_POOL = pool("Spicetify previews", 2);
    private static PreviewImages previewImages;

    private final Context context;
    private final MarketplaceLoader loader;
    private final PreviewImages previews;
    private final Executor background;
    private final Executor downloads;
    private final Marketplace.Fetcher fetcher;
    private final PreviewImages.Downloader imageFetcher;
    /** Why no theme can apply here, as a theme card says it; null when themes apply. */
    private final String whyNoThemes;
    private final Runnable onApplied;
    private final int rowWidthPx;
    private final Adapter adapter = new Adapter();
    /**
     * Above the GitHub results of their tab, in order: the preset cards, Galaxy V2, the paste card and
     * Play a random song.
     */
    private final List<Marketplace.Theme> pinned = new ArrayList<>();
    /** Each preset card's {@link ThemePresets} kind. */
    private final Map<Marketplace.Theme, String> presets = new HashMap<>();
    /** The card that opens {@link ThemeSection#paste}. */
    private final Marketplace.Theme paste = builtIn("Paste a Spicetify theme",
            "Use the colors of a color.ini, or of CSS with --spice-* variables.", Marketplace.Kind.THEME, null);

    private final MarketplaceLoader.Listener listener = new MarketplaceLoader.Listener() {
        @Override
        public void onThemes(List<Marketplace.Theme> themes, boolean done) {
            ThemeSection.onMain(() -> {
                if (done || !keepList) allThemes = themes;
                if (done) loading = false;
                applyFilter();
            });
        }

        @Override
        public void onError(String message) {
            ThemeSection.onMain(() -> {
                errorMessage = message;
                loading = false;
                applyFilter();
            });
        }

        @Override
        public void onNotice(String message) {
            ThemeSection.onMain(() -> {
                notice = message;
                applyFilter();
            });
        }
    };

    private Dialog dialog;
    /** The Themes and Extensions pills. */
    private LinearLayout tabs;
    private EditText search;
    private ProgressBar progress;
    private TextView status;
    private Button retry;
    private ListView list;
    /** The tab on screen: the kind of item it lists. */
    private Marketplace.Kind tab;
    private List<Marketplace.Theme> allThemes = Collections.emptyList();
    private String errorMessage;
    /**
     * Why part of the list, such as the extensions, couldn't load; shown when the status has nothing
     * else to say, and on a tab with nothing found, where it may say why.
     */
    private String notice;
    /** From the start of a load until its last call. */
    private boolean loading;
    /** Set when a load starts with a list on screen: that list stays until the load is done. */
    private boolean keepList;
    /** The theme whose color scheme is downloading, or null. */
    private Marketplace.Theme downloading;

    private MarketplaceScreen(Context context, MarketplaceLoader loader, PreviewImages previews, Executor background,
            Executor downloads, Marketplace.Fetcher fetcher, PreviewImages.Downloader imageFetcher, String whyNoThemes,
            Runnable onApplied) {
        this.context = context;
        this.loader = loader;
        this.previews = previews;
        this.background = background;
        this.downloads = downloads;
        this.fetcher = fetcher;
        this.imageFetcher = imageFetcher;
        this.whyNoThemes = whyNoThemes;
        this.onApplied = onApplied;
        this.rowWidthPx = context.getResources().getDisplayMetrics().widthPixels;
        for (String[] preset : PRESETS) {
            Marketplace.Theme card = builtIn(preset[1], preset[2], Marketplace.Kind.THEME, null);
            pinned.add(card);
            presets.put(card, preset[0]);
        }
        pinned.add(Marketplace.GALAXY_V2);
        pinned.add(paste);
        pinned.add(builtIn(Extensions.title(Extensions.RANDOM_SONG), Extensions.description(Extensions.RANDOM_SONG),
                Marketplace.Kind.EXTENSION, Extensions.RANDOM_SONG));
        // Hidden for now: Extensions.isHidden keeps its card off this list until it's unhidden.
        if (!Extensions.isHidden(Extensions.UNAVAILABLE_SONGS)) {
            pinned.add(builtIn(Extensions.title(Extensions.UNAVAILABLE_SONGS),
                    Extensions.description(Extensions.UNAVAILABLE_SONGS), Marketplace.Kind.EXTENSION,
                    Extensions.UNAVAILABLE_SONGS));
        }
    }

    /** A card built into the Marketplace rather than read from GitHub: no preview, repository or stars. */
    private static Marketplace.Theme builtIn(String title, String description, Marketplace.Kind kind, String androidId) {
        return new Marketplace.Theme(title, description, BUILT_IN_AUTHOR, null, null, null, -1, -1, null, null,
                Collections.emptyList(), kind, null, androidId);
    }

    /**
     * Opens the Marketplace with production collaborators: real network access, real caching.
     * {@code onClosed} runs once it closes, so the caller can show what changed there.
     */
    public static void open(Context context, Runnable onApplied, Runnable onClosed) {
        File cache = new File(context.getCacheDir(), "spicetify_marketplace.json");
        MarketplaceLoader loader = new MarketplaceLoader(Marketplace.HTTP, MANIFESTS, cache, System::currentTimeMillis);
        show(context, loader, previewImages(), BACKGROUND, DOWNLOADS, Marketplace.HTTP, PreviewImages.HTTP,
                whyNoThemes(context), onApplied, onClosed);
    }

    /**
     * Why no theme can apply on this device, as a theme card says it, or null when themes apply. An old
     * Android comes before a missing patch, since adding the patch can't fix it. Extensions work on
     * any Android version, so the Marketplace opens either way.
     */
    static String whyNoThemes(Context context) {
        boolean android14OrLater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
        if (android14OrLater && !InstalledPatches.themeColors()) return "Needs the Theme colors patch";
        return ThemeRuntime.supported(context) ? null : "Needs Android 14 or later";
    }

    /** One {@link PreviewImages} for the process, so reopening the Marketplace reuses cached thumbnails. */
    private static synchronized PreviewImages previewImages() {
        if (previewImages == null) previewImages = new PreviewImages(PreviewImages.HTTP, PREVIEW_POOL);
        return previewImages;
    }

    /** Named threads that end after a minute idle, so a closed Marketplace leaves none behind. */
    private static ExecutorService pool(String name, int threads) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 1, TimeUnit.MINUTES,
                new LinkedBlockingQueue<>(), task -> new Thread(task, name));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** Loads run on {@code background} and theme downloads on {@code downloads}, so a tap never waits for a load. */
    static void show(Context context, MarketplaceLoader loader, PreviewImages previews, Executor background,
            Executor downloads, Marketplace.Fetcher fetcher, PreviewImages.Downloader imageFetcher, String whyNoThemes,
            Runnable onApplied, Runnable onClosed) {
        new MarketplaceScreen(context, loader, previews, background, downloads, fetcher, imageFetcher, whyNoThemes,
                onApplied).build(onClosed);
    }

    private void build(Runnable onClosed) {
        dialog = new Dialog(context, android.R.style.Theme_Material_NoActionBar);
        dialog.setTitle("Marketplace");

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(BACKGROUND_COLOR));
        root.setFitsSystemWindows(true);
        root.addView(header());
        root.addView(tabRow());
        root.addView(searchField());
        root.addView(statusRow());
        showTab(whyNoThemes == null ? Marketplace.Kind.THEME : Marketplace.Kind.EXTENSION);

        list = new ListView(context);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> guarded(() -> openItem(adapter.getItem(position))));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(root);
        // Previews still queued are for rows nobody will see, and the caller shows what changed here.
        dialog.setOnDismissListener(ignored -> {
            guarded(previews::cancelQueued);
            guarded(onClosed);
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();

        load(false);
    }

    private LinearLayout header() {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton close = new ImageButton(context);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        close.setBackgroundResource(ripple.resourceId);
        close.setContentDescription("Close Marketplace");
        close.setOnClickListener(view -> guarded(dialog::dismiss));
        header.addView(close, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView title = SpicetifySettingsScreen.text(context, "Marketplace", true);
        title.setTextSize(20);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button refresh = new Button(context);
        refresh.setText("Refresh");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(view -> guarded(() -> load(true)));
        header.addView(refresh);

        return header;
    }

    /** A pill for each tab, in the style of Spotify's filter chips. */
    private LinearLayout tabRow() {
        tabs = new LinearLayout(context);
        tabs.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.setMarginEnd(dp(8));
        tabs.addView(pill("Themes", Marketplace.Kind.THEME), gap);
        tabs.addView(pill("Extensions", Marketplace.Kind.EXTENSION));
        return tabs;
    }

    private TextView pill(String label, Marketplace.Kind kind) {
        TextView pill = new TextView(context);
        pill.setText(label);
        pill.setTag(kind);
        pill.setTextSize(14);
        pill.setPadding(dp(16), dp(6), dp(16), dp(6));
        pill.setOnClickListener(view -> guarded(() -> {
            showTab(kind);
            applyFilter();
            // A tab starts at its top. After the new items, or the list would keep its old position.
            list.setSelection(0);
        }));
        return pill;
    }

    /**
     * Makes {@code kind}'s tab the one on screen: its pill filled, the other outlined, and the search
     * hint to match. The search text stays.
     */
    private void showTab(Marketplace.Kind kind) {
        tab = kind;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView pill = (TextView) tabs.getChildAt(i);
            boolean selected = pill.getTag() == kind;
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(100)); // cut to half the height when drawn, which rounds the ends
            if (selected) {
                shape.setColor(GREEN);
            } else {
                shape.setStroke(dp(1), Color.rgb(179, 179, 179));
            }
            pill.setBackground(shape);
            pill.setTextColor(selected ? Color.parseColor(BACKGROUND_COLOR) : Color.WHITE);
            pill.setSelected(selected);
        }
        search.setHint(kind == Marketplace.Kind.THEME ? "Search themes" : "Search extensions");
    }

    private EditText searchField() {
        search = new EditText(context);
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                guarded(MarketplaceScreen.this::applyFilter);
            }
        });
        return search;
    }

    private LinearLayout statusRow() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        progress = new ProgressBar(context);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams spinner = new LinearLayout.LayoutParams(dp(24), dp(24));
        spinner.setMarginEnd(dp(12));
        row.addView(progress, spinner);
        status = SpicetifySettingsScreen.text(context, "", false);
        row.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        retry = new Button(context);
        retry.setText("Retry");
        retry.setAllCaps(false);
        retry.setOnClickListener(view -> guarded(() -> load(false)));
        row.addView(retry);
        return row;
    }

    /** Starts a load, unless one is running: Retry and Refresh wait for it. */
    private void load(boolean refresh) {
        if (loading) return;
        loading = true;
        errorMessage = null;
        notice = null;
        keepList = !allThemes.isEmpty();
        applyFilter();
        background.execute(() -> {
            try {
                loader.load(refresh, listener);
            } catch (Throwable e) {
                // Anything that escapes a pool thread ends Spotify's process.
                Log.w("Spicetify", "Marketplace load failed", e);
            }
        });
    }

    /**
     * Re-derives the list from the tab and the search text, and updates the status row. Each tab is
     * loading until the loader's last call: the extensions come after the themes, and a theme can
     * still come with them.
     */
    private void applyFilter() {
        List<Marketplace.Theme> found = new ArrayList<>();
        for (Marketplace.Theme item : allThemes) {
            if (item.kind == tab) found.add(item);
        }
        // Extensions with an Android version first. The sort is stable, so the star order holds within each group.
        Collections.sort(found, (a, b) -> Boolean.compare(a.androidId == null, b.androidId == null));
        List<Marketplace.Theme> items = new ArrayList<>();
        for (Marketplace.Theme item : pinned) {
            if (item.kind == tab) items.add(item);
        }
        items.addAll(found);
        List<Marketplace.Theme> filtered = Marketplace.filter(items, search.getText().toString());
        adapter.setThemes(filtered);
        String kinds = tab == Marketplace.Kind.THEME ? "themes" : "extensions";
        String message = notice;
        if (loading) {
            message = "Loading " + kinds;
        } else if (errorMessage != null) {
            message = errorMessage;
        } else if (found.isEmpty()) {
            message = notice != null ? notice : "No " + kinds + " found";
        } else if (filtered.isEmpty()) {
            message = "No " + kinds + " match";
        }
        status.setText(message);
        status.setVisibility(message == null ? View.GONE : View.VISIBLE);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        retry.setVisibility(!loading && (errorMessage != null || found.isEmpty()) ? View.VISIBLE : View.GONE);
    }

    /**
     * A preset applies, and the paste card asks for a theme to paste. A theme downloads. An extension
     * with an Android version opens its dialog, and any other extension opens its GitHub page. Where no
     * theme can apply, a theme card only says why.
     */
    private void openItem(Marketplace.Theme item) {
        String preset = presets.get(item);
        if (item.kind == Marketplace.Kind.THEME && whyNoThemes != null) {
            Toast.makeText(context, whyNoThemes, Toast.LENGTH_SHORT).show();
        } else if (preset != null) {
            ThemeSection.apply(context, ThemeState.Selection.preset(preset, item.title), null, this::applied);
        } else if (item == paste) {
            ThemeSection.paste(context, this::applied);
        } else if (item.kind == Marketplace.Kind.THEME) {
            openTheme(item);
        } else if (item.androidId != null) {
            // The row catches up with a change made in the dialog.
            openExtension(context, item.androidId, adapter::notifyDataSetChanged);
        } else {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.repoUrl)));
        }
    }

    /** Once a theme is applied: closes the Marketplace, and lets the settings screen recreate Spotify's activity. */
    private void applied() {
        dialog.dismiss();
        onApplied.run();
    }

    /**
     * What an Android extension does, its switch, and the controls it registered, if any. Spicetify
     * settings opens it from the extension's row. {@code onSwitch} runs after the switch changes.
     */
    public static void openExtension(Context context, String id, Runnable onSwitch) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 24), dp(context, 8), dp(context, 24), 0);
        content.addView(SpicetifySettingsScreen.text(context, Extensions.description(id), false));
        if (!InstalledPatches.extensions()) {
            content.addView(SpicetifySettingsScreen.text(
                    context, "Add the Spicetify extensions patch in Morphe Manager to use this.", false));
        }
        Switch toggle = new Switch(context);
        toggle.setText(Extensions.title(id));
        bindSwitch(context, toggle, id, onSwitch);
        content.addView(toggle);
        Extensions.Controls controls = Extensions.controls(id);
        if (controls != null) content.addView(controls.create(context));
        new AlertDialog.Builder(context).setTitle(Extensions.title(id)).setView(content)
                .setPositiveButton("Done", null).show();
    }

    /**
     * Shows whether extension {@code id} is on and turns it on or off, then runs {@code onSwitch}.
     * Without the extensions patch, it's disabled.
     */
    private static void bindSwitch(Context context, Switch toggle, String id, Runnable onSwitch) {
        toggle.setOnCheckedChangeListener(null); // a reused row's switch still has its last extension's listener
        toggle.setChecked(Extensions.isOn(context, id));
        toggle.setEnabled(InstalledPatches.extensions());
        toggle.setOnCheckedChangeListener((button, on) -> guarded(() -> {
            Extensions.setOn(context, id, on);
            onSwitch.run();
        }));
    }

    /** Downloads the tapped theme's color scheme and hands it to the chooser, one theme at a time. */
    private void openTheme(Marketplace.Theme theme) {
        if (downloading == null) {
            downloading = theme;
            downloads.execute(() -> {
                Runnable result = download(theme);
                ThemeSection.onMain(() -> {
                    downloading = null;
                    if (dialog.isShowing()) result.run(); // Nothing to show once the Marketplace is closed.
                });
            });
        }
        Toast.makeText(context, "Loading " + downloading.title, Toast.LENGTH_SHORT).show();
    }

    /**
     * Downloads and reads a theme's color.ini on the download thread, then its background image if
     * it has one, and returns what to show for it on the main thread: the scheme chooser, or why
     * there's none. Never throws. A theme's own image that fails to load is left out, with a Toast.
     */
    private Runnable download(Marketplace.Theme theme) {
        try {
            List<SpicetifyTheme.Scheme> usable = new ArrayList<>();
            for (SpicetifyTheme.Scheme scheme : SpicetifyTheme.parseColorIni(fetcher.get(theme.schemesUrl))) {
                if (!scheme.colors.isEmpty()) usable.add(scheme);
            }
            if (usable.isEmpty()) return () -> ThemeSection.error(context, theme.title + " has no colors Spotify can use.");
            byte[] image;
            try {
                image = theme.backgroundUrl == null ? null : imageFetcher.get(theme.backgroundUrl);
            } catch (IOException e) {
                Log.w("Spicetify", "Marketplace background image download failed: " + theme.backgroundUrl, e);
                String message = "Couldn't download " + theme.title + "'s background image: " + ThemeSection.describe(e);
                return () -> ThemeSection.error(context, message);
            }
            String problem = null;
            if (theme.backgroundUrl == null) {
                try {
                    image = ownImage(theme);
                } catch (IOException | IllegalArgumentException e) {
                    Log.w("Spicetify", "Marketplace background image failed for " + theme.title, e);
                    problem = "Couldn't load " + theme.title + "'s background image: " + ThemeSection.describe(e);
                }
            }
            byte[] background = image;
            String warning = problem;
            return () -> {
                if (warning != null) Toast.makeText(context, warning, Toast.LENGTH_LONG).show();
                ThemeSection.chooseScheme(context, usable, "button", theme.title, background, this::applied);
            };
        } catch (FileNotFoundException e) {
            return () -> ThemeSection.error(context, theme.title + " has no color schemes to use on Android.");
        } catch (ThemeException e) {
            return () -> ThemeSection.error(context, theme.title + ": " + e.getMessage());
        } catch (Throwable e) {
            // Anything that escapes a pool thread ends Spotify's process.
            Log.w("Spicetify", "Marketplace theme download failed: " + theme.schemesUrl, e);
            String reason = ThemeSection.describe(e);
            return () -> ThemeSection.error(context, "Couldn't download " + theme.title + ": " + reason);
        }
    }

    /**
     * The image a theme shows on desktop: the first its include JS files name, in manifest order,
     * then its user.css; null when none does, or when it's a texture tile rather than a background.
     * A file that fails to download names none, but an image that fails to download or decode, or
     * isn't one Android can read, throws.
     */
    private byte[] ownImage(Marketplace.Theme theme) throws IOException {
        String url = null;
        for (int i = 0; url == null && i < theme.includeUrls.size(); i++) {
            String js = text(theme.includeUrls.get(i));
            if (js != null) url = ThemeImages.fromJs(js);
        }
        if (url == null && theme.usercssUrl != null) {
            String css = text(theme.usercssUrl);
            if (css != null) url = ThemeImages.fromCss(css, theme.usercssUrl);
        }
        if (url == null) return null;
        byte[] image = url.startsWith("data:")
                ? Base64.decode(url.substring(url.indexOf(',') + 1), Base64.DEFAULT) : imageFetcher.get(url);
        BitmapFactory.Options bounds = ThemeBackground.bounds(image);
        if (bounds.outWidth >= MIN_BACKGROUND_PX && bounds.outHeight >= MIN_BACKGROUND_PX) return image;
        Log.i("Spicetify", theme.title + "'s " + bounds.outWidth + "x" + bounds.outHeight
                + " image is a texture tile, not a background");
        return null;
    }

    /** A script or stylesheet to look for an image in, or null when it fails to download. */
    private String text(String url) {
        try {
            return fetcher.get(url);
        } catch (IOException e) {
            Log.w("Spicetify", "Couldn't read " + url + " to look for a background image", e);
            return null;
        }
    }

    /** Runs a UI action that must never crash Spotify. */
    private static void guarded(Runnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            Log.w("Spicetify", "Marketplace action failed", e);
        }
    }

    /**
     * Adds an empty strip of a theme's colors to {@code parent}, full width and 24 dp tall: a cell for
     * each of {@link #STRIP_ROLES}. {@link #paintStrip} colors it.
     */
    static LinearLayout addStrip(LinearLayout parent) {
        Context context = parent.getContext();
        LinearLayout strip = new LinearLayout(context);
        for (int i = 0; i < STRIP_ROLES.length; i++) {
            strip.addView(new View(context), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        parent.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 24)));
        return strip;
    }

    /**
     * Colors {@code strip} with {@code theme}'s roles. A role the theme leaves out keeps Spotify's own
     * color, and a see-through one shows whole, since a strip has no image behind it. Material You's
     * colors come with Android 12, so before it, its strip is hidden.
     */
    static void paintStrip(LinearLayout strip, ThemeState.Selection theme) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && (ThemePresets.MATERIAL_YOU.equals(theme.kind)
                || ThemePresets.MATERIAL_YOU_BLACK.equals(theme.kind))) {
            strip.setVisibility(View.GONE);
            return;
        }
        Map<String, Integer> roles = ThemeRuntime.roleColors(strip.getContext(), theme);
        for (int i = 0; i < STRIP_ROLES.length; i++) {
            strip.getChildAt(i).setBackgroundColor(roles.getOrDefault(STRIP_ROLES[i], STOCK_STRIP[i]) | 0xFF000000);
        }
    }

    private int dp(int value) {
        return dp(context, value);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private final class Adapter extends BaseAdapter {
        private List<Marketplace.Theme> themes = Collections.emptyList();

        void setThemes(List<Marketplace.Theme> themes) {
            this.themes = themes;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return themes.size();
        }

        @Override
        public Marketplace.Theme getItem(int position) {
            return themes.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = convertView != null ? (Row) convertView.getTag() : new Row(context);
            Marketplace.Theme theme = themes.get(position);
            String subtitle = theme.author
                    + (theme.stars < 0 ? "" : ", " + theme.stars + (theme.stars == 1 ? " star" : " stars"))
                    + (theme.kind == Marketplace.Kind.EXTENSION && theme.androidId == null ? " · Desktop only" : "");
            row.title.setText(theme.title);
            row.subtitle.setText(subtitle);
            // An extension with an Android version says what it does on Android; others keep the manifest's.
            row.description.setText(theme.androidId != null ? Extensions.description(theme.androidId) : theme.description);
            previews.load(theme.previewUrl, row.image, rowWidthPx);
            // A preset card shows a strip of its colors in place of the image.
            String preset = presets.get(theme);
            row.image.setVisibility(preset == null ? View.VISIBLE : View.GONE);
            row.strip.setVisibility(preset == null ? View.GONE : View.VISIBLE);
            if (preset != null) paintStrip(row.strip, ThemeState.Selection.preset(preset, theme.title));
            row.toggle.setVisibility(theme.androidId == null ? View.GONE : View.VISIBLE);
            if (theme.androidId != null) {
                bindSwitch(context, row.toggle, theme.androidId, adapter::notifyDataSetChanged);
            }
            boolean off = theme.kind == Marketplace.Kind.THEME && whyNoThemes != null;
            row.view.setAlpha(off ? 0.5f : 1f);
            row.whyOff.setText(whyNoThemes);
            row.whyOff.setVisibility(off ? View.VISIBLE : View.GONE);
            return row.view;
        }
    }

    /** One list row's views, cached on the row so {@code convertView} can be reused. */
    private final class Row {
        final View view;
        final ImageView image;
        /** A preset card's colors, shown in place of the image. */
        final LinearLayout strip;
        final TextView title;
        final Switch toggle;
        final TextView subtitle;
        final TextView description;
        /** Why a theme card is greyed: no theme can apply here. */
        final TextView whyOff;

        Row(Context context) {
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            int horizontal = dp(16);
            int vertical = dp(12);
            root.setPadding(horizontal, vertical, horizontal, vertical);

            image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(Color.parseColor(PLACEHOLDER_COLOR));
            root.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));

            strip = addStrip(root);

            LinearLayout heading = new LinearLayout(context);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            title = new TextView(context);
            title.setTextColor(Color.WHITE);
            title.setTextSize(16);
            title.setTypeface(null, Typeface.BOLD);
            heading.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            toggle = new Switch(context);
            toggle.setFocusable(false); // a focusable view in a row keeps the list from taking the row's taps
            heading.addView(toggle);
            root.addView(heading);

            subtitle = new TextView(context);
            subtitle.setTextColor(Color.rgb(179, 179, 179));
            subtitle.setTextSize(13);
            root.addView(subtitle);

            description = new TextView(context);
            description.setTextColor(Color.rgb(179, 179, 179));
            description.setTextSize(13);
            description.setMaxLines(2);
            description.setEllipsize(TextUtils.TruncateAt.END);
            root.addView(description);

            whyOff = new TextView(context);
            whyOff.setTextColor(Color.WHITE);
            whyOff.setTextSize(13);
            root.addView(whyOff);

            root.setTag(this);
            view = root;
        }
    }
}
