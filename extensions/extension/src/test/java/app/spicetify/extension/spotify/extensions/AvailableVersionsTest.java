package app.spicetify.extension.spotify.extensions;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

/** Robolectric for its real {@code org.json} and SharedPreferences. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class AvailableVersionsTest {
    private static final String ORIGINAL = "spotify:track:original";
    private static final String TOKEN = "SECRET-TOKEN";
    private static final String ISRC = "GBAYE0601498";
    private static final String TRACK_URL = "https://api.spotify.com/v1/tracks/original?market=from_token";
    private static final String ISRC_URL =
            "https://api.spotify.com/v1/search?q=isrc%3AGBAYE0601498&type=track&market=from_token&limit=10";
    private static final String TITLE_URL = "https://api.spotify.com/v1/search"
            + "?q=track%3A%22Song%22+artist%3A%22The+Band%22&type=track&market=from_token&limit=10";
    private static final String NONE = "No available version in your country";
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    private final Context context = RuntimeEnvironment.getApplication();
    private final List<String> sentToSpotify = new CopyOnWriteArrayList<>();
    private final FakeWebApi webApi = new FakeWebApi();
    /** The cache's clock, in milliseconds since the epoch. */
    private final AtomicLong now = new AtomicLong(1_790_000_000_000L);

    @Before
    public void setUp() {
        Extensions.setAppContext(context);
        // The cache lives in the process's preferences, so each test starts it empty.
        preferences().edit().clear().commit();
        // Spotify's router, as far as a lookup needs it: it answers each token request at once.
        PlayerBridge.attach(new CosmosRouter() {
            @Override
            public Cancel resolve(String action, String uri, byte[] body, Callback callback) {
                sentToSpotify.add(action + " " + uri);
                if (WebApi.TOKEN_URI.equals(uri)) {
                    callback.onResponse(200, ("{\"accessToken\":\"" + TOKEN + "\",\"expiresIn\":3600,\"errorCode\":0}")
                            .getBytes(UTF_8));
                }
                return () -> {};
            }

            @Override
            public boolean destroyed() {
                return false;
            }
        });
    }

    // ---- The three steps ----

    @Test
    public void theVersionTheWebApiRelinksToIsFoundWithoutASearch() throws Exception {
        // Relinked: the root is the instance that plays here, and linked_from, deprecated, is left out.
        webApi.answer(TRACK_URL, track("relinked", "Song", true));

        Outcomes outcomes = resolve(ORIGINAL);

        assertEquals("found spotify:track:relinked Song", outcomes.next());
        assertEquals(Collections.singletonList(TRACK_URL), webApi.urls);
        assertEquals("the core's token", Collections.singletonList(TOKEN), webApi.tokens);
        assertEquals("the requests run on the Web API thread", "Spicetify Web API", webApi.thread);
        assertEquals("the outcome comes on the bridge thread", "Spicetify player bridge", outcomes.thread);
        assertEquals(Collections.singletonList("GET " + WebApi.TOKEN_URI), sentToSpotify);
        assertEquals("Looking for an available version", AvailableVersions.LOOKING);
        assertEquals("Playing an available version: Song", AvailableVersions.playing("Song"));
    }

    @Test
    public void aVersionWithTheSameIsrcThatPlaysHereIsFoundPreferringTheSameExplicitFlag() throws Exception {
        webApi.answer(TRACK_URL, blocked("market").put("explicit", true));
        JSONObject noPlayability = track("unknown", "Song", true).put("explicit", true);
        noPlayability.remove("is_playable");
        webApi.answer(ISRC_URL, search(
                track("clean", "Song", true), // the other explicit flag, so only if nothing better plays
                track("greyed", "Song", false).put("explicit", true),
                noPlayability,
                track("original", "Song", true).put("explicit", true),
                track("otherIsrc", "Song", true).put("explicit", true)
                        .put("external_ids", new JSONObject().put("isrc", "USUM71703861")),
                track("tooLong", "Song", true).put("explicit", true).put("duration_ms", 205_001),
                track("explicit", "Song (Remastered)", true).put("explicit", true).put("duration_ms", 195_000)));

        assertEquals("found spotify:track:explicit Song (Remastered)", resolve(ORIGINAL).next());
        assertEquals(Arrays.asList(TRACK_URL, ISRC_URL), webApi.urls);
    }

    @Test
    public void anIsrcMatchWithTheOtherExplicitFlagIsTakenWhenNoneHasTheSame() throws Exception {
        webApi.answer(TRACK_URL, blocked("market").put("explicit", true));
        webApi.answer(ISRC_URL, search(track("clean", "Song", true)));

        assertEquals("found spotify:track:clean Song", resolve(ORIGINAL).next());
    }

    @Test
    public void aVersionWithTheSameTitleAndFirstArtistIsFoundWhenNoIsrcMatchPlays() throws Exception {
        webApi.answer(TRACK_URL, blocked("market").put("name", "Song - Remastered 2011"));
        webApi.answer(ISRC_URL, search(track("greyedToo", "Song", false)));
        webApi.answer(TITLE_URL, search(
                track("live", "Song - Live at Wembley", true),
                track("karaoke", "Song (Karaoke Version)", true),
                track("instrumental", "Song [Instrumental]", true),
                track("cover", "Song - Cover", true),
                track("otherArtist", "Song", true).put("artists", artists("other", "Another Band")),
                track("tooLong", "Song", true).put("duration_ms", 203_001),
                track("otherSong", "Song Two", true),
                track("greyed", "Song", false),
                track("byName", "SONG (2019 Mix)", true).put("artists", artists("band2", "the band"))
                        .put("duration_ms", 197_000)));

        assertEquals("found spotify:track:byName SONG (2019 Mix)", resolve(ORIGINAL).next());
        assertEquals(Arrays.asList(TRACK_URL, ISRC_URL, TITLE_URL), webApi.urls);
    }

    @Test
    public void theFirstArtistMatchesByIdWhateverItsSpelling() throws Exception {
        webApi.answer(TRACK_URL, blocked("market"));
        webApi.answer(ISRC_URL, search());
        webApi.answer(TITLE_URL, search(track("byId", "Song", true).put("artists", artists("band", "Band, The"))));

        assertEquals("found spotify:track:byId Song", resolve(ORIGINAL).next());
    }

    @Test
    public void aLiveOriginalMayMatchALiveVersion() throws Exception {
        webApi.answer(TRACK_URL, blocked("market").put("name", "Song (Live)"));
        webApi.answer(ISRC_URL, search());
        webApi.answer(TITLE_URL, search(track("live", "Song - Live at Wembley", true)));

        assertEquals("found spotify:track:live Song - Live at Wembley", resolve(ORIGINAL).next());
    }

    @Test
    public void titlesMatchWhateverTheirCaseSpacingOrApostrophes() throws Exception {
        webApi.answer(TRACK_URL, blocked("market").put("name", "Don\u2019t Stop"));
        webApi.answer(ISRC_URL, search());
        webApi.answer("https://api.spotify.com/v1/search?q=track%3A%22Don%E2%80%99t+Stop%22+artist%3A%22The+Band%22"
                + "&type=track&market=from_token&limit=10", search(track("plain", "DON'T  STOP", true)));

        assertEquals("found spotify:track:plain DON'T  STOP", resolve(ORIGINAL).next());
    }

    @Test
    public void withNoMatchItsNoneInYourCountry() throws Exception {
        // No reason with is_playable false counts as a country block too, so both searches run.
        webApi.answer(TRACK_URL, blocked(null));
        webApi.answer(ISRC_URL, search(track("greyed", "Song", false)));
        webApi.answer(TITLE_URL, search(track("otherSong", "Another Song", true)));

        assertEquals("none " + NONE, resolve(ORIGINAL).next());
        assertEquals(Arrays.asList(TRACK_URL, ISRC_URL, TITLE_URL), webApi.urls);
    }

    @Test
    public void aSongHeldBackForPremiumOrExplicitContentOrThatPlaysNeverLooksFurther() throws Exception {
        webApi.answer(TRACK_URL, blocked("product"));
        webApi.answer("https://api.spotify.com/v1/tracks/rude?market=from_token",
                track("rude", "Song", false).put("restrictions", new JSONObject().put("reason", "explicit")));
        webApi.answer("https://api.spotify.com/v1/tracks/plays?market=from_token", track("plays", "Song", true));

        assertEquals("none Spotify doesn't block it in your country (product), so no other version is looked for",
                resolve(ORIGINAL).next());
        assertEquals("none Spotify doesn't block it in your country (explicit), so no other version is looked for",
                resolve("spotify:track:rude").next());
        assertEquals("none Spotify doesn't block it in your country (playable), so no other version is looked for",
                resolve("spotify:track:plays").next());
        assertEquals("no search", 3, webApi.urls.size());
    }

    // ---- The cache ----

    @Test
    public void aFoundVersionIsCachedFor30Days() throws Exception {
        webApi.answer(TRACK_URL, track("relinked", "Song", true), track("relinkedAgain", "Song", true));
        assertEquals("found spotify:track:relinked Song", resolve(ORIGINAL).next());
        assertTrue("by the original's uri", preferences().contains(ORIGINAL));

        now.addAndGet(30 * DAY - 1);
        Outcomes cached = resolve(ORIGINAL);
        assertEquals("found spotify:track:relinked Song", cached.next());
        assertEquals("Spicetify player bridge", cached.thread);
        assertEquals("no token and no request", 1, tokenRequests());
        assertEquals(1, webApi.urls.size());

        now.addAndGet(1);
        assertEquals("found spotify:track:relinkedAgain Song", resolve(ORIGINAL).next());
        assertEquals(2, webApi.urls.size());
    }

    @Test
    public void noneIsCachedFor3Days() throws Exception {
        webApi.answer(TRACK_URL, blocked("market"), track("relinked", "Song", true));
        webApi.answer(ISRC_URL, search());
        webApi.answer(TITLE_URL, search());
        assertEquals("none " + NONE, resolve(ORIGINAL).next());

        now.addAndGet(3 * DAY - 1);
        assertEquals("none " + NONE, resolve(ORIGINAL).next());
        assertEquals("no token and no request", 1, tokenRequests());
        assertEquals(3, webApi.urls.size());

        now.addAndGet(1);
        assertEquals("found spotify:track:relinked Song", resolve(ORIGINAL).next());
        assertEquals(4, webApi.urls.size());
    }

    @Test
    public void anEntryDatedAheadOfTheClockIsLookedUpAgain() throws Exception {
        // Dated a day ahead, as when the clock is set back: kept, it would last 31 days instead of 30.
        preferences().edit().putString(ORIGINAL,
                "{\"uri\":\"spotify:track:stale\",\"title\":\"Stale\",\"at\":" + (now.get() + DAY) + "}").commit();
        webApi.answer(TRACK_URL, track("relinked", "Song", true));

        assertEquals("found spotify:track:relinked Song", resolve(ORIGINAL).next());
    }

    // ---- When it can't finish ----

    @Test
    public void whileTheWebApiAsksToWaitALookupFailsAtOnceCachesNothingAndCachedSongsStillResolve() throws Exception {
        WebApiTest.FakeClock clock = new WebApiTest.FakeClock();
        WebApi.Paced paced = new WebApi.Paced(webApi, clock);
        webApi.answer("https://api.spotify.com/v1/tracks/cached?market=from_token",
                track("cachedAgain", "Cached", true));
        webApi.answer(TRACK_URL, new WebApi.RateLimited("the Web API answered HTTP 429, retry after 30 s", 30, false),
                track("relinked", "Song", true));
        assertEquals("found spotify:track:cachedAgain Cached", resolve("spotify:track:cached", paced).next());

        String waiting = "failed Spotify's Web API asked to wait: try again in 30 s / 30";
        String limited = resolve(ORIGINAL, paced).next();
        assertEquals(waiting, limited);
        assertFalse(limited, limited.contains(TOKEN));
        assertEquals("in the pause it fails at once", waiting, resolve(ORIGINAL, paced).next());
        assertEquals("with no request", 2, webApi.urls.size());
        assertEquals("a cached song still resolves", "found spotify:track:cachedAgain Cached",
                resolve("spotify:track:cached", paced).next());
        assertFalse("nothing cached", preferences().contains(ORIGINAL));

        clock.now += 30_000; // the pause is over
        assertEquals("found spotify:track:relinked Song", resolve(ORIGINAL, paced).next());
        assertEquals(3, webApi.urls.size());
        assertNoTokenLogged();
    }

    @Test
    public void aUsedUpQuotaSaysToRestartSpotifyAndCachesNothing() throws Exception {
        WebApi.Paced paced = new WebApi.Paced(webApi, new WebApiTest.FakeClock());
        webApi.answer(TRACK_URL,
                new WebApi.RateLimited("the Web API answered HTTP 429 (QUOTA_EXCEEDED), retry after 60 s", 60, true));
        String stopped = "failed Spotify's Web API quota is used up: try again after restarting Spotify / 0";

        assertEquals(stopped, resolve(ORIGINAL, paced).next());
        assertEquals("the pace stays stopped", stopped, resolve(ORIGINAL, paced).next());
        assertEquals(1, webApi.urls.size());
        assertFalse(preferences().contains(ORIGINAL));
    }

    @Test
    public void aLookupThatCantFinishSaysWhyAndCachesNothing() throws Exception {
        webApi.answer(TRACK_URL, new IOException("the Web API answered HTTP 500"), "{\"uri\":",
                track("relinked", "Song", true));

        assertEquals("failed Couldn't look for an available version: the Web API answered HTTP 500 / 0",
                resolve(ORIGINAL).next());
        assertEquals("failed Couldn't look for an available version: the Web API gave an answer that can't be read / 0",
                resolve(ORIGINAL).next());
        assertEquals("found spotify:track:relinked Song", resolve(ORIGINAL).next());
        assertEquals(3, webApi.urls.size());

        assertEquals("failed Couldn't look for an available version: not a Spotify song: spotify:local:a:b:c:1 / 0",
                resolve("spotify:local:a:b:c:1").next());
        assertEquals("nothing asked for it", 3, tokenRequests());
        assertNoTokenLogged();
    }

    // ---- Helpers ----

    private Outcomes resolve(String uri) {
        return resolve(uri, webApi);
    }

    private Outcomes resolve(String uri, WebApi.Http http) {
        Outcomes outcomes = new Outcomes();
        AvailableVersions.resolve(context, uri, http, now::get, outcomes);
        return outcomes;
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(AvailableVersions.PREFERENCES, Context.MODE_PRIVATE);
    }

    private int tokenRequests() {
        return Collections.frequency(sentToSpotify, "GET " + WebApi.TOKEN_URI);
    }

    private static void assertNoTokenLogged() {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            String logged = item.msg + (item.throwable == null ? "" : " " + item.throwable);
            assertFalse(logged, logged.contains(TOKEN));
        }
    }

    /** A Web API track object: by The Band, 200 s long, clean, with one ISRC. */
    private static JSONObject track(String id, String name, boolean playable) throws JSONException {
        return new JSONObject()
                .put("uri", "spotify:track:" + id)
                .put("id", id)
                .put("name", name)
                .put("artists", artists("band", "The Band"))
                .put("duration_ms", 200_000)
                .put("explicit", false)
                .put("external_ids", new JSONObject().put("isrc", ISRC))
                .put("is_playable", playable);
    }

    /** The original, which doesn't play here, with {@code reason} as its restriction, or none given for null. */
    private static JSONObject blocked(String reason) throws JSONException {
        JSONObject original = track("original", "Song", false);
        if (reason != null) original.put("restrictions", new JSONObject().put("reason", reason));
        return original;
    }

    private static JSONArray artists(String id, String name) throws JSONException {
        return new JSONArray().put(new JSONObject().put("id", id).put("name", name)
                .put("uri", "spotify:artist:" + id));
    }

    private static JSONObject search(JSONObject... tracks) throws JSONException {
        return new JSONObject().put("tracks",
                new JSONObject().put("items", new JSONArray(Arrays.asList(tracks))).put("total", tracks.length));
    }

    /**
     * A stubbed Web API: each url answers its next canned body, or throws its next failure, and it
     * keeps what was asked.
     */
    private static final class FakeWebApi implements WebApi.Http {
        final List<String> urls = new CopyOnWriteArrayList<>();
        final List<String> tokens = new CopyOnWriteArrayList<>();
        volatile String thread;
        private final Map<String, Deque<Object>> answers = new ConcurrentHashMap<>();

        void answer(String url, Object... bodiesOrFailures) {
            answers.computeIfAbsent(url, key -> new ArrayDeque<>()).addAll(Arrays.asList(bodiesOrFailures));
        }

        @Override
        public synchronized String get(String url, String token) throws IOException {
            urls.add(url);
            tokens.add(token);
            thread = Thread.currentThread().getName();
            Deque<Object> canned = answers.get(url);
            Object next = canned == null ? null : canned.poll();
            if (next == null) throw new IOException("no canned answer for " + url);
            if (next instanceof IOException) throw (IOException) next;
            return next.toString();
        }
    }

    /** Hears one lookup's outcome as a line, and the thread it came on. */
    private static final class Outcomes implements AvailableVersions.Callback {
        private final BlockingQueue<String> heard = new LinkedBlockingQueue<>();
        volatile String thread;

        @Override
        public void found(AvailableVersions.Version version) {
            heard("found " + version.uri + " " + version.title);
        }

        @Override
        public void none(String line) {
            heard("none " + line);
        }

        @Override
        public void failed(String line, long retryAfterSeconds) {
            heard("failed " + line + " / " + retryAfterSeconds);
        }

        private void heard(String outcome) {
            thread = Thread.currentThread().getName();
            heard.add(outcome);
        }

        /** The outcome; only a hung lookup reaches the minute. */
        String next() throws InterruptedException {
            String outcome = heard.poll(60, TimeUnit.SECONDS);
            assertNotNull("no outcome within 60 s", outcome);
            return outcome;
        }
    }
}
