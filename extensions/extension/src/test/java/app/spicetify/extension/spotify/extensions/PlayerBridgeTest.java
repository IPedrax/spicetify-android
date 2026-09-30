package app.spicetify.extension.spotify.extensions;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.spotify.cosmos.cosmos.Lifetime;
import com.spotify.cosmos.cosmos.Request;
import com.spotify.cosmos.cosmos.ResolveCallback;
import com.spotify.cosmos.cosmos.Response;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class PlayerBridgeTest {
    private static final String SKIP_NEXT = "sp://esperanto/spotify.player.esperanto.proto.ContextPlayer/SkipNext";
    private static final String GET_STATE = "sp://esperanto/spotify.player.esperanto.proto.ContextPlayer/GetState";

    private final Context context = RuntimeEnvironment.getApplication();
    private final FakeRouter router = new FakeRouter();
    private final List<PlayerBridge.StateListener> listeners = new ArrayList<>();

    @Before
    public void attachAFakeRouter() {
        // The bridge is process-wide, so each test points it at its own application and router.
        Extensions.setAppContext(context);
        PlayerBridge.attach(router);
    }

    @After
    public void removeListeners() {
        for (PlayerBridge.StateListener listener : listeners) PlayerBridge.removeStateListener(listener);
    }

    // ---- Calls ----

    @Test
    public void callPostsToEsperantoAndDeliversTheBodyOnTheBridgeThread() throws Exception {
        Answer answer = new Answer();
        PlayerBridge.call(Esperanto.CONTEXT_PLAYER, "SkipNext", new byte[] {1, 2}, answer);

        FakeRequest request = router.only();
        assertEquals("POST", request.action);
        assertEquals(SKIP_NEXT, request.uri);
        assertArrayEquals(new byte[] {1, 2}, request.body);

        request.callback.onResponse(200, new byte[] {7});

        answer.await();
        assertArrayEquals(new byte[] {7}, answer.body);
        assertEquals("Spicetify player bridge", answer.thread);
        assertTrue("an answered call releases its request", request.cancelled);
    }

    @Test
    public void aStatusOtherThan200Fails() throws Exception {
        Answer answer = new Answer();
        PlayerBridge.call(Esperanto.CONTEXT_PLAYER, "SkipNext", new byte[0], answer);

        router.only().callback.onResponse(500, new byte[0]);

        answer.await();
        assertEquals("status 500", answer.reason);
        assertNull(answer.body);
    }

    @Test
    public void aRouterErrorFails() throws Exception {
        Answer answer = new Answer();
        PlayerBridge.call(Esperanto.CONTEXT_PLAYER, "SkipNext", new byte[0], answer);
        FakeRequest request = router.only();

        request.callback.onError(new IllegalStateException("router gone"));

        answer.await();
        assertTrue(answer.reason, answer.reason.contains("router gone"));
        assertTrue("an error releases the request", request.cancelled);
    }

    @Test
    public void getSendsAPlainCosmosGet() throws Exception {
        Answer answer = new Answer();
        PlayerBridge.get("sp://auth/v2/token?renew=0", answer);

        FakeRequest request = router.only();
        assertEquals("GET", request.action);
        assertEquals("sp://auth/v2/token?renew=0", request.uri);
        assertArrayEquals(new byte[0], request.body);
        request.callback.onResponse(200, "{}".getBytes(UTF_8));

        answer.await();
        assertEquals("{}", new String(answer.body, UTF_8));
    }

    @Test
    public void aCallbackCopiesTheBodyAndLeavesTheWorkToTheBridgeThread() throws Exception {
        CountDownLatch busy = new CountDownLatch(1);
        PlayerBridge.call(Esperanto.CONTEXT_PLAYER, "SkipNext", new byte[0], new PlayerBridge.Result() {
            @Override
            public void done(byte[] body) {
                awaitQuietly(busy);
            }

            @Override
            public void failed(String reason) {}
        });
        Answer answer = new Answer();
        PlayerBridge.call(Esperanto.CONTEXT_PLAYER, "SkipNext", new byte[0], answer);

        router.requests.get(0).callback.onResponse(200, new byte[0]); // holds the bridge thread
        byte[] body = {5};
        router.requests.get(1).callback.onResponse(200, body); // returns while the bridge thread is held
        body[0] = 6; // Spotify owns this array once its callback returns
        busy.countDown();

        answer.await();
        assertArrayEquals(new byte[] {5}, answer.body);
    }

    @Test
    public void onlyTheFirstAnswerCountsEvenWhenItArrivesBeforeResolveReturns() throws Exception {
        router.answerWhileResolving = new byte[] {42};
        Answer answer = new Answer();

        PlayerBridge.call(Esperanto.CONTEXT_PLAYER, "SkipNext", new byte[0], answer);

        FakeRequest request = router.only();
        assertTrue("released as soon as resolve handed it back", request.cancelled);
        request.callback.onResponse(500, new byte[0]);
        flush(router);
        assertEquals(1, answer.count);
        assertArrayEquals(new byte[] {42}, answer.body);
    }

    @Test
    public void aDestroyedRouterFailsCallsWithBridgeNotConnected() throws Exception {
        router.destroyed = true;
        Answer answer = new Answer();

        PlayerBridge.call(Esperanto.CONTEXT_PLAYER, "SkipNext", new byte[0], answer);

        answer.await();
        assertEquals("bridge not connected", answer.reason);
        assertTrue(router.requests.isEmpty());
        assertFalse(PlayerBridge.connected());
    }

    @Test
    public void aRouterDestroyedAfterTheBridgesCheckFailsTheCallWithoutCallingIn() throws Exception {
        FakeService service = new FakeService();
        PlayerBridge.attach(CosmosRouter.reflective(service));
        service.router.destroyAfterFirstCheck = true;
        Answer answer = new Answer();

        PlayerBridge.call(Esperanto.CONTEXT_PLAYER, "SkipNext", new byte[0], answer);

        answer.await();
        assertEquals("bridge not connected", answer.reason);
        assertNull("never sent to the destroyed router", service.router.request);
    }

    @Test
    public void attachingANewRouterFailsTheOldRoutersPendingCalls() throws Exception {
        Answer answer = new Answer();
        PlayerBridge.call(Esperanto.CONTEXT_PLAYER, "SkipNext", new byte[0], answer);
        FakeRequest pending = router.only();
        FakeRouter replacement = new FakeRouter();

        PlayerBridge.attach(replacement);

        answer.await();
        assertEquals("bridge not connected", answer.reason);
        assertTrue("its request is released", pending.cancelled);
        pending.callback.onResponse(200, new byte[] {1}); // a late answer from the old router
        flush(replacement);
        assertEquals(1, answer.count);
        assertNull(answer.body);
    }

    // ---- The player state stream ----

    @Test
    public void theFirstListenerOpensOneStreamAndRemovingTheLastCancelsIt() {
        States first = listen(new States());

        FakeRequest stream = router.only();
        assertEquals("SUB", stream.action);
        assertEquals(GET_STATE, stream.uri);
        assertArrayEquals(Esperanto.getState(), stream.body);

        States second = listen(new States());
        assertEquals("a second listener opens none", 1, router.requests.size());

        PlayerBridge.removeStateListener(first);
        assertFalse(stream.cancelled);
        PlayerBridge.removeStateListener(second);
        assertTrue(stream.cancelled);
    }

    @Test
    public void aStateReachesListenersParsed() throws Exception {
        States states = listen(new States());

        router.only().callback.onResponse(200, EsperantoTest.contextPlayerState("spotify:track:x"));

        Esperanto.PlayerState state = states.next();
        assertEquals("spotify:playlist:p", state.contextUri);
        assertEquals("spotify:track:x", state.trackUri);
        assertEquals("uid-1", state.trackUid);
        assertEquals(2, state.artistUris.size());
        assertEquals("Spicetify player bridge", states.thread);
        assertSame(state, PlayerBridge.lastState());
    }

    @Test
    public void removingTheLastListenerForgetsTheLastState() throws Exception {
        States states = listen(new States());
        router.only().callback.onResponse(200, EsperantoTest.contextPlayerState("spotify:track:x"));
        states.next();

        PlayerBridge.removeStateListener(states);

        assertNull("no stream, so the track may have changed", PlayerBridge.lastState());
    }

    @Test
    public void aStreamErrorForgetsTheStateShowsWhyAndTheNextListenerReopens() throws Exception {
        States states = listen(new States());
        FakeRequest stream = router.only();
        stream.callback.onResponse(200, EsperantoTest.contextPlayerState("spotify:track:x"));
        states.next();

        FutureTask<String> line = new FutureTask<>(() -> Extensions.statusLines().get(0));
        RandomSongTest.onBridge(() -> {
            stream.callback.onError(new IllegalStateException("stream gone"));
            PlayerBridge.post(line); // behind the error's handling, and before its reopen
            return null;
        });

        assertEquals("Player bridge: stream error, retrying"
                + " (The player state stream ended: java.lang.IllegalStateException: stream gone)",
                line.get(5, TimeUnit.SECONDS)); // the error has been handled by now
        assertTrue("an ended stream is released", stream.cancelled);
        assertNull(PlayerBridge.lastState());

        listen(new States());
        FakeRequest reopened = router.requests.get(router.requests.size() - 1);
        assertEquals("SUB", reopened.action);
        assertEquals(GET_STATE, reopened.uri);
        reopened.callback.onResponse(200, EsperantoTest.contextPlayerState("spotify:track:y"));
        assertEquals("spotify:track:y", states.next().trackUri);
        assertEquals("a good state clears the problem",
                "Player bridge: connected, spotify:track:y", Extensions.statusLines().get(0));
    }

    @Test
    public void anEndedStreamOpensAgainAfterABackoffThatDoublesUntilAStateArrives() throws Exception {
        RandomSongTest.FakeRouter waiting = new RandomSongTest.FakeRouter();
        PlayerBridge.attach(waiting);
        States states = listen(new States());

        RandomSongTest.FakeRequest second = reopenedAfter(waiting.next(), waiting, 1000);
        RandomSongTest.FakeRequest third = reopenedAfter(second, waiting, 2000); // doubled
        third.callback.onResponse(200, EsperantoTest.contextPlayerState("spotify:track:x"));
        states.next(); // a state sets the backoff back to a second
        reopenedAfter(third, waiting, 1000);
    }

    @Test
    public void aStreamSpotifyWontOpenIsTriedAgainASecondLater() throws Exception {
        RandomSongTest.FakeRouter waiting = new RandomSongTest.FakeRouter();
        AtomicBoolean refused = new AtomicBoolean();
        PlayerBridge.attach(new CosmosRouter() {
            @Override
            public Cancel resolve(String action, String uri, byte[] body, Callback callback) {
                if (refused.compareAndSet(false, true)) throw new IllegalStateException("refused");
                return waiting.resolve(action, uri, body, callback);
            }

            @Override
            public boolean destroyed() {
                return false;
            }
        });

        States states = new States();
        listeners.add(states);
        FutureTask<String> line = new FutureTask<>(() -> Extensions.statusLines().get(0));
        RandomSongTest.onBridge(() -> {
            PlayerBridge.addStateListener(states); // refused, so a reopen is due in a second
            PlayerBridge.post(line); // due now, so before that reopen
            return null;
        });

        assertEquals("Player bridge: stream error, retrying"
                + " (Couldn't open the player state stream: java.lang.IllegalStateException: refused)",
                line.get(5, TimeUnit.SECONDS));
        RandomSongTest.FakeRequest retried = waiting.next();
        assertEquals("SUB", retried.action);
        assertEquals(GET_STATE, retried.uri);
    }

    @Test
    public void aReopenOpensNothingWhileAStreamIsOpenOrOnceNothingListens() throws Exception {
        RandomSongTest.FakeRouter waiting = new RandomSongTest.FakeRouter();
        PlayerBridge.attach(waiting);
        States first = listen(new States());
        RandomSongTest.FakeRequest ended = waiting.next();

        // A new listener opens a stream while the reopen is due, and the reopen leaves it alone. The
        // listener comes in a task queued right behind the error's handling, so before the reopen, and
        // the probe after the sleep falls due after the reopen, so the bridge runs it after.
        States second = new States();
        listeners.add(second);
        RandomSongTest.onBridge(() -> {
            ended.callback.onError(new IllegalStateException("stream gone"));
            PlayerBridge.post(() -> PlayerBridge.addStateListener(second));
            return null;
        });
        RandomSongTest.FakeRequest opened = waiting.next();
        Thread.sleep(1500);
        RandomSongTest.onBridge(() -> null);
        assertEquals("the new listener's stream, and no second one", 2, waiting.requests.size());

        // That stream ends too, and nothing listens by the time its reopen is due, 2 s later. The
        // listeners go in a task queued right behind the error's handling, so before that reopen.
        RandomSongTest.onBridge(() -> {
            opened.callback.onError(new IllegalStateException("stream gone again"));
            PlayerBridge.post(() -> {
                PlayerBridge.removeStateListener(first);
                PlayerBridge.removeStateListener(second);
            });
            return null;
        });
        Thread.sleep(2500);
        RandomSongTest.onBridge(() -> null);
        assertEquals("no stream when nothing listens", 2, waiting.requests.size());
    }

    @Test
    public void aNewRouterStartsTheRetryOver() throws Exception {
        RandomSongTest.FakeRouter old = new RandomSongTest.FakeRouter();
        PlayerBridge.attach(old);
        listen(new States());
        RandomSongTest.FakeRequest ended = old.next();
        RandomSongTest.FakeRouter replacement = new RandomSongTest.FakeRouter();
        // The new router comes half a second after the error, by the bridge's own clock. It's scheduled
        // before the error is even handled, so it falls due after that handling and before the old
        // router's reopen, however slow the machine.
        FutureTask<Void> attached = new FutureTask<>(() -> PlayerBridge.attach(replacement), null);
        RandomSongTest.onBridge(() -> {
            PlayerBridge.postDelayed(attached, 500);
            ended.callback.onError(new IllegalStateException("stream gone"));
            return null;
        });
        attached.get(5500, TimeUnit.MILLISECONDS);
        RandomSongTest.FakeRequest moved = replacement.next(); // the stream moved to it at once

        // Its stream ends too: it reopens a whole second later, not when the old router's reopen was due.
        reopenedAfter(moved, replacement, 1000);
        assertEquals("nothing more went to the old router", 1, old.requests.size());
    }

    @Test
    public void aThrowingListenerDoesNotStopTheOthers() throws Exception {
        listen(state -> {
            throw new IllegalStateException("listener failed");
        });
        States states = listen(new States());

        router.only().callback.onResponse(200, EsperantoTest.contextPlayerState("spotify:track:x"));

        assertEquals("spotify:track:x", states.next().trackUri);
    }

    @Test
    public void attachingANewRouterMovesTheStreamToIt() throws Exception {
        States states = listen(new States());
        FakeRequest old = router.only();

        FakeRouter replacement = new FakeRouter();
        PlayerBridge.attach(replacement);

        assertTrue(old.cancelled);
        FakeRequest reopened = replacement.only();
        assertEquals("SUB", reopened.action);
        assertEquals(GET_STATE, reopened.uri);

        // A state the old router still had queued is dropped; the bridge thread runs tasks in order.
        old.callback.onResponse(200, EsperantoTest.contextPlayerState("spotify:track:old"));
        reopened.callback.onResponse(200, EsperantoTest.contextPlayerState("spotify:track:new"));
        assertEquals("spotify:track:new", states.next().trackUri);
    }

    @Test
    public void aStateThatCantBeReadIsReportedOnceAndTheStreamStaysOpen() throws Exception {
        File log = new File(context.getFilesDir(), "spicetify_extensions.log");
        log.delete();
        States states = listen(new States());
        FakeRequest stream = router.only();

        byte[] truncated = {0x12, 0x05, 'a'}; // field 2 declares 5 bytes and carries 1
        stream.callback.onResponse(200, truncated);
        stream.callback.onResponse(500, new byte[0]);
        stream.callback.onResponse(200, truncated);
        stream.callback.onResponse(200, EsperantoTest.contextPlayerState("spotify:track:x"));

        assertEquals("spotify:track:x", states.next().trackUri);
        assertFalse(stream.cancelled);
        String written = ExtensionsTest.awaitLog(context, log);
        assertEquals(written, 1, written.split(" player_bridge: Couldn't read the player state", -1).length - 1);
        assertEquals("Player bridge: connected, spotify:track:x", Extensions.statusLines().get(0));
    }

    @Test
    public void statusLinesStartWithTheBridgeLine() throws Exception {
        assertEquals("Player bridge: connected", Extensions.statusLines().get(0));

        States states = listen(new States());
        router.only().callback.onResponse(200, EsperantoTest.contextPlayerState("spotify:track:x"));
        states.next();
        assertEquals("Player bridge: connected, spotify:track:x", Extensions.statusLines().get(0));

        router.destroyed = true;
        assertEquals("Player bridge: waiting for Spotify", Extensions.statusLines().get(0));
    }

    @Test
    public void theProblemLineIsTheBridgesLineOnlyWhileItWaitsForSpotifyOrRetriesTheStream() throws Exception {
        router.destroyed = true;
        assertEquals("Player bridge: waiting for Spotify", PlayerBridge.problemLine());
        router.destroyed = false;
        assertNull("connected", PlayerBridge.problemLine());

        States states = listen(new States());
        FakeRequest stream = router.only();
        stream.callback.onResponse(200, EsperantoTest.contextPlayerState("spotify:track:x"));
        states.next();
        assertNull("connected and playing", PlayerBridge.problemLine());

        FutureTask<String> retrying = new FutureTask<>(PlayerBridge::problemLine);
        RandomSongTest.onBridge(() -> {
            stream.callback.onError(new IllegalStateException("stream gone"));
            PlayerBridge.post(retrying); // behind the error's handling, and before its reopen
            return null;
        });
        assertEquals("Player bridge: stream error, retrying"
                + " (The player state stream ended: java.lang.IllegalStateException: stream gone)",
                retrying.get(5, TimeUnit.SECONDS));
    }

    // ---- Spotify's router, through reflection ----

    @Test
    public void reflectiveBuildsSpotifysRequestAndMapsItsCallback() throws Exception {
        FakeService service = new FakeService();
        CosmosRouter reflective = CosmosRouter.reflective(service);
        Recorded recorded = new Recorded();

        CosmosRouter.Cancel cancel = reflective.resolve("SUB", GET_STATE, new byte[] {3}, recorded);

        Request request = service.router.request;
        assertEquals("SUB", request.getAction());
        assertEquals(GET_STATE, request.getUri());
        assertArrayEquals(new byte[] {3}, request.getBody());

        ResolveCallback callback = service.router.callback;
        callback.onResolved(new Response(200, new byte[] {9}));
        assertEquals(200, recorded.status);
        assertArrayEquals(new byte[] {9}, recorded.body);
        IllegalStateException error = new IllegalStateException("gone");
        callback.onError(error);
        assertSame(error, recorded.error);

        // Spotify may keep callbacks in hashed collections, so the proxy answers Object's methods.
        assertTrue(callback.equals(callback));
        assertFalse(callback.equals(new Object()));
        Set<ResolveCallback> callbacks = new HashSet<>();
        callbacks.add(callback);
        assertTrue(callbacks.contains(callback));
        assertNotNull(callback.toString());

        assertEquals(0, service.router.released);
        cancel.cancel();
        assertEquals(1, service.router.released);

        assertFalse(reflective.destroyed());
        service.router.destroyed = true;
        assertTrue(reflective.destroyed());
    }

    @Test
    public void onCosmosAttachesSpotifysRouterAndStartsTheExtensionsThatAreOn() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Extensions.onSwitch("test_started", (switchContext, on) -> {
            if (on && switchContext == context) started.countDown();
        });
        context.getSharedPreferences("spicetify_extensions", Context.MODE_PRIVATE).edit()
                .putBoolean("test_started", true).commit();
        Extensions.setAppContext(null);
        FakeService service = new FakeService();

        PlayerBridge.onCosmos(service, true);

        assertSame("found through the router's class loader", context, Extensions.appContext());
        assertTrue(PlayerBridge.connected());
        assertTrue("an extension that is on starts with Spotify", started.await(5, TimeUnit.SECONDS));
        PlayerBridge.call(Esperanto.CONTEXT_PLAYER, "SkipNext", new byte[0], new Answer());
        assertEquals(SKIP_NEXT, service.router.request.getUri());
    }

    @Test
    public void withoutTheExtensionsPatchOnCosmosConnectsTheBridgeButStartsNoExtension() throws Exception {
        AtomicBoolean started = new AtomicBoolean();
        Extensions.onSwitch("test_left_on", (switchContext, on) -> started.set(true));
        context.getSharedPreferences("spicetify_extensions", Context.MODE_PRIVATE).edit()
                .putBoolean("test_left_on", true).commit(); // switched on before the extensions patch was left out
        FakeService service = new FakeService();

        PlayerBridge.onCosmos(service); // Home pins alone: InstalledPatches.extensions() is false, as in every test
        RandomSongTest.onBridge(() -> null); // runs after anything onCosmos posted

        assertTrue(PlayerBridge.connected());
        assertFalse("nothing starts without the extensions patch", started.get());
        PlayerBridge.call(Esperanto.YOUR_LIBRARY, "All", Esperanto.yourLibraryAll(), new Answer());
        assertEquals("sp://esperanto/" + Esperanto.YOUR_LIBRARY + "/All", service.router.request.getUri());
    }

    @Test
    public void onCosmosWithoutSpotifysRouterKeepsTheBridgeAndLogsWhy() throws Exception {
        File log = new File(context.getFilesDir(), "spicetify_extensions.log");
        log.delete();

        PlayerBridge.onCosmos(new Object());

        assertTrue(PlayerBridge.connected());
        String written = ExtensionsTest.awaitLog(context, log);
        assertTrue(written, written.contains(" player_bridge: Couldn't connect: java.lang.NoSuchMethodException"));
        router.destroyed = true;
        String line = Extensions.statusLines().get(0);
        assertTrue(line, line.startsWith(
                "Player bridge: waiting for Spotify (Couldn't connect: java.lang.NoSuchMethodException"));
    }

    // ---- Helpers ----

    /**
     * Attaches a fresh router that answers nothing, one Spotify has destroyed when {@code destroyed};
     * for tests outside this package, since the bridge is process-wide.
     */
    public static void attachRouter(boolean destroyed) {
        FakeRouter fresh = new FakeRouter();
        fresh.destroyed = destroyed;
        PlayerBridge.attach(fresh);
    }

    private <T extends PlayerBridge.StateListener> T listen(T listener) {
        listeners.add(listener);
        PlayerBridge.addStateListener(listener);
        return listener;
    }

    /** Waits for the bridge thread to run everything posted so far; it runs tasks in order. */
    private static void flush(FakeRouter attached) throws InterruptedException {
        Answer marker = new Answer();
        PlayerBridge.call("flush", "flush", new byte[0], marker);
        attached.requests.get(attached.requests.size() - 1).callback.onResponse(200, new byte[0]);
        marker.await();
    }

    /**
     * Ends {@code stream} and checks, by the bridge's own clock, that its reopen comes {@code backoff}
     * ms after the error is handled: a probe due 1 ms sooner finds no new request, and one due that
     * late finds the reopen. The bridge runs tasks in the order they fall due, ties in the order they
     * were posted, so a slow machine can hold the probes up but can't move them around the reopen.
     */
    private static RandomSongTest.FakeRequest reopenedAfter(RandomSongTest.FakeRequest stream,
            RandomSongTest.FakeRouter waiting, long backoff) throws Exception {
        int sent = waiting.requests.size();
        FutureTask<Integer> sooner = new FutureTask<>(waiting.requests::size);
        FutureTask<Integer> onTime = new FutureTask<>(waiting.requests::size);
        RandomSongTest.onBridge(() -> {
            PlayerBridge.postDelayed(sooner, backoff - 1); // before the error, so due before its reopen
            stream.callback.onError(new IllegalStateException("stream gone"));
            PlayerBridge.post(() -> PlayerBridge.postDelayed(onTime, backoff)); // after the error's handling
            return null;
        });
        assertEquals("nothing reopened within " + (backoff - 1) + " ms",
                sent, (int) sooner.get(backoff + 5000, TimeUnit.MILLISECONDS));
        assertEquals("reopened by " + backoff + " ms",
                sent + 1, (int) onTime.get(backoff + 5000, TimeUnit.MILLISECONDS));
        RandomSongTest.FakeRequest reopened = waiting.next();
        assertEquals("SUB", reopened.action);
        assertEquals(GET_STATE, reopened.uri);
        return reopened;
    }

    static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class FakeRouter implements CosmosRouter {
        final List<FakeRequest> requests = new CopyOnWriteArrayList<>();
        volatile boolean destroyed;
        volatile byte[] answerWhileResolving;

        @Override
        public Cancel resolve(String action, String uri, byte[] body, Callback callback) {
            FakeRequest request = new FakeRequest(action, uri, body, callback);
            requests.add(request);
            byte[] answer = answerWhileResolving;
            if (answer != null) callback.onResponse(200, answer);
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

    private static final class Answer implements PlayerBridge.Result {
        private final CountDownLatch answered = new CountDownLatch(1);
        volatile byte[] body;
        volatile String reason;
        volatile String thread;
        volatile int count; // written only by the bridge thread

        @Override
        public void done(byte[] body) {
            this.body = body;
            answered();
        }

        @Override
        public void failed(String reason) {
            this.reason = reason;
            answered();
        }

        private void answered() {
            thread = Thread.currentThread().getName();
            count++;
            answered.countDown();
        }

        void await() throws InterruptedException {
            assertTrue("no answer within 5 s", answered.await(5, TimeUnit.SECONDS));
        }
    }

    private static final class States implements PlayerBridge.StateListener {
        private final BlockingQueue<Esperanto.PlayerState> received = new LinkedBlockingQueue<>();
        volatile String thread;

        @Override
        public void onState(Esperanto.PlayerState state) {
            thread = Thread.currentThread().getName();
            received.add(state);
        }

        Esperanto.PlayerState next() throws InterruptedException {
            Esperanto.PlayerState state = received.poll(5, TimeUnit.SECONDS);
            assertNotNull("no state within 5 s", state);
            return state;
        }
    }

    private static final class Recorded implements CosmosRouter.Callback {
        int status;
        byte[] body;
        Throwable error;

        @Override
        public void onResponse(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }
    }

    /** Stands in for {@code SharedCosmosRouterService}; reflective() only calls getRemoteNativeRouter(). */
    public static final class FakeService {
        final FakeNativeRouter router = new FakeNativeRouter();

        public FakeNativeRouter getRemoteNativeRouter() {
            return router;
        }
    }

    /** Stands in for {@code RemoteNativeRouter}. */
    public static final class FakeNativeRouter {
        volatile Request request;
        volatile ResolveCallback callback;
        volatile int released;
        volatile boolean destroyed;
        /** Destroys the router right after its first check, as a logout between check and call would. */
        volatile boolean destroyAfterFirstCheck;

        public Lifetime performNativeResolve(Request request, ResolveCallback callback) {
            // Spotify's own resolve holds this monitor, and destroy() takes it.
            assertTrue("sent under the router's monitor", Thread.holdsLock(this));
            this.request = request;
            this.callback = callback;
            return () -> released++;
        }

        public boolean getRouterDestroyed() {
            boolean was = destroyed;
            if (destroyAfterFirstCheck) destroyed = true;
            return was;
        }
    }
}
