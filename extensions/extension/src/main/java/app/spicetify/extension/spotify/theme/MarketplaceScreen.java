package app.spicetify.extension.spotify.theme;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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
import android.widget.TextView;
import android.widget.Toast;
import app.spicetify.extension.spotify.settings.SpicetifySettingsScreen;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The Spicetify Marketplace: a full-screen theme browser reached from the Theme section. Lists
 * community themes, downloads a tapped theme's color scheme and any background image, and hands
 * them to {@link ThemeSection#chooseScheme}.
 */
final class MarketplaceScreen {
    private static final String BACKGROUND_COLOR = "#121212";
    private static final String PLACEHOLDER_COLOR = "#282828";

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
    private final Runnable onApplied;
    private final int rowWidthPx;
    private final Adapter adapter = new Adapter();

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
    };

    private Dialog dialog;
    private EditText search;
    private ProgressBar progress;
    private TextView status;
    private Button retry;
    private List<Marketplace.Theme> allThemes = Collections.emptyList();
    private String errorMessage;
    /** From the start of a load until its last call. */
    private boolean loading;
    /** Set when a load starts with a list on screen: that list stays until the load is done. */
    private boolean keepList;
    /** The theme whose color scheme is downloading, or null. */
    private Marketplace.Theme downloading;

    private MarketplaceScreen(Context context, MarketplaceLoader loader, PreviewImages previews, Executor background,
            Executor downloads, Marketplace.Fetcher fetcher, PreviewImages.Downloader imageFetcher, Runnable onApplied) {
        this.context = context;
        this.loader = loader;
        this.previews = previews;
        this.background = background;
        this.downloads = downloads;
        this.fetcher = fetcher;
        this.imageFetcher = imageFetcher;
        this.onApplied = onApplied;
        this.rowWidthPx = context.getResources().getDisplayMetrics().widthPixels;
    }

    /** Opens the Marketplace with production collaborators: real network access, real caching. */
    static void open(Context context, Runnable onApplied) {
        File cache = new File(context.getCacheDir(), "spicetify_marketplace.json");
        MarketplaceLoader loader = new MarketplaceLoader(Marketplace.HTTP, MANIFESTS, cache, System::currentTimeMillis);
        show(context, loader, previewImages(), BACKGROUND, DOWNLOADS, Marketplace.HTTP, PreviewImages.HTTP, onApplied);
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
            Executor downloads, Marketplace.Fetcher fetcher, PreviewImages.Downloader imageFetcher, Runnable onApplied) {
        new MarketplaceScreen(context, loader, previews, background, downloads, fetcher, imageFetcher, onApplied).build();
    }

    private void build() {
        dialog = new Dialog(context, android.R.style.Theme_Material_NoActionBar);
        dialog.setTitle("Marketplace");

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(BACKGROUND_COLOR));
        root.setFitsSystemWindows(true);
        root.addView(header());
        root.addView(searchField());
        root.addView(statusRow());

        ListView list = new ListView(context);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> guarded(() -> openTheme(adapter.getItem(position))));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(root);
        // Previews still queued are for rows nobody will see.
        dialog.setOnDismissListener(ignored -> guarded(previews::cancelQueued));
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

    private EditText searchField() {
        search = new EditText(context);
        search.setHint("Search themes");
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

    /** Re-derives the filtered list from the current search text and updates the status row. */
    private void applyFilter() {
        List<Marketplace.Theme> themes = new ArrayList<>();
        themes.add(Marketplace.GALAXY_V2); // pinned above the GitHub results
        themes.addAll(allThemes);
        List<Marketplace.Theme> filtered = Marketplace.filter(themes, search.getText().toString());
        adapter.setThemes(filtered);
        String message = null;
        if (loading) {
            message = "Loading themes";
        } else if (errorMessage != null) {
            message = errorMessage;
        } else if (allThemes.isEmpty()) {
            message = "No themes found";
        } else if (filtered.isEmpty()) {
            message = "No themes match";
        }
        status.setText(message);
        status.setVisibility(message == null ? View.GONE : View.VISIBLE);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        retry.setVisibility(!loading && (errorMessage != null || allThemes.isEmpty()) ? View.VISIBLE : View.GONE);
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
     * there's none. Never throws.
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
            return () -> ThemeSection.chooseScheme(context, usable, "button", theme.title, image, () -> {
                dialog.dismiss();
                onApplied.run();
            });
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

    /** Runs a UI action that must never crash Spotify. */
    private void guarded(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            Log.w("Spicetify", "Marketplace action failed", e);
        }
    }

    private int dp(int value) {
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
            String subtitle = theme.stars < 0 ? theme.author
                    : theme.author + ", " + theme.stars + (theme.stars == 1 ? " star" : " stars");
            row.title.setText(theme.title);
            row.subtitle.setText(subtitle);
            row.description.setText(theme.description);
            previews.load(theme.previewUrl, row.image, rowWidthPx);
            return row.view;
        }
    }

    /** One list row's views, cached on the row so {@code convertView} can be reused. */
    private final class Row {
        final View view;
        final ImageView image;
        final TextView title;
        final TextView subtitle;
        final TextView description;

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

            title = new TextView(context);
            title.setTextColor(Color.WHITE);
            title.setTextSize(16);
            title.setTypeface(null, Typeface.BOLD);
            root.addView(title);

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

            root.setTag(this);
            view = root;
        }
    }
}
