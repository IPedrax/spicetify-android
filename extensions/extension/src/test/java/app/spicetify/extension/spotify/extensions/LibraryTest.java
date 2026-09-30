package app.spicetify.extension.spotify.extensions;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class LibraryTest {
    private static final String ALL = "sp://esperanto/spotify.your_library_esperanto.proto.YourLibraryService/All";
    private static final int ALBUM = 2;
    private static final int ARTIST = 3;
    private static final int PLAYLIST = 4;
    private static final int FOLDER = 6;

    private final Context context = RuntimeEnvironment.getApplication();
    private final RandomSongTest.FakeRouter router = new RandomSongTest.FakeRouter();

    @Before
    public void setUp() {
        // The bridge is process-wide, so each test points it at its own router.
        Extensions.setAppContext(context);
        PlayerBridge.attach(router);
    }

    @Test
    public void givesLikedSongsThenEachSavedPlaylistAndAlbumOnceWithItsNameAndCover() throws Exception {
        Answer answer = new Answer();
        Library.fetch(context, answer);

        RandomSongTest.FakeRequest request = router.next();
        assertEquals(ALL, request.uri);
        assertArrayEquals("the request Play a random song sends", Esperanto.yourLibraryAll(), request.body);
        assertEquals("sent from the bridge thread", "Spicetify player bridge", request.thread);
        Wire.Writer response = new Wire.Writer();
        response.message(2, entity("spotify:playlist:road", "Road trip", "spotify:image:ab", PLAYLIST));
        response.message(2, entity("spotify:album:blue", "Blue", "spotify:image:cd", ALBUM));
        response.message(2, entity("spotify:playlist:mix", "Mix", "spotify:mosaic:1:2:3:4", PLAYLIST));
        response.message(2, entity("spotify:playlist:bare", "Bare", null, PLAYLIST));
        response.message(2, entity("spotify:user:u:folder:00000000000000ff", "Folder", null, FOLDER));
        response.message(2, entity("spotify:artist:x", "Artist", "spotify:image:ef", ARTIST));
        // Liked Songs in each of the four forms the app accepts.
        response.message(2, entity("spotify:collection:tracks", "Liked Songs", "spotify:image:1", PLAYLIST));
        response.message(2, entity("spotify:user:u:collection", "Liked Songs", "spotify:image:2", PLAYLIST));
        response.message(3, entity(Esperanto.LIKED_SONGS, "Liked Songs", "spotify:image:3", PLAYLIST));
        response.message(3, entity("spotify:internal:collection:tracks", "Liked Songs", "spotify:image:4", PLAYLIST));
        // A pinned copy: the first one wins.
        response.message(3, entity("spotify:playlist:road", "Old name", "spotify:image:old", PLAYLIST));
        response.varint(98, 200);
        request.callback.onResponse(200, response.toByteArray());
        answer.await();

        assertTrue("told on the main thread", answer.onMain);
        List<Library.Item> items = answer.items;
        assertEquals(Arrays.asList("spotify:collection:tracks", "spotify:playlist:road", "spotify:album:blue",
                "spotify:playlist:mix", "spotify:playlist:bare"), uris(items));
        assertEquals(Arrays.asList("Liked Songs", "Road trip", "Blue", "Mix", "Bare"), titles(items));
        // The covers as Your Library gives them; Liked Songs gets the one Spotify shows for it.
        assertEquals(Arrays.asList("https://misc.scdn.co/liked-songs/liked-songs-300.png", "spotify:image:ab",
                "spotify:image:cd", "spotify:mosaic:1:2:3:4", null), images(items));
        assertEquals(Arrays.asList(false, false, true, false, false), albums(items));
    }

    @Test
    public void likedSongsTakesSpotifysOwnNameForIt() throws Exception {
        Resources base = context.getResources();
        Resources spotify = new Resources(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration()) {
            @Override
            public int getIdentifier(String name, String type, String defPackage) {
                boolean title = "collection_liked_songs_title".equals(name) && "string".equals(type)
                        && context.getPackageName().equals(defPackage);
                return title ? 0x7f130853 : 0;
            }

            @Override
            public String getString(int id) {
                return id == 0x7f130853 ? "Lieblingssongs" : super.getString(id);
            }
        };
        Context german = new ContextWrapper(context) {
            @Override
            public Resources getResources() {
                return spotify;
            }
        };
        Answer answer = new Answer();

        Library.fetch(german, answer);
        router.next().callback.onResponse(200, EsperantoTest.yourLibrary(false));
        answer.await();

        assertEquals(Arrays.asList("spotify:collection:tracks"), uris(answer.items));
        assertEquals("Lieblingssongs", answer.items.get(0).title);
    }

    @Test
    public void withoutTheBridgeItSaysSoOnTheMainThread() throws Exception {
        PlayerBridgeTest.attachRouter(true); // Spotify destroyed its router, as on a logout
        Answer answer = new Answer();

        Library.fetch(context, answer);
        answer.await();

        assertEquals(PlayerBridge.NOT_CONNECTED, answer.reason);
        assertNull(answer.items);
        assertTrue(answer.onMain);
    }

    @Test
    public void aLibraryStillLoadingIsAskedAgainASecondLater() throws Exception {
        Answer answer = new Answer();

        Library.fetch(context, answer);
        RandomSongTest.FakeRequest first = router.next();
        long answered = System.nanoTime();
        first.callback.onResponse(200, EsperantoTest.yourLibrary(true));
        RandomSongTest.FakeRequest retry = router.next();
        assertTrue("the retry waited its second", TimeUnit.NANOSECONDS.toMillis(retry.at - answered) >= 1000);
        assertEquals(ALL, retry.uri);
        retry.callback.onResponse(200, EsperantoTest.yourLibrary(false,
                entity("spotify:playlist:road", "Road trip", null, PLAYLIST)));
        answer.await();

        assertEquals(Arrays.asList("spotify:collection:tracks", "spotify:playlist:road"), uris(answer.items));
        assertEquals(2, router.requests.size());
    }

    @Test
    public void afterThreeRetriesALibraryStillLoadingComesAsItIs() throws Exception {
        Answer answer = new Answer();

        Library.fetch(context, answer, 0);
        for (int i = 0; i < 3; i++) router.next().callback.onResponse(200, EsperantoTest.yourLibrary(true));
        router.next().callback.onResponse(200, EsperantoTest.yourLibrary(true,
                entity("spotify:playlist:road", "Road trip", null, PLAYLIST)));
        answer.await();

        assertEquals(Arrays.asList("spotify:collection:tracks", "spotify:playlist:road"), uris(answer.items));
        assertEquals("the first ask and 3 retries", 4, router.requests.size());
    }

    @Test
    public void aCallbackThatThrowsIsLoggedInsteadOfReachingSpotify() throws Exception {
        Library.fetch(context, new Library.Callback() {
            @Override
            public void loaded(List<Library.Item> items) {
                throw new IllegalStateException("the picker is gone");
            }

            @Override
            public void failed(String reason) {
                throw new IllegalStateException("the picker is gone");
            }
        });
        router.next().callback.onResponse(200, EsperantoTest.yourLibrary(false));

        long deadline = System.currentTimeMillis() + 5000;
        while (!logged("the picker is gone")) {
            shadowOf(Looper.getMainLooper()).idle(); // would throw here without the catch
            assertTrue("no log within 5 s", System.currentTimeMillis() < deadline);
            Thread.sleep(10);
        }
    }

    // ---- Helpers ----

    /**
     * Attaches a router that answers Your Library's request with {@code items}, each {uri, name,
     * image}, an album when its uri is one, and answers nothing else; for the picker's tests, since
     * the bridge is package-private.
     */
    public static void attachLibrary(String[]... items) {
        Wire.Writer[] entities = new Wire.Writer[items.length];
        for (int i = 0; i < items.length; i++) {
            entities[i] = entity(items[i][0], items[i][1], items[i][2],
                    items[i][0].startsWith("spotify:album:") ? ALBUM : PLAYLIST);
        }
        byte[] library = EsperantoTest.yourLibrary(false, entities);
        PlayerBridge.attach(new CosmosRouter() {
            @Override
            public Cancel resolve(String action, String uri, byte[] body, Callback callback) {
                if (ALL.equals(uri)) callback.onResponse(200, library);
                return () -> {};
            }

            @Override
            public boolean destroyed() {
                return false;
            }
        });
    }

    /**
     * Returns once the bridge thread has done its part of a {@link Library#fetch} made before: the ask,
     * then the answer's handling, which the ask posts and which posts the answer to the main looper; for
     * the picker's tests, since the bridge is package-private. The bridge runs tasks in the order they fall
     * due, ties in the order they were posted, so a probe posted now runs after the ask, and the probe it
     * posts runs after the answer's handling. Only a hung bridge thread reaches its minute.
     */
    public static void awaitFetch() throws Exception {
        FutureTask<Void> probe = new FutureTask<>(() -> null);
        PlayerBridge.post(() -> PlayerBridge.post(probe));
        try {
            probe.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("the bridge thread didn't finish the library fetch within 60 s");
        }
    }

    /** A {@code YourLibraryDecoratedEntity}: {@code entity_info{2 name, 3 uri, 6 image}}, then an empty member {@code kind}. */
    private static Wire.Writer entity(String uri, String name, String image, int kind) {
        Wire.Writer info = new Wire.Writer();
        info.string(2, name);
        info.string(3, uri);
        if (image != null) info.string(6, image);
        Wire.Writer entity = new Wire.Writer();
        entity.message(1, info);
        entity.message(kind, new Wire.Writer());
        return entity;
    }

    private static boolean logged(String message) {
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("Spicetify")) {
            if (item.throwable != null && message.equals(item.throwable.getMessage())) return true;
        }
        return false;
    }

    private static List<String> uris(List<Library.Item> items) {
        List<String> uris = new ArrayList<>();
        for (Library.Item item : items) uris.add(item.uri);
        return uris;
    }

    private static List<String> titles(List<Library.Item> items) {
        List<String> titles = new ArrayList<>();
        for (Library.Item item : items) titles.add(item.title);
        return titles;
    }

    private static List<String> images(List<Library.Item> items) {
        List<String> images = new ArrayList<>();
        for (Library.Item item : items) images.add(item.image);
        return images;
    }

    private static List<Boolean> albums(List<Library.Item> items) {
        List<Boolean> albums = new ArrayList<>();
        for (Library.Item item : items) albums.add(item.album);
        return albums;
    }

    /** Keeps what {@link Library#fetch} told it, and whether it heard it on the main thread. */
    private static final class Answer implements Library.Callback {
        volatile List<Library.Item> items;
        volatile String reason;
        volatile boolean onMain;

        @Override
        public void loaded(List<Library.Item> items) {
            onMain = Looper.myLooper() == Looper.getMainLooper();
            this.items = items;
        }

        @Override
        public void failed(String reason) {
            onMain = Looper.myLooper() == Looper.getMainLooper();
            this.reason = reason;
        }

        /** Runs the main looper, where the answer is posted, until it comes. */
        void await() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (true) {
                shadowOf(Looper.getMainLooper()).idle();
                if (items != null || reason != null) return;
                assertTrue("no answer within 5 s", System.currentTimeMillis() < deadline);
                Thread.sleep(10);
            }
        }
    }
}
