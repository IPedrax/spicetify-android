package app.spicetify.extension.spotify.extensions;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The saved playlists and albums in Your Library, with their names and covers, for the Home
 * shortcuts picker in Spicetify settings. They come from the Your Library request that Play a random
 * song sends (Your Library trace, section 9), through the player bridge. Liked Songs is added by
 * hand, as the Home shortcut tiles report's section 6 gives it.
 */
public final class Library {
    /** Liked Songs as a tile's uri: the form Spotify's navigator routes, of the four the app accepts. */
    static final String LIKED_SONGS = "spotify:collection:tracks";
    /** The cover Spotify itself shows for Liked Songs. */
    static final String LIKED_SONGS_IMAGE = "https://misc.scdn.co/liked-songs/liked-songs-300.png";

    private Library() {}

    /** A saved playlist or album, or Liked Songs. */
    public static final class Item {
        public final String uri;
        /** Its name in Your Library, or null without one. */
        public final String title;
        /** Its cover as Your Library gives it, such as {@code spotify:image:<id>}, or null without one. */
        public final String image;
        public final boolean album;

        public Item(String uri, String title, String image, boolean album) {
            this.uri = uri;
            this.title = title;
            this.image = image;
            this.album = album;
        }
    }

    /** Hears how a {@link #fetch} ended, on the main thread. */
    public interface Callback {
        /** Liked Songs, then each saved playlist and album once, in Your Library's order. */
        void loaded(List<Item> items);

        /** Why there's no library, such as "bridge not connected" before Spotify's core is up. */
        void failed(String reason);
    }

    /**
     * Reads the library on the bridge thread, then tells {@code callback} on the main thread. It
     * returns at once and never throws, so a tap can call it. A library the core is still loading
     * fails, since it may be missing playlists.
     */
    public static void fetch(Context context, Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        try {
            String likedSongs = likedSongsTitle(context);
            PlayerBridge.post(() -> PlayerBridge.call(Esperanto.YOUR_LIBRARY, "All", Esperanto.yourLibraryAll(),
                    RandomSong.step(body -> {
                        Esperanto.Library library = Esperanto.parseYourLibrary(body);
                        if (library.loading) throw new IOException("your library is still loading");
                        List<Item> items = items(library, likedSongs);
                        tell(main, () -> callback.loaded(items));
                    }, (reason, e) -> {
                        Log.w("Spicetify", "Couldn't read the library: " + reason, e);
                        tell(main, () -> callback.failed(reason));
                    })));
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't ask for the library", e);
            tell(main, () -> callback.failed(String.valueOf(e)));
        }
    }

    /**
     * Liked Songs first, then the rest in the order the parse kept them. The parse has already
     * dropped the four Liked Songs forms, and holds Liked Songs' list uri in their place.
     */
    private static List<Item> items(Esperanto.Library library, String likedSongs) {
        List<Item> items = new ArrayList<>();
        items.add(new Item(LIKED_SONGS, likedSongs, LIKED_SONGS_IMAGE, false));
        for (Esperanto.LibrarySource source : library.sources) {
            if (!Esperanto.LIKED_SONGS.equals(source.uri)) {
                items.add(new Item(source.uri, source.name, source.image, source.album));
            }
        }
        return Collections.unmodifiableList(items);
    }

    /** Spotify's own name for Liked Songs, in the app's language, or the English one if it has none. */
    @SuppressLint("DiscouragedApi") // Spotify's string, whose id this extension can't know when it's built
    private static String likedSongsTitle(Context context) {
        Resources resources = context.getResources();
        int id = resources.getIdentifier("collection_liked_songs_title", "string", context.getPackageName());
        return id == 0 ? "Liked Songs" : resources.getString(id);
    }

    /** Runs {@code answer} on the main thread, where a throw is logged instead of reaching Spotify's looper. */
    private static void tell(Handler main, Runnable answer) {
        main.post(() -> {
            try {
                answer.run();
            } catch (Throwable e) {
                Log.w("Spicetify", "The library's callback failed", e);
            }
        });
    }
}
