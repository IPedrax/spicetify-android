package app.spicetify.extension.spotify.extensions;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class RandomSongTest {
    private static final String TOKEN = "sp://auth/v2/token?renew=0";
    private static final String PLAY = "sp://esperanto/spotify.player.esperanto.proto.ContextPlayer/Play";
    private static final String ALL = "sp://esperanto/spotify.your_library_esperanto.proto.YourLibraryService/All";
    private static final String PLAYLIST_GET =
            "sp://esperanto/spotify.playlist_esperanto.proto.PlaylistDataService/Get";
    private static final String GET_ENTITY =
            "sp://esperanto/spotify.metadata_esperanto.proto.ClassicMetadataService/GetEntity";
    private static final String SEARCH_URL =
            "https://api\\.spotify\\.com/v1/search\\?q=[a-z0-9]&type=track&limit=1&offset=\\d{1,3}&market=from_token";
    private static final String BRIDGE_THREAD = "Spicetify player bridge";
    private static final String WEB_API_THREAD = "Spicetify Web API";

    private final Context context = RuntimeEnvironment.getApplication();
    private final FakeRouter router = new FakeRouter();

    @Before
    public void setUp() {
        Extensions.setAppContext(context);
        PlayerBridge.attach(router);
        // Spicetify settings lists an extension's status only while it's on.
        Extensions.setOn(context, Extensions.RANDOM_SONG, true);
        // The status map outlives a test, so no test may pass on the line the last one left.
        Extensions.status(null, Extensions.RANDOM_SONG, "not run");
    }

    @After
    public void tearDown() {
        Extensions.setOn(context, Extensions.RANDOM_SONG, false);
    }

    // ---- Picking ----

    @Test
    public void pickFindsTheSourceAndThePositionInsideIt() {
        long[] sizes = {3, 0, 5};

        assertArrayEquals(new int[] {0, 0}, RandomSong.pick(sizes, 0));
        assertArrayEquals(new int[] {0, 2}, RandomSong.pick(sizes, 2));
        assertArrayEquals(new int[] {2, 0}, RandomSong.pick(sizes, 3));
        assertArrayEquals(new int[] {2, 4}, RandomSong.pick(sizes, 7));
    }

    @Test
    public void randomQueriesAndOffsetsStayInRange() {
        Random random = new Random(42);
        Set<String> queries = new HashSet<>();
        int highest = 0;
        for (int i = 0; i < 1000; i++) {
            String query = RandomSong.randomQuery(random);
            assertEquals(query, 1, query.length());
            assertTrue(query, "abcdefghijklmnopqrstuvwxyz0123456789".contains(query));
            queries.add(query);
            int offset = RandomSong.randomOffset(random);
            assertTrue(String.valueOf(offset), offset >= 0 && offset < 1000);
            highest = Math.max(highest, offset);
        }
        assertEquals("every character comes up", 36, queries.size());
        assertTrue("offsets reach the top of the range: " + highest, highest >= 990);
    }

    // ---- From Spotify ----

    @Test
    public void fromSpotifySearchesWithTheTokenAndPlaysTheTrackAloneInItsContext() throws Exception {
        Search search = new Search("{\"tracks\":{\"items\":[{\"uri\":\"spotify:track:found\"}],\"total\":900}}");

        RandomSong.playFromSpotify(context, search);

        FakeRequest token = router.next();
        assertEquals("GET", token.action);
        assertEquals(TOKEN, token.uri);
        token.callback.onResponse(200,
                "{\"accessToken\":\"canned\",\"expiresIn\":3600,\"errorCode\":0}".getBytes(UTF_8));

        FakeRequest play = router.next();
        assertEquals("POST", play.action);
        assertEquals(PLAY, play.uri);
        assertArrayEquals(Esperanto.playContext("spotify:track:found", null), play.body);
        play.callback.onResponse(200, new byte[0]);

        awaitStatus("Playing spotify:track:found from Spotify");
        assertEquals("the token and one Play", 2, router.requests.size());
        assertEquals(1, search.urls.size());
        assertTrue(search.urls.get(0), search.urls.get(0).matches(SEARCH_URL));
        assertEquals(Collections.singletonList("canned"), search.tokens);
        assertEquals("the search runs on the Web API thread", WEB_API_THREAD, search.thread);
        assertSentFromTheBridgeThread();
    }

    @Test
    public void aSearchWaitingOnTheNetworkLeavesTheBridgeThreadFree() throws Exception {
        CountDownLatch searching = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        RandomSong.playFromSpotify(context, (url, token) -> {
            searching.countDown();
            PlayerBridgeTest.awaitQuietly(answer);
            return "{\"tracks\":{\"items\":[{\"uri\":\"spotify:track:late\"}],\"total\":1}}";
        });
        router.next().callback.onResponse(200, "{\"accessToken\":\"canned\"}".getBytes(UTF_8));
        assertTrue("no search within 5 s", searching.await(5, TimeUnit.SECONDS));

        try {
            assertEquals("a task posted while the search waits runs", "ran", onBridge(() -> "ran"));
        } finally {
            answer.countDown();
        }

        FakeRequest play = router.next();
        assertArrayEquals(Esperanto.playContext("spotify:track:late", null), play.body);
        play.callback.onResponse(200, new byte[0]);
        awaitStatus("Playing spotify:track:late from Spotify");
        assertSentFromTheBridgeThread();
    }

    @Test
    public void anEmptyPageIsSearchedOnceMoreBelowTheTotal() throws Exception {
        Search search = new Search(
                "{\"tracks\":{\"items\":[],\"total\":42}}",
                "{\"tracks\":{\"items\":[{\"uri\":\"spotify:track:second\"}],\"total\":42}}");

        RandomSong.playFromSpotify(context, search);
        router.next().callback.onResponse(200, "{\"accessToken\":\"canned\"}".getBytes(UTF_8));

        FakeRequest play = router.next();
        assertArrayEquals(Esperanto.playContext("spotify:track:second", null), play.body);
        play.callback.onResponse(200, new byte[0]);
        awaitStatus("Playing spotify:track:second from Spotify");
        assertEquals(2, search.urls.size());
        assertTrue(search.urls.get(1), search.urls.get(1).matches(SEARCH_URL));
        assertEquals("the same query", parameter(search.urls.get(0), "q"), parameter(search.urls.get(1), "q"));
        int offset = Integer.parseInt(parameter(search.urls.get(1), "offset"));
        assertTrue("below the total: " + offset, offset < 42);
    }

    @Test
    public void aTokenErrorEndsTheRunWithAToastSayingWhy() throws Exception {
        Search search = new Search();

        RandomSong.playFromSpotify(context, search);
        router.next().callback.onResponse(200, "{\"errorCode\":3,\"errorDescription\":\"logged out\"}".getBytes(UTF_8));

        awaitToast("Couldn't find a random song: sp://auth/v2/token responded with an error: 3, logged out");
        assertTrue("no search without a token", search.urls.isEmpty());
        assertEquals(1, router.requests.size());
    }

    @Test
    public void aFailedSearchShowsWhyAndPlaysNothing() throws Exception {
        RandomSong.playFromSpotify(context, (url, token) -> {
            throw new IOException("the Web API answered HTTP 429");
        });
        router.next().callback.onResponse(200, "{\"accessToken\":\"canned\"}".getBytes(UTF_8));

        awaitToast("Couldn't find a random song: the Web API answered HTTP 429");
        awaitStatus("Couldn't find a random song: the Web API answered HTTP 429");
        assertEquals("the token request, and no Play", 1, router.requests.size());
    }

    @Test
    public void aPlaySpotifyRefusesShowsWhy() throws Exception {
        RandomSong.playFromSpotify(context,
                new Search("{\"tracks\":{\"items\":[{\"uri\":\"spotify:track:refused\"}],\"total\":1}}"));
        router.next().callback.onResponse(200, "{\"accessToken\":\"canned\"}".getBytes(UTF_8));

        Wire.Writer forbidden = new Wire.Writer();
        forbidden.varint(1, 1);
        router.next().callback.onResponse(200, forbidden.toByteArray());

        awaitToast("Couldn't find a random song: Spotify refused to play it (error 1)");
    }

    // ---- From my library ----

    @Test
    public void fromLibraryPlaysAFetchedSongInsideTheChosenPlaylist() throws Exception {
        String playlist = "spotify:playlist:counted";

        RandomSong.playFromLibrary(context, 0);

        FakeRequest all = router.next();
        assertEquals("POST", all.action);
        assertEquals(ALL, all.uri);
        assertArrayEquals(Esperanto.yourLibraryAll(), all.body);
        all.callback.onResponse(200, EsperantoTest.yourLibrary(false,
                EsperantoTest.libraryEntity(playlist, 4, EsperantoTest.countedPlaylist(3))));

        FakeRequest liked = router.next();
        assertEquals(PLAYLIST_GET, liked.uri);
        assertArrayEquals(Esperanto.playlistGet(Esperanto.LIKED_SONGS, 0, 0, true), liked.body);
        liked.callback.onResponse(200, playlistPage(0));

        // Only the playlist has songs. It was weighed by its count, which includes unplayable
        // songs, so its playable length comes first.
        FakeRequest length = router.next();
        assertArrayEquals(Esperanto.playlistGet(playlist, 0, 0, true), length.body);
        length.callback.onResponse(200, playlistPage(2));

        FakeRequest fetch = router.next();
        int position = rangeStart(fetch.body);
        assertTrue("below the playable length: " + position, position >= 0 && position < 2);
        assertArrayEquals(Esperanto.playlistGet(playlist, position, 1, false), fetch.body);
        fetch.callback.onResponse(200, playlistPage(2, "spotify:track:fetched"));

        FakeRequest play = router.next();
        assertEquals(PLAY, play.uri);
        assertArrayEquals(Esperanto.playContext(playlist, "spotify:track:fetched"), play.body);
        play.callback.onResponse(200, new byte[0]);

        awaitStatus("Playing spotify:track:fetched from your library (1 playlists, 0 albums and Liked Songs)");
        assertEquals("one Play, and nothing after it", 5, router.requests.size());
        assertSentFromTheBridgeThread();
    }

    @Test
    public void likedSongsPlaysTheSongAtThePickedPosition() throws Exception {
        RandomSong.playFromLibrary(context, 0);

        // The Library's own Liked Songs row is dropped, so Liked Songs counts once.
        router.next().callback.onResponse(200, EsperantoTest.yourLibrary(false,
                EsperantoTest.libraryEntity("spotify:collection:tracks", 4, EsperantoTest.countedPlaylist(7))));
        FakeRequest liked = router.next();
        assertArrayEquals(Esperanto.playlistGet(Esperanto.LIKED_SONGS, 0, 0, true), liked.body);
        liked.callback.onResponse(200, playlistPage(3));

        // Liked Songs was sized by its playable length, so the pick's position is fetched as is.
        FakeRequest fetch = router.next();
        int position = rangeStart(fetch.body);
        assertTrue("below the length: " + position, position >= 0 && position < 3);
        assertArrayEquals(Esperanto.playlistGet(Esperanto.LIKED_SONGS, position, 1, false), fetch.body);
        fetch.callback.onResponse(200, playlistPage(3, "spotify:track:liked"));

        FakeRequest play = router.next();
        assertArrayEquals(Esperanto.playContext(Esperanto.LIKED_SONGS, "spotify:track:liked"), play.body);
        play.callback.onResponse(200, new byte[0]);

        awaitStatus("Playing spotify:track:liked from your library (0 playlists, 0 albums and Liked Songs)");
        assertEquals(4, router.requests.size());
    }

    @Test
    public void anAlbumsSongComesFromTheTrackListThatSizedIt() throws Exception {
        String album = "spotify:album:saved";
        byte[] one = gid(1);
        byte[] two = gid(2);

        RandomSong.playFromLibrary(context, 0);
        router.next().callback.onResponse(200, EsperantoTest.yourLibrary(false,
                EsperantoTest.libraryEntity(album, 2, new Wire.Writer()),
                EsperantoTest.libraryEntity("spotify:artist:dropped", 3, new Wire.Writer())));
        router.next().callback.onResponse(200, playlistPage(0)); // Liked Songs, empty

        FakeRequest entity = router.next();
        assertEquals(GET_ENTITY, entity.uri);
        assertArrayEquals(Esperanto.getEntity(album), entity.body);
        entity.callback.onResponse(200, albumTracks(one, two));

        // No further request: the list that sized the album holds its songs.
        FakeRequest play = router.next();
        assertEquals(PLAY, play.uri);
        String first = "spotify:track:" + Esperanto.base62(one);
        String played = Arrays.equals(Esperanto.playContext(album, first), play.body)
                ? first : "spotify:track:" + Esperanto.base62(two);
        assertArrayEquals(Esperanto.playContext(album, played), play.body);
        play.callback.onResponse(200, new byte[0]);

        awaitStatus("Playing " + played + " from your library (0 playlists, 1 albums and Liked Songs)");
        assertEquals(4, router.requests.size());
    }

    @Test
    public void anAlbumThatCantBeReadIsLeftOutAndTheSongComesFromLikedSongs() throws Exception {
        RandomSong.playFromLibrary(context, 0);
        router.next().callback.onResponse(200, EsperantoTest.yourLibrary(false,
                EsperantoTest.libraryEntity("spotify:album:gone", 2, new Wire.Writer())));
        router.next().callback.onResponse(200, playlistPage(3)); // Liked Songs
        FakeRequest entity = router.next();
        assertEquals(GET_ENTITY, entity.uri);
        entity.callback.onResponse(404, new byte[0]);

        // Only Liked Songs is left to pick from.
        FakeRequest fetch = router.next();
        int position = rangeStart(fetch.body);
        assertTrue("below the length: " + position, position >= 0 && position < 3);
        assertArrayEquals(Esperanto.playlistGet(Esperanto.LIKED_SONGS, position, 1, false), fetch.body);
        fetch.callback.onResponse(200, playlistPage(3, "spotify:track:liked"));
        FakeRequest play = router.next();
        assertArrayEquals(Esperanto.playContext(Esperanto.LIKED_SONGS, "spotify:track:liked"), play.body);
        play.callback.onResponse(200, new byte[0]);

        awaitStatus("Playing spotify:track:liked from your library"
                + " (0 playlists, 1 albums and Liked Songs; 1 couldn't be read)");
    }

    @Test
    public void aLibraryWhoseSongsCantBeReadFailsWithTheFirstReason() throws Exception {
        RandomSong.playFromLibrary(context, 0);
        router.next().callback.onResponse(200, EsperantoTest.yourLibrary(false,
                EsperantoTest.libraryEntity("spotify:album:missing", 2, new Wire.Writer()),
                EsperantoTest.libraryEntity("spotify:album:garbled", 2, new Wire.Writer())));
        router.next().callback.onResponse(200, playlistPage(0)); // Liked Songs, empty
        FakeRequest missing = router.next();
        assertArrayEquals(Esperanto.getEntity("spotify:album:missing"), missing.body);
        missing.callback.onResponse(404, new byte[0]);
        FakeRequest garbled = router.next();
        assertArrayEquals("sizing goes on past a source it can't read",
                Esperanto.getEntity("spotify:album:garbled"), garbled.body);
        garbled.callback.onResponse(200, new byte[] {0x0a, 0x05, 'a'}); // declares 5 bytes, carries 1

        awaitToast("Couldn't find a random song: status 404");
        awaitStatus("Couldn't find a random song: status 404");
        assertEquals("nothing fetched or played", 4, router.requests.size());
    }

    @Test
    public void losingTheRouterWhileSizingStillEndsTheRun() throws Exception {
        RandomSong.playFromLibrary(context, 0);
        router.next().callback.onResponse(200, EsperantoTest.yourLibrary(false,
                EsperantoTest.libraryEntity("spotify:album:a", 2, new Wire.Writer())));
        router.next().callback.onResponse(200, playlistPage(3)); // Liked Songs
        assertEquals(GET_ENTITY, router.next().uri);
        FakeRouter replacement = new FakeRouter();

        PlayerBridge.attach(replacement); // fails the pending GetEntity with "bridge not connected"

        awaitToast("Couldn't find a random song: bridge not connected");
        awaitStatus("Couldn't find a random song: bridge not connected");
        assertTrue("nothing sent after the loss", replacement.requests.isEmpty());
    }

    @Test
    public void aLibraryStillLoadingIsAskedAgainAndAnEmptyOneSaysSo() throws Exception {
        RandomSong.playFromLibrary(context, 0);

        FakeRequest first = router.next();
        first.callback.onResponse(200, EsperantoTest.yourLibrary(true));
        FakeRequest second = router.next();
        assertEquals(ALL, second.uri);
        assertArrayEquals(first.body, second.body);
        second.callback.onResponse(200, EsperantoTest.yourLibrary(false));
        router.next().callback.onResponse(200, playlistPage(0)); // Liked Songs, empty

        awaitToast("Your library has no songs to pick from");
        awaitStatus("Your library has no songs to pick from");
        assertEquals(3, router.requests.size());
    }

    @Test
    public void aLibraryStillLoadingIsAskedAgainLaterWhileTheBridgeThreadRunsOtherWork() throws Exception {
        RandomSong.playFromLibrary(context, 2000);
        router.next().callback.onResponse(200, EsperantoTest.yourLibrary(true));

        onBridge(() -> null); // the loading answer's step has run, and put off its retry
        assertEquals("a task posted after the loading answer runs before the retry",
                1, (int) onBridge(router.requests::size));

        FakeRequest retry = router.next(); // 2 s later
        assertEquals(ALL, retry.uri);
        retry.callback.onResponse(200, EsperantoTest.yourLibrary(false));
        router.next().callback.onResponse(200, playlistPage(0)); // Liked Songs, empty
        awaitStatus("Your library has no songs to pick from");
    }

    @Test
    public void theLibraryGivesUpAfterThreeRetries() throws Exception {
        RandomSong.playFromLibrary(context, 0);

        for (int i = 0; i < 4; i++) router.next().callback.onResponse(200, EsperantoTest.yourLibrary(true));

        awaitStatus("Couldn't find a random song: your library is still loading");
        assertEquals("the first ask and 3 retries", 4, router.requests.size());
    }

    @Test
    public void aCountedPlaylistWithNoPlayableSongsLeavesThePickAndItIsDoneAgain() throws Exception {
        String playlist = "spotify:playlist:unplayable";

        RandomSong.playFromLibrary(context, 0);
        router.next().callback.onResponse(200, EsperantoTest.yourLibrary(false,
                EsperantoTest.libraryEntity(playlist, 4, EsperantoTest.countedPlaylist(5))));
        router.next().callback.onResponse(200, playlistPage(0)); // Liked Songs, empty
        FakeRequest length = router.next();
        assertArrayEquals(Esperanto.playlistGet(playlist, 0, 0, true), length.body);
        length.callback.onResponse(200, playlistPage(0));

        // Without it, nothing is left to pick from.
        awaitStatus("Your library has no songs to pick from");
        assertEquals("nothing fetched or played", 3, router.requests.size());
    }

    // ---- Registration ----

    @Test
    public void theMenuActionsAndTheTwoCardButtonsStartTheirRuns() throws Exception {
        LinearLayout controls = (LinearLayout) Extensions.controls(Extensions.RANDOM_SONG).create(context);
        assertEquals(2, controls.getChildCount());
        Button spotify = (Button) controls.getChildAt(0);
        Button library = (Button) controls.getChildAt(1);
        assertEquals("Random from Spotify", spotify.getText().toString());
        assertEquals("Random from my library", library.getText().toString());

        // Each run ends at its first request, which fails, so no real search goes out.
        Extensions.action("random_spotify").run(context);
        endWith(TOKEN, "menu spotify");
        Extensions.action("random_library").run(context);
        endWith(ALL, "menu library");
        spotify.performClick();
        endWith(TOKEN, "button spotify");
        library.performClick();
        endWith(ALL, "button library");
        assertSentFromTheBridgeThread();
    }

    private void endWith(String uri, String reason) throws InterruptedException {
        FakeRequest request = router.next();
        assertEquals(uri, request.uri);
        request.callback.onError(new IllegalStateException(reason));
        awaitStatus("Couldn't find a random song: java.lang.IllegalStateException: " + reason);
    }

    // ---- Helpers ----

    /**
     * A run's action only posts its first step, and the Web API's answers come back to the bridge
     * thread, so every request goes out from there, the first one too.
     */
    private void assertSentFromTheBridgeThread() {
        for (FakeRequest request : router.requests) assertEquals(request.uri, BRIDGE_THREAD, request.thread);
    }

    /** Runs {@code task} on the bridge thread, after everything posted before it, and returns its result. */
    private static <T> T onBridge(Callable<T> task) throws Exception {
        FutureTask<T> run = new FutureTask<>(task);
        PlayerBridge.post(run);
        try {
            return run.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("the bridge thread didn't run a task within 5 s");
        }
    }

    /** Waits for the extension's line in Spicetify settings to read {@code line}. */
    static void awaitStatus(String line) throws InterruptedException {
        String expected = "Play a random song: " + line;
        long deadline = System.currentTimeMillis() + 5000;
        while (!Extensions.statusLines().contains(expected)) {
            assertTrue("no status \"" + line + "\" within 5 s: " + Extensions.statusLines(),
                    System.currentTimeMillis() < deadline);
            Thread.sleep(10);
        }
    }

    /** Runs the main looper, where the runs post their Toasts, until one reads {@code text}. */
    static void awaitToast(String text) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            shadowOf(Looper.getMainLooper()).idle();
            if (text.equals(ShadowToast.getTextOfLatestToast())) return;
            assertTrue("no Toast \"" + text + "\" within 5 s, the last was " + ShadowToast.getTextOfLatestToast(),
                    System.currentTimeMillis() < deadline);
            Thread.sleep(10);
        }
    }

    private static String parameter(String url, String name) {
        Matcher matcher = Pattern.compile("[?&]" + name + "=([^&]*)").matcher(url);
        assertTrue(url, matcher.find());
        return matcher.group(1);
    }

    /** A {@code PlaylistGetResponse} with status 200, the playable {@code length} and {@code uris} as its items. */
    private static byte[] playlistPage(int length, String... uris) {
        Wire.Writer status = new Wire.Writer();
        status.varint(1, 200);
        Wire.Writer data = new Wire.Writer();
        for (String uri : uris) {
            Wire.Writer item = new Wire.Writer();
            item.string(18, uri);
            data.message(1, item);
        }
        data.varint(4, length);
        Wire.Writer response = new Wire.Writer();
        response.message(1, status);
        response.message(2, data);
        return response.toByteArray();
    }

    /** A {@code GetEntityResponse} for an album with one disc of the tracks {@code gids}. */
    private static byte[] albumTracks(byte[]... gids) {
        Wire.Writer disc = new Wire.Writer();
        for (byte[] gid : gids) {
            Wire.Writer track = new Wire.Writer();
            track.bytes(1, gid);
            disc.message(3, track);
        }
        Wire.Writer album = new Wire.Writer();
        album.message(11, disc);
        Wire.Writer item = new Wire.Writer();
        item.message(3, album);
        Wire.Writer response = new Wire.Writer();
        response.message(1, item);
        return response.toByteArray();
    }

    private static byte[] gid(int last) {
        byte[] gid = new byte[16];
        gid[15] = (byte) last;
        return gid;
    }

    /** A {@code PlaylistGetRequest}'s range start: query 2, then range 4, then start 1. */
    private static int rangeStart(byte[] playlistGetRequest) throws IOException {
        byte[] range = EsperantoTest.nestedBytes(EsperantoTest.nestedBytes(playlistGetRequest, 2), 4);
        return (int) EsperantoTest.varintField(range, 1);
    }

    /** Takes the runs' requests in the order they're sent; a run sends its next one only after an answer. */
    static final class FakeRouter implements CosmosRouter {
        final List<FakeRequest> requests = new CopyOnWriteArrayList<>();
        private final BlockingQueue<FakeRequest> unread = new LinkedBlockingQueue<>();

        @Override
        public Cancel resolve(String action, String uri, byte[] body, Callback callback) {
            FakeRequest request = new FakeRequest(action, uri, body, callback);
            requests.add(request);
            unread.add(request);
            return () -> {};
        }

        @Override
        public boolean destroyed() {
            return false;
        }

        FakeRequest next() throws InterruptedException {
            FakeRequest request = unread.poll(5, TimeUnit.SECONDS);
            assertNotNull("no request within 5 s", request);
            return request;
        }
    }

    static final class FakeRequest {
        final String action;
        final String uri;
        final byte[] body;
        final CosmosRouter.Callback callback;
        /** The thread that sent it, since resolve runs on the sender's thread. */
        final String thread = Thread.currentThread().getName();

        FakeRequest(String action, String uri, byte[] body, CosmosRouter.Callback callback) {
            this.action = action;
            this.uri = uri;
            this.body = body;
            this.callback = callback;
        }
    }

    /** A stubbed Web API: each GET answers the next canned body, and keeps its url, token and thread. */
    private static final class Search implements WebApi.Http {
        final List<String> urls = new CopyOnWriteArrayList<>();
        final List<String> tokens = new CopyOnWriteArrayList<>();
        volatile String thread;
        private final Deque<String> answers;

        Search(String... answers) {
            this.answers = new ArrayDeque<>(Arrays.asList(answers));
        }

        @Override
        public synchronized String get(String url, String token) {
            urls.add(url);
            tokens.add(token);
            thread = Thread.currentThread().getName();
            return answers.remove();
        }
    }
}
