package app.spicetify.extension.spotify.extensions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class TrashBinTest {
    private static final String SKIP_NEXT = "sp://esperanto/spotify.player.esperanto.proto.ContextPlayer/SkipNext";
    private static final String GET_STATE = "sp://esperanto/spotify.player.esperanto.proto.ContextPlayer/GetState";

    private final Context context = RuntimeEnvironment.getApplication();
    private final FakeRouter router = new FakeRouter();
    private PlayerBridge.StateListener probe;

    @Before
    public void setUp() {
        Extensions.setAppContext(context);
        PlayerBridge.attach(router);
        TrashBin.clear(context);
    }

    @After
    public void tearDown() {
        Extensions.setOn(context, Extensions.TRASH_BIN, false);
        if (probe != null) PlayerBridge.removeStateListener(probe);
    }

    // ---- shouldSkip ----

    @Test
    public void shouldSkipIsFalseForAnAdEvenWhenTheTrackIsTrashed() {
        TrashBin.setSong(context, "spotify:track:a", true);
        Esperanto.PlayerState state = state("spotify:track:a", "uid-1");
        state.advertisement = true;

        assertFalse(TrashBin.shouldSkip(state));
    }

    @Test
    public void shouldSkipIsFalseForAnEpisodeEvenWhenTheArtistIsTrashed() {
        TrashBin.setArtist(context, "spotify:artist:z", true);
        Esperanto.PlayerState state = state("spotify:episode:a", "uid-1");
        state.episode = true;
        state.artistUris = Collections.singletonList("spotify:artist:z");

        assertFalse(TrashBin.shouldSkip(state));
    }

    @Test
    public void shouldSkipIsTrueForATrashedSong() {
        TrashBin.setSong(context, "spotify:track:a", true);

        assertTrue(TrashBin.shouldSkip(state("spotify:track:a", "uid-1")));
    }

    @Test
    public void shouldSkipIsTrueWhenAnyArtistIsTrashed() {
        TrashBin.setArtist(context, "spotify:artist:z", true);
        Esperanto.PlayerState state = state("spotify:track:a", "uid-1");
        state.artistUris = Arrays.asList("spotify:artist:y", "spotify:artist:z");

        assertTrue(TrashBin.shouldSkip(state));
    }

    @Test
    public void shouldSkipIsFalseOtherwise() {
        assertFalse(TrashBin.shouldSkip(state("spotify:track:a", "uid-1")));
    }

    // ---- Import and export ----

    @Test
    public void importMergesIntoAnExistingSetWithoutReplacingIt() throws Exception {
        TrashBin.setArtist(context, "spotify:artist:z", true);

        TrashBin.importJson(context, "{\"songs\":{\"spotify:track:a\":true}}");

        assertTrue(TrashBin.isSongTrashed("spotify:track:a"));
        assertTrue("import merges rather than replacing", TrashBin.isArtistTrashed("spotify:artist:z"));
    }

    @Test
    public void exportRoundTrips() throws Exception {
        TrashBin.setSong(context, "spotify:track:a", true);
        TrashBin.setArtist(context, "spotify:artist:z", true);

        String exported = TrashBin.exportJson();
        TrashBin.clear(context);
        TrashBin.importJson(context, exported);

        assertTrue(TrashBin.isSongTrashed("spotify:track:a"));
        assertTrue(TrashBin.isArtistTrashed("spotify:artist:z"));
    }

    @Test(expected = JSONException.class)
    public void importRejectsMalformedJson() throws Exception {
        TrashBin.importJson(context, "not json");
    }

    // ---- Guard ----

    @Test
    public void guardAllowsFiveWithinTenSecondsThenBlocksUntilAQuietPeriod() {
        TrashBin.Guard guard = new TrashBin.Guard();

        assertTrue(guard.allow(0));
        assertTrue(guard.allow(1000));
        assertTrue(guard.allow(2000));
        assertTrue(guard.allow(3000));
        assertTrue(guard.allow(4000));
        assertFalse("the sixth skip within 10 s is refused", guard.allow(5000));
        assertTrue("allowed again after a quiet 10 s", guard.allow(16000));
    }

    // ---- The real bridge, through a fake router ----

    @Test
    public void aTrashedArtistSendsExactlyOneSkipNextAndTheSameTrackUidSendsNone() throws Exception {
        Extensions.setOn(context, Extensions.TRASH_BIN, true);
        TrashBin.setArtist(context, "spotify:artist:a1", true);

        BlockingQueue<Esperanto.PlayerState> seen = new LinkedBlockingQueue<>();
        probe = seen::add;
        PlayerBridge.addStateListener(probe);

        FakeRequest stream = router.only();
        assertEquals(GET_STATE, stream.uri);

        byte[] state = stateBytes("spotify:track:x", "trk-artist-test", "spotify:artist:a1");
        stream.callback.onResponse(200, state);
        assertNotNull("no state within 5 s", seen.poll(5, TimeUnit.SECONDS));

        assertEquals(2, router.requests.size());
        FakeRequest skip = router.requests.get(1);
        assertEquals("POST", skip.action);
        assertEquals(SKIP_NEXT, skip.uri);

        // The same trackUid again: no additional SkipNext.
        stream.callback.onResponse(200, state);
        assertNotNull(seen.poll(5, TimeUnit.SECONDS));

        assertEquals("the same trackUid sends no additional SkipNext", 2, router.requests.size());
    }

    @Test
    public void aForbiddenResultReportsAndDoesNotRetry() throws Exception {
        Extensions.setOn(context, Extensions.TRASH_BIN, true);
        TrashBin.setSong(context, "spotify:track:forbidden", true);

        BlockingQueue<Esperanto.PlayerState> seen = new LinkedBlockingQueue<>();
        probe = seen::add;
        PlayerBridge.addStateListener(probe);

        FakeRequest stream = router.only();
        byte[] state = stateBytes("spotify:track:forbidden", "trk-forbidden-test");
        stream.callback.onResponse(200, state);
        assertNotNull(seen.poll(5, TimeUnit.SECONDS));

        assertEquals(2, router.requests.size());
        FakeRequest skip = router.requests.get(1);
        Wire.Writer forbidden = new Wire.Writer();
        forbidden.varint(1, 1);
        skip.callback.onResponse(200, forbidden.toByteArray());

        // A second push lets the bridge thread finish handling the forbidden result before we assert,
        // and doubles as the "same trackUid, no retry" check.
        stream.callback.onResponse(200, state);
        assertNotNull(seen.poll(5, TimeUnit.SECONDS));

        boolean reported = false;
        for (String line : Extensions.statusLines()) if (line.contains("Spotify refused the skip")) reported = true;
        assertTrue("status should report the refusal: " + Extensions.statusLines(), reported);
        assertEquals("no retry after a forbidden result", 2, router.requests.size());
    }

    @Test
    public void trashingThePlayingSongSkipsItImmediately() throws Exception {
        Extensions.setOn(context, Extensions.TRASH_BIN, true);

        BlockingQueue<Esperanto.PlayerState> seen = new LinkedBlockingQueue<>();
        probe = seen::add;
        PlayerBridge.addStateListener(probe);

        FakeRequest stream = router.only();
        stream.callback.onResponse(200, stateBytes("spotify:track:playing", "trk-playing-test"));
        assertNotNull(seen.poll(5, TimeUnit.SECONDS));
        assertEquals("not trashed yet, so only the subscription so far", 1, router.requests.size());

        TrashBin.setSong(context, "spotify:track:playing", true);

        assertEquals("trashing the playing song skips it", 2, router.requests.size());
        assertEquals(SKIP_NEXT, router.requests.get(1).uri);
    }

    @Test
    public void trashingThePlayingArtistSkipsItImmediatelyButANonPlayingArtistDoesNot() throws Exception {
        Extensions.setOn(context, Extensions.TRASH_BIN, true);

        BlockingQueue<Esperanto.PlayerState> seen = new LinkedBlockingQueue<>();
        probe = seen::add;
        PlayerBridge.addStateListener(probe);

        FakeRequest stream = router.only();
        stream.callback.onResponse(200,
                stateBytes("spotify:track:by-artist", "trk-artist-playing-test", "spotify:artist:playing"));
        assertNotNull(seen.poll(5, TimeUnit.SECONDS));
        assertEquals("not trashed yet, so only the subscription so far", 1, router.requests.size());

        TrashBin.setArtist(context, "spotify:artist:elsewhere", true);
        assertEquals("trashing an artist who isn't playing sends no skip", 1, router.requests.size());

        TrashBin.setArtist(context, "spotify:artist:playing", true);
        assertEquals("trashing the playing artist skips it immediately", 2, router.requests.size());
        assertEquals(SKIP_NEXT, router.requests.get(1).uri);
    }

    @Test
    public void offThenOnResendsSkipNextForTheSameStillPlayingTrashedTrack() throws Exception {
        Extensions.setOn(context, Extensions.TRASH_BIN, true);
        TrashBin.setSong(context, "spotify:track:cycle", true);

        BlockingQueue<Esperanto.PlayerState> seen = new LinkedBlockingQueue<>();
        probe = seen::add;
        // Stands in for another extension's listener: it keeps the stream open across the off/on
        // cycle below, so PlayerBridge.lastState() is preserved instead of being forgotten.
        PlayerBridge.addStateListener(probe);

        FakeRequest stream = router.only();
        stream.callback.onResponse(200, stateBytes("spotify:track:cycle", "trk-cycle-test"));
        assertNotNull(seen.poll(5, TimeUnit.SECONDS));
        assertEquals("the first push skips once", 2, router.requests.size());

        Extensions.setOn(context, Extensions.TRASH_BIN, false);
        Extensions.setOn(context, Extensions.TRASH_BIN, true);

        assertEquals("turning back on re-evaluates the still-playing trashed track",
                3, router.requests.size());
        assertEquals(SKIP_NEXT, router.requests.get(2).uri);
    }

    @Test
    public void autoSkipGoesOnAfterTheStreamEndsWithNothingPressed() throws Exception {
        // Trash Bin's listener alone: nothing else listens, and nobody presses anything.
        RandomSongTest.FakeRouter waiting = new RandomSongTest.FakeRouter();
        PlayerBridge.attach(waiting);
        Extensions.setOn(context, Extensions.TRASH_BIN, true);
        TrashBin.setSong(context, "spotify:track:after-error", true);
        waiting.next().callback.onError(new IllegalStateException("stream gone"));

        RandomSongTest.FakeRequest reopened = waiting.next(); // the bridge's own retry, a second later
        assertEquals("SUB", reopened.action);
        assertEquals(GET_STATE, reopened.uri);
        reopened.callback.onResponse(200, stateBytes("spotify:track:after-error", "trk-after-error-test"));

        assertEquals("Trash Bin skips again", SKIP_NEXT, waiting.next().uri);
    }

    // ---- Helpers ----

    private static Esperanto.PlayerState state(String trackUri, String trackUid) {
        Esperanto.PlayerState state = new Esperanto.PlayerState();
        state.trackUri = trackUri;
        state.trackUid = trackUid;
        return state;
    }

    /**
     * A {@code ContextPlayerState} playing {@code trackUri} with the given {@code trackUid} and
     * artists. Each test uses its own trackUid, since {@link TrashBin} keys "already handled" on it
     * and the field is shared across every test in this class.
     */
    private static byte[] stateBytes(String trackUri, String trackUid, String... artistUris) {
        Wire.Writer contextTrack = new Wire.Writer();
        contextTrack.string(1, trackUri);
        contextTrack.string(2, trackUid);
        for (int i = 0; i < artistUris.length; i++) {
            Wire.Writer entry = new Wire.Writer();
            entry.string(1, i == 0 ? "artist_uri" : "artist_uri:" + i);
            entry.string(2, artistUris[i]);
            contextTrack.message(3, entry);
        }
        Wire.Writer providedTrack = new Wire.Writer();
        providedTrack.message(1, contextTrack);
        Wire.Writer state = new Wire.Writer();
        state.message(7, providedTrack);
        return state.toByteArray();
    }

    private static final class FakeRouter implements CosmosRouter {
        final List<FakeRequest> requests = new CopyOnWriteArrayList<>();
        volatile boolean destroyed;

        @Override
        public Cancel resolve(String action, String uri, byte[] body, Callback callback) {
            FakeRequest request = new FakeRequest(action, uri, body, callback);
            requests.add(request);
            return () -> request.cancelled = true;
        }

        @Override
        public boolean destroyed() {
            return destroyed;
        }

        FakeRequest only() {
            assertEquals(1, requests.size());
            return requests.get(0);
        }
    }

    private static final class FakeRequest {
        final String action;
        final String uri;
        final byte[] body;
        final CosmosRouter.Callback callback;
        volatile boolean cancelled;

        FakeRequest(String action, String uri, byte[] body, CosmosRouter.Callback callback) {
            this.action = action;
            this.uri = uri;
            this.body = body;
            this.callback = callback;
        }
    }
}
