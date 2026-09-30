package app.spicetify.extension.spotify.extensions;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowToast;

/**
 * Unavailable songs' tap on a greyed-out row (unavailable songs report, sections 2.4, 4 and 7.1),
 * through the real bridge thread, the real {@link AvailableVersions} and a fake router and Web API.
 * Every wait has a minute's ceiling, which only a hang reaches. Robolectric's clock is fake, so the
 * list play's window only moves when a test moves it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class UnavailableSongsTest {
    private static final String GET_STATE = "sp://esperanto/spotify.player.esperanto.proto.ContextPlayer/GetState";
    private static final String GET_ERROR = "sp://esperanto/spotify.player.esperanto.proto.ContextPlayer/GetError";
    private static final String PLAY = "sp://esperanto/spotify.player.esperanto.proto.ContextPlayer/Play";
    private static final String PLAYLIST_GET =
            "sp://esperanto/spotify.playlist_esperanto.proto.PlaylistDataService/Get";
    private static final String GET_ENTITY =
            "sp://esperanto/spotify.metadata_esperanto.proto.ClassicMetadataService/GetEntity";
    private static final String LIST = "spotify:playlist:list";
    private static final String ORIGINAL = "spotify:track:original";
    private static final String ROW = "row-original";
    private static final String TRACK_URL = "https://api.spotify.com/v1/tracks/original?market=from_token";
    private static final String TRYING = "refused row: " + ORIGINAL + " in " + LIST + " (NOT_IN_CATALOGUE), trying it";
    private static final String OMNI_TRYING =
            "refused row (omni): " + ORIGINAL + " in " + LIST + " (NOT_IN_CATALOGUE), trying it";
    /** The line the phone logged for each tap on a greyed-out song while omni play was on. */
    private static final String SKIPPED_TO_NOTHING =
            "GetError 22: reasons=null track=null context=null message=skip_to_non_existent_track_auto_stopped";
    private static final String PLAYING = "Playing an available version: Song";
    /** A wait for Spotify's answer that no test reaches, for the tests where Spotify answers. */
    private static final long LONG_WAIT = 60_000;
    /** A wait that ends at once, for the tests where Spotify never answers. */
    private static final long NO_WAIT = 1;

    private final Context context = RuntimeEnvironment.getApplication();
    private final Router router = new Router();
    private final FakeWebApi webApi = new FakeWebApi();
    /** The two streams Unavailable songs keeps open while it's on. */
    private Request states;
    private Request errors;

    @Before
    public void setUp() throws Exception {
        Extensions.setAppContext(context);
        // Off before the new router, since off forgets a tap an earlier test may have left.
        Extensions.setOn(context, Extensions.UNAVAILABLE_SONGS, false);
        context.getSharedPreferences(AvailableVersions.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        PlayerBridge.attach(router);
        // The status map outlives a test, so no test may pass on the line the last one left.
        Extensions.status(null, Extensions.UNAVAILABLE_SONGS, "not run");
        Extensions.setOn(context, Extensions.UNAVAILABLE_SONGS, true);
        states = router.next(GET_STATE);
        errors = router.next(GET_ERROR);
    }

    @After
    public void tearDown() {
        webApi.release();
        Extensions.setOn(context, Extensions.UNAVAILABLE_SONGS, false);
    }

    // ---- The tap (hook B1) ----

    @Test
    public void onlyASongGreyedOutAsNotInTheCatalogueOrForNoReasonGoesOnIntoSpotifysOwnPlay() {
        assertTrue(tap("NOT_IN_CATALOGUE", false));
        assertEquals(TRYING, status());
        assertTrue(tap("UNKNOWN", false));

        assertFalse("banned", tap("NOT_IN_CATALOGUE", true));
        assertEquals("refused row: " + ORIGINAL + " in " + LIST + " (NOT_IN_CATALOGUE, banned), left to Spotify",
                status());
        for (String other : Arrays.asList(
                "NO_RESTRICTION", "EXPLICIT_CONTENT", "AGE_RESTRICTED", "NOT_AVAILABLE_OFFLINE", "PREMIUM_ONLY")) {
            assertFalse(other, tap(other, false));
            assertEquals("refused row: " + ORIGINAL + " in " + LIST + " (" + other + "), left to Spotify", status());
        }
        assertFalse(tap(null, false));
        // A local file or an episode isn't a song that another version could stand in for (report 4).
        assertFalse(UnavailableSongs.onGreyedRowTap(LIST, "spotify:local:a:b:c:1", ROW, "UNKNOWN", false, webApi,
                LONG_WAIT));
        assertFalse(UnavailableSongs.onGreyedRowTap(LIST, "spotify:episode:e", ROW, "NOT_IN_CATALOGUE", false, webApi,
                LONG_WAIT));
        assertEquals("refused row: spotify:episode:e in " + LIST + " (NOT_IN_CATALOGUE), left to Spotify", status());

        Extensions.status(null, Extensions.UNAVAILABLE_SONGS, "off");
        Extensions.setOn(context, Extensions.UNAVAILABLE_SONGS, false);
        assertFalse("off, Spotify's refusal stands", tap("NOT_IN_CATALOGUE", false));
        assertEquals("and nothing is said", "off", status());
    }

    @Test
    public void aTapReturnsAtOnceWhileTheBridgeThreadIsBusy() throws Exception {
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch free = new CountDownLatch(1);
        // Busy for longer than the tap's minute below, unless the test frees it, as it always does last.
        PlayerBridge.post(() -> {
            busy.countDown();
            try {
                free.await(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        await(busy);
        try {
            // On a thread of its own, so a tap that waited for the bridge would fail after its minute.
            FutureTask<Boolean> tapping = new FutureTask<>(() -> tap("NOT_IN_CATALOGUE", false));
            new Thread(tapping).start();
            assertTrue(tapping.get(60, TimeUnit.SECONDS));
        } finally {
            free.countDown();
        }
    }

    @Test
    public void aTapSpotifyDidntTryWaitsForNothing() throws Exception {
        assertFalse(tap("PREMIUM_ONLY", false));

        errors.callback.onResponse(200, error(20, ORIGINAL, "not_available"));
        flushBridge();

        assertEquals("GetError 20: reasons=not_available track=" + ORIGINAL + " context=null", status());
        assertNothingLookedUp();
    }

    // ---- A tap on the omni play path (hook B2) ----

    @Test
    public void anOmniTapOnAGreyedRowThenCode22WithNoDataPlaysTheVersionInTheSongsPlace() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        omni(ORIGINAL, ROW, "NOT_IN_CATALOGUE", false, false, 10_000);
        assertEquals(OMNI_TRYING, status());

        // As on the phone: Spotify played the list without the song, so it had nothing to skip to.
        errors.callback.onResponse(200, skippedToNothing());

        Request listing = router.next(PLAYLIST_GET);
        assertEquals("Looking for an available version (GetError 22)", status());
        listing.callback.onResponse(200, page(2, "spotify:track:a", "row-a", ORIGINAL, ROW));
        Request play = router.next(PLAY);
        assertArrayEquals(Esperanto.playPage(LIST, tracks("spotify:track:a", "row-a", "spotify:track:found", ROW), ROW),
                play.body);
        play.callback.onResponse(200, new byte[0]);
        awaitStatus(PLAYING);
        awaitToast(PLAYING);
    }

    @Test
    public void anOmniTapOnAPlayableRowIsLeftToSpotify() throws Exception {
        // Most rows have no play state, which reads as playable with no restriction: UNKNOWN.
        omni(ORIGINAL, ROW, "UNKNOWN", false, true, 10_000);
        omni(ORIGINAL, "row-two", "NO_RESTRICTION", false, true, 10_000);
        assertEquals("nothing is logged", "not run", status());

        errors.callback.onResponse(200, skippedToNothing());
        assertNothingLookedUp();
        assertEquals("the phone's line, and nothing after it", SKIPPED_TO_NOTHING, status());
    }

    @Test
    public void anOmniTapOnABannedOrPremiumOnlyRowOrOnAnythingButASongIsOnlyLogged() throws Exception {
        String row = "refused row (omni): " + ORIGINAL + " in " + LIST + " (";
        omni(ORIGINAL, "row-banned", "NOT_IN_CATALOGUE", true, false, 10_000);
        assertEquals(row + "NOT_IN_CATALOGUE, banned), left to Spotify", status());
        // A banned song is greyed out even when it could play.
        omni(ORIGINAL, "row-banned-playable", "UNKNOWN", true, true, 10_000);
        assertEquals(row + "UNKNOWN, banned), left to Spotify", status());
        omni(ORIGINAL, "row-premium", "PREMIUM_ONLY", false, false, 10_000);
        assertEquals(row + "PREMIUM_ONLY), left to Spotify", status());
        omni("spotify:episode:e", "row-episode", "NOT_IN_CATALOGUE", false, false, 10_000);
        assertEquals("refused row (omni): spotify:episode:e in " + LIST + " (NOT_IN_CATALOGUE), left to Spotify",
                status());

        errors.callback.onResponse(200, skippedToNothing());
        assertNothingLookedUp();
    }

    @Test
    public void theSameOmniRowWithinTwoSecondsIsOneTap() throws Exception {
        omni(ORIGINAL, ROW, "NOT_IN_CATALOGUE", false, false, 10_000);
        states.callback.onResponse(200, state(ORIGINAL, ROW)); // that tap is over
        awaitStatus("Spotify played the original: " + ORIGINAL);

        omni(ORIGINAL, ROW, "NOT_IN_CATALOGUE", false, false, 11_999);
        assertEquals("the same tap isn't logged twice", "Spotify played the original: " + ORIGINAL, status());
        errors.callback.onResponse(200, skippedToNothing());
        assertNothingLookedUp(); // nor posted twice

        omni(ORIGINAL, ROW, "NOT_IN_CATALOGUE", false, false, 12_000);
        assertEquals("two seconds on, it's a new tap", OMNI_TRYING, status());
    }

    @Test
    public void anotherRowOrAnotherSongWithinTwoSecondsIsAnotherOmniTap() {
        omni(ORIGINAL, ROW, "NOT_IN_CATALOGUE", false, false, 10_000);
        Extensions.status(null, Extensions.UNAVAILABLE_SONGS, "first");

        omni(ORIGINAL, "row-again", "NOT_IN_CATALOGUE", false, false, 10_000);
        assertEquals("the song in another row", OMNI_TRYING, status());
        omni("spotify:track:other", "row-again", "NOT_IN_CATALOGUE", false, false, 10_000);
        assertEquals("another song", "refused row (omni): spotify:track:other in " + LIST
                + " (NOT_IN_CATALOGUE), trying it", status());
    }

    @Test
    public void offAnOmniTapIsLeftToSpotifyAndTheLastRowIsForgotten() {
        omni(ORIGINAL, ROW, "NOT_IN_CATALOGUE", false, false, 10_000);
        Extensions.setOn(context, Extensions.UNAVAILABLE_SONGS, false);
        Extensions.status(null, Extensions.UNAVAILABLE_SONGS, "off");
        omni(ORIGINAL, ROW, "NOT_IN_CATALOGUE", false, false, 20_000);
        assertEquals("off, nothing is logged", "off", status());

        Extensions.setOn(context, Extensions.UNAVAILABLE_SONGS, true);
        omni(ORIGINAL, ROW, "NOT_IN_CATALOGUE", false, false, 10_000);
        assertEquals("on again, the row is a new tap", OMNI_TRYING, status());
    }

    // ---- A list play the app let through (hook L) ----

    @Test
    public void aListPlayThenCode22ForAGreyedRowFindsItThenPlaysAVersionInItsPlace() throws Exception {
        new File(context.getFilesDir(), "spicetify_extensions.log").delete();
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        webApi.hold();
        // As on the phone: the app found the row playable, played the list from it, and the core refused.
        listPlay(ROW);
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_999));
        errors.callback.onResponse(200, skippedToNothing());

        // The listing with the songs that can't play, from the top, which stops at the row.
        Request finding = router.next(PLAYLIST_GET);
        assertArrayEquals(Esperanto.playlistPlayability(LIST, 0, 500), finding.body);
        finding.callback.onResponse(200, listing(3, row("spotify:track:a", "row-a", true, 1, false),
                row(ORIGINAL, ROW, false, 4, false)));
        webApi.awaitAsked();
        assertEquals("Looking for an available version (GetError 22)", status());
        awaitLogged("refused row (list play): " + ORIGINAL + " in " + LIST + " (NOT_IN_CATALOGUE), trying it");
        // The core refused the song, so a state that still names it isn't playback.
        states.callback.onResponse(200, state(ORIGINAL, ROW));
        flushBridge();
        webApi.release();

        // Then the tap's own read of the list, from the top again, after the lookup found the version.
        Request read = router.next(PLAYLIST_GET);
        assertArrayEquals("the finding read no further", Esperanto.playlistPlayability(LIST, 0, 500), read.body);
        read.callback.onResponse(200, page(3, "spotify:track:a", "row-a", ORIGINAL, ROW));
        router.next(PLAYLIST_GET).callback.onResponse(200, page(3, "spotify:track:c", "row-c"));

        Request play = router.next(PLAY);
        assertArrayEquals(Esperanto.playPage(LIST,
                tracks("spotify:track:a", "row-a", "spotify:track:found", ROW, "spotify:track:c", "row-c"), ROW),
                play.body);
        play.callback.onResponse(200, new byte[0]);
        awaitStatus(PLAYING);
        awaitToast(PLAYING);
        assertEquals(Arrays.asList(TRACK_URL), webApi.urls);
    }

    @Test
    public void aListPlayThenCode22ForARowAnotherVersionCantStandInForIsLeftToSpotifySayingWhy() throws Exception {
        String line = "refused row (list play): ";
        // Most rows have no play state, which reads as playable with no restriction: UNKNOWN.
        refusedListPlay(row(ORIGINAL, ROW, true, 0, false));
        assertEquals(line + ORIGINAL + " in " + LIST + " (UNKNOWN, is_playable=true, is_local=false), "
                + "left to Spotify", status());
        refusedListPlay(row(ORIGINAL, ROW, false, 6, false));
        assertEquals(line + ORIGINAL + " in " + LIST + " (PREMIUM_ONLY, is_playable=false, is_local=false), "
                + "left to Spotify", status());
        refusedListPlay(row(ORIGINAL, ROW, false, 0, true));
        assertEquals(line + ORIGINAL + " in " + LIST + " (UNKNOWN, is_playable=false, is_local=true), "
                + "left to Spotify", status());
        refusedListPlay(row("spotify:episode:e", ROW, false, 4, false));
        assertEquals(line + "spotify:episode:e in " + LIST + " (NOT_IN_CATALOGUE, is_playable=false, is_local=false), "
                + "left to Spotify", status());

        refusedListPlay(row("spotify:track:a", "row-a", false, 4, false));
        assertEquals("a listing without the row", line + "row " + ROW + " isn't in " + LIST + ", left to Spotify",
                status());
        listPlay(ROW);
        errors.callback.onResponse(200, skippedToNothing());
        router.next(PLAYLIST_GET).callback.onError(new IllegalStateException("no list"));
        awaitStatus(line + "couldn't read " + LIST + ": java.lang.IllegalStateException: no list, left to Spotify");

        assertNothingLookedUp();
        assertEquals(0, router.count(PLAY));
    }

    @Test
    public void code22ThreeSecondsAfterAListPlayOrAfterAListPlayWithoutARowLooksForNothing() throws Exception {
        listPlay(ROW);
        ShadowSystemClock.advanceBy(Duration.ofMillis(3_000));
        errors.callback.onResponse(200, skippedToNothing());
        flushBridge();
        assertEquals("the phone's line, and nothing after it", SKIPPED_TO_NOTHING, status());

        // A list play by song, as some of the app's other places send, names no row.
        listPlay(null);
        errors.callback.onResponse(200, skippedToNothing());
        flushBridge();

        assertEquals(0, router.count(PLAYLIST_GET));
        assertNothingLookedUp();
    }

    @Test
    public void onlyCode22FollowsAListPlay() throws Exception {
        listPlay(ROW);
        for (int code : new int[] {4, 19, 20, 21}) errors.callback.onResponse(200, error(code, null, "not_available"));
        flushBridge();
        assertEquals(0, router.count(PLAYLIST_GET));

        errors.callback.onResponse(200, skippedToNothing());
        Request finding = router.next(PLAYLIST_GET);
        assertArrayEquals("the list play was still there for it", Esperanto.playlistPlayability(LIST, 0, 500),
                finding.body);
        finding.callback.onResponse(200, listing(0));
        awaitStatus("refused row (list play): row " + ROW + " isn't in " + LIST + ", left to Spotify");
    }

    @Test
    public void aListingStillLoadingIsAskedAgainASecondLater() throws Exception {
        webApi.answer(TRACK_URL, "{\"uri\":\"" + ORIGINAL + "\",\"is_playable\":false}"); // no version
        listPlay(ROW);
        errors.callback.onResponse(200, skippedToNothing());
        Wire.Writer data = new Wire.Writer();
        data.bool(6, true); // loading_contents, and no items yet
        Wire.Writer status = new Wire.Writer();
        status.varint(1, 200);
        Wire.Writer loading = new Wire.Writer();
        loading.message(1, status);
        loading.message(2, data);
        router.next(PLAYLIST_GET).callback.onResponse(200, loading.toByteArray());

        Request again = router.next(PLAYLIST_GET);
        assertArrayEquals(Esperanto.playlistPlayability(LIST, 0, 500), again.body);
        again.callback.onResponse(200, listing(1, row(ORIGINAL, ROW, false, 4, false)));
        awaitStatus(AvailableVersions.NONE);
    }

    @Test
    public void inLikedSongsTheRowTurnsUpOnTheListingsSecondPage() throws Exception {
        webApi.answer(TRACK_URL, "{\"uri\":\"" + ORIGINAL + "\",\"is_playable\":false}"); // no version
        UnavailableSongs.onListPlay("spotify:user:me:collection", ROW, webApi);
        errors.callback.onResponse(200, skippedToNothing());

        Request first = router.next(PLAYLIST_GET);
        assertArrayEquals(Esperanto.playlistPlayability(Esperanto.LIKED_SONGS, 0, 500), first.body);
        first.callback.onResponse(200, listing(3, row("spotify:track:a", "row-a", true, 1, false),
                row("spotify:track:b", "row-b", false, 4, false)));
        Request second = router.next(PLAYLIST_GET);
        assertArrayEquals(Esperanto.playlistPlayability(Esperanto.LIKED_SONGS, 2, 500), second.body);
        second.callback.onResponse(200, listing(3, row(ORIGINAL, ROW, false, 0, false)));

        awaitStatus(AvailableVersions.NONE);
        assertEquals("the lookup was for the row's song", Arrays.asList(TRACK_URL), webApi.urls);
    }

    @Test
    public void whenB1AlsoFiresItsTapLooksOnceAndItsListPlayStartsNothing() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        // B1 lets the tap go on, and the app's list play from the same row follows.
        assertTrue(tap("NOT_IN_CATALOGUE", false, LONG_WAIT));
        listPlay(ROW);
        errors.callback.onResponse(200, skippedToNothing());

        router.next(PLAYLIST_GET).callback.onResponse(200, page(1, ORIGINAL, ROW)); // the tap's read
        router.next(PLAY).callback.onResponse(200, new byte[0]);
        awaitStatus(PLAYING);
        // Even a code 22 once the tap is over finds no list play to follow.
        errors.callback.onResponse(200, skippedToNothing());
        flushBridge();

        assertEquals(1, router.count(PLAYLIST_GET));
        assertEquals(1, router.count(PLAY));
        assertEquals(Arrays.asList(TRACK_URL), webApi.urls);
    }

    @Test
    public void whileATapIsPendingCode22IsItsAndAListPlayFromAnotherRowStartsNothing() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        webApi.hold();
        omni(ORIGINAL, ROW, "NOT_IN_CATALOGUE", false, false, 10_000);
        listPlay("row-other");
        errors.callback.onResponse(200, skippedToNothing());

        webApi.awaitAsked();
        flushBridge();
        assertEquals("Looking for an available version (GetError 22)", status());
        assertEquals("only the omni tap's read, after its lookup, would list", 0, router.count(PLAYLIST_GET));
    }

    @Test
    public void aListPlayIsFollowedOnceAndATapOrListPlayAfterItTakesOver() throws Exception {
        listPlay(ROW);
        errors.callback.onResponse(200, skippedToNothing());
        Request finding = router.next(PLAYLIST_GET);
        errors.callback.onResponse(200, skippedToNothing()); // the list play is followed already
        flushBridge();
        assertEquals(1, router.count(PLAYLIST_GET));
        listPlay("row-newer"); // the user played on before the listing came
        finding.callback.onResponse(200, listing(1, row(ORIGINAL, ROW, false, 4, false)));
        flushBridge();
        assertEquals(SKIPPED_TO_NOTHING, status());

        listPlay(ROW);
        errors.callback.onResponse(200, skippedToNothing());
        finding = router.next(PLAYLIST_GET);
        omni("spotify:track:other", "row-tapped", "NOT_IN_CATALOGUE", false, false, 10_000); // waits a minute
        finding.callback.onResponse(200, listing(1, row(ORIGINAL, ROW, false, 4, false)));
        flushBridge();
        assertEquals("refused row (omni): spotify:track:other in " + LIST + " (NOT_IN_CATALOGUE), trying it", status());
        assertNothingLookedUp();
    }

    @Test
    public void offForgetsTheLastListPlayAndAListPlayWhileOffIsntKept() throws Exception {
        listPlay(ROW);
        Extensions.setOn(context, Extensions.UNAVAILABLE_SONGS, false);
        Extensions.setOn(context, Extensions.UNAVAILABLE_SONGS, true);
        router.next(GET_ERROR).callback.onResponse(200, skippedToNothing());
        flushBridge();
        assertEquals(0, router.count(PLAYLIST_GET));

        Extensions.setOn(context, Extensions.UNAVAILABLE_SONGS, false);
        listPlay(ROW);
        Extensions.setOn(context, Extensions.UNAVAILABLE_SONGS, true);
        router.next(GET_ERROR).callback.onResponse(200, skippedToNothing());
        flushBridge();
        assertEquals(0, router.count(PLAYLIST_GET));
    }

    @Test
    public void aBridgeFailureGoesOnTheStatusLineOnlyWhileOn() {
        UnavailableSongs.bridgeFailed("onListPlay", new ClassCastException("p.vy70"));
        assertEquals("bridge failed at onListPlay: java.lang.ClassCastException: p.vy70", status());

        Extensions.setOn(context, Extensions.UNAVAILABLE_SONGS, false);
        Extensions.status(null, Extensions.UNAVAILABLE_SONGS, "off");
        UnavailableSongs.bridgeFailed("onRefusedRow", new NullPointerException());
        assertEquals("off", status());
    }

    // ---- Spotify's answer ----

    @Test
    public void whenSpotifyPlaysTheOriginalNoOtherVersionIsLookedFor() throws Exception {
        assertTrue(tap("NOT_IN_CATALOGUE", false, LONG_WAIT));
        states.callback.onResponse(200, state("spotify:track:before", "row-before")); // not the song yet

        states.callback.onResponse(200, state(ORIGINAL, ROW));
        awaitStatus("Spotify played the original: " + ORIGINAL);

        errors.callback.onResponse(200, error(20, ORIGINAL, "not_available"));
        flushBridge();
        assertEquals("GetError 20: reasons=not_available track=" + ORIGINAL + " context=null", status());
        assertNothingLookedUp();
    }

    @Test
    public void theTappedRowPlayingCountsAsTheOriginalEvenAsAnotherInstanceOfTheSong() throws Exception {
        assertTrue(tap("NOT_IN_CATALOGUE", false, LONG_WAIT));

        // Spotify relinks: the row plays another instance of the song, under the row's uid.
        states.callback.onResponse(200, state("spotify:track:relinked", ROW));

        awaitStatus("Spotify played the original: spotify:track:relinked");
        assertNothingLookedUp();
    }

    @Test
    public void theOriginalPlayingUnderAnotherUidIsKnownByItsUri() throws Exception {
        assertTrue(tap("NOT_IN_CATALOGUE", false, LONG_WAIT));

        // The player may use a uid other than the row id the list showed, as Liked Songs' listing does.
        states.callback.onResponse(200, state(ORIGINAL, "row-elsewhere"));

        awaitStatus("Spotify played the original: " + ORIGINAL);
        assertNothingLookedUp();
    }

    @Test
    public void aStateStoppedOrPausedOnTheSongBeforeItsRefusalIsntTheOriginalPlaying() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        assertTrue(tap("NOT_IN_CATALOGUE", false, LONG_WAIT));

        // The player may stop on the song, or pause on it, before its GetError comes.
        states.callback.onResponse(200, state(ORIGINAL, ROW, false, false));
        states.callback.onResponse(200, state(ORIGINAL, ROW, true, true));
        states.callback.onResponse(200, new byte[0]); // an empty state, which names nothing
        flushBridge();
        assertEquals("none of them is the original playing", TRYING, status());

        errors.callback.onResponse(200, error(20, ORIGINAL, "not_available"));
        router.next(PLAYLIST_GET);
        assertEquals("Looking for an available version (GetError 20)", status());
    }

    @Test
    public void aRefusalForAnotherReasonLooksForNothing() throws Exception {
        assertTrue(tap("NOT_IN_CATALOGUE", false, LONG_WAIT));

        errors.callback.onResponse(200, error(20, ORIGINAL, "user_capping_reached"));

        awaitStatus("Spotify refused it for another reason: user_capping_reached");
        flushBridge();
        assertNothingLookedUp();
    }

    @Test
    public void aNonRegionReasonThatStartsLikeARegionOneLooksForNothing() throws Exception {
        for (String reason : Arrays.asList("not_available_offline", "not_available_in_non_premium",
                "not_available_by_artist_ban")) {
            assertTrue(tap("NOT_IN_CATALOGUE", false, LONG_WAIT));
            errors.callback.onResponse(200, error(20, ORIGINAL, reason));
            awaitStatus("Spotify refused it for another reason: " + reason);
        }
        assertNothingLookedUp();
    }

    @Test
    public void onlyAnUnplayableCodeForTheSongLooksAndCode22NeedsNoReason() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        assertTrue(tap("NOT_IN_CATALOGUE", false, LONG_WAIT));

        errors.callback.onResponse(200, error(20, "spotify:track:other", "not_available")); // another song
        errors.callback.onResponse(200, error(4, ORIGINAL, "not_available")); // PLAY_RESTRICTED
        errors.callback.onResponse(200, error(23, ORIGINAL, "not_available"));
        errors.callback.onResponse(200, error(18, ORIGINAL, "not_available"));
        flushBridge();
        assertEquals("GetError 18: reasons=not_available track=" + ORIGINAL + " context=null", status());
        assertNothingLookedUp();

        // SKIP_TO_NON_EXISTENT_TRACK_AUTO_STOPPED: the list played without the greyed-out song.
        errors.callback.onResponse(200, error(22, null, null));

        router.next(PLAYLIST_GET);
        assertEquals("Looking for an available version (GetError 22)", status());
    }

    @Test
    public void eachUnplayableCodeWithARegionReasonLooks() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        int[] codes = {19, 20, 21};
        String[] reasons = {"not_available", "not_available_in_current_region", "not_available"};
        for (int i = 0; i < codes.length; i++) {
            assertTrue(tap("NOT_IN_CATALOGUE", false, LONG_WAIT));
            errors.callback.onResponse(200, error(codes[i], ORIGINAL, reasons[i]));
            router.next(PLAYLIST_GET); // the lookup found the version, and the list is being read
            assertEquals("Looking for an available version (GetError " + codes[i] + ")", status());
        }
    }

    @Test
    public void aRegionReasonAmongSeveralLooks() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        // Code 21 is for a whole list, and the app reads its reasons as several.
        for (String reasons : Arrays.asList("not_available,not_available",
                "not_available_offline, not_available_in_current_region ",
                "not_available; not_available")) {
            assertTrue(tap("NOT_IN_CATALOGUE", false, LONG_WAIT));
            errors.callback.onResponse(200, error(21, ORIGINAL, reasons));
            router.next(PLAYLIST_GET);
            assertEquals("Looking for an available version (GetError 21)", status());
        }
    }

    // ---- The version that plays ----

    @Test
    public void aRegionRefusalPlaysAVersionThatPlaysHereInTheSongsPlaceInTheList() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        assertTrue(tap("NOT_IN_CATALOGUE", false, LONG_WAIT));

        errors.callback.onResponse(200, error(20, ORIGINAL, "not_available_in_current_region"));

        // The whole list, a page at a time, the greyed-out songs too.
        Request first = router.next(PLAYLIST_GET);
        assertEquals("Looking for an available version (GetError 20)", status());
        assertArrayEquals(Esperanto.playlistPlayability(LIST, 0, 500), first.body);
        first.callback.onResponse(200, page(3, "spotify:track:a", "row-a", ORIGINAL, ROW));
        Request second = router.next(PLAYLIST_GET);
        assertArrayEquals(Esperanto.playlistPlayability(LIST, 2, 500), second.body);
        second.callback.onResponse(200, page(3, "spotify:track:c", "row-c"));

        Request play = router.next(PLAY);
        assertArrayEquals(Esperanto.playPage(LIST,
                tracks("spotify:track:a", "row-a", "spotify:track:found", ROW, "spotify:track:c", "row-c"), ROW),
                play.body);
        play.callback.onResponse(200, new byte[0]);

        awaitStatus(PLAYING);
        awaitToast(PLAYING);
        assertEquals(Arrays.asList(TRACK_URL), webApi.urls);
    }

    @Test
    public void inLikedSongsTheVersionTakesTheSongsPlaceFoundByItsUriWhenTheRowIdsDiffer() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        assertTrue(UnavailableSongs.onGreyedRowTap("spotify:user:me:collection", ORIGINAL, ROW, "NOT_IN_CATALOGUE",
                false, webApi, NO_WAIT));

        Request listing = router.next(PLAYLIST_GET);
        assertArrayEquals(Esperanto.playlistPlayability(Esperanto.LIKED_SONGS, 0, 500), listing.body);
        listing.callback.onResponse(200, page(2, "spotify:track:a", "row-a", ORIGINAL, "row-listed"));

        assertArrayEquals(Esperanto.playPage(Esperanto.LIKED_SONGS,
                tracks("spotify:track:a", "row-a", "spotify:track:found", "row-listed"), "row-listed"),
                router.next(PLAY).body);
    }

    @Test
    public void withNoAnswerFromSpotifyItLooksAnywayAndPlaysTheVersionAloneWhenTheListLacksTheSong() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        assertTrue(tap("NOT_IN_CATALOGUE", false, NO_WAIT));

        Request listing = router.next(PLAYLIST_GET);
        assertEquals("Looking for an available version (no answer within 1 ms)", status());
        listing.callback.onResponse(200, page(1, "spotify:track:a", "row-a"));

        Request play = router.next(PLAY);
        assertArrayEquals(Esperanto.playContext("spotify:track:found", null), play.body);
        assertEquals("the log says why it plays alone", "Playing spotify:track:found alone: the song isn't in " + LIST,
                status());
        play.callback.onResponse(200, new byte[0]);
        awaitStatus(PLAYING);
    }

    @Test
    public void aSongOutsideAPlaylistPlaysItsVersionAloneWithoutListingAnything() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        assertTrue(UnavailableSongs.onGreyedRowTap("spotify:album:x", ORIGINAL, ROW, "NOT_IN_CATALOGUE", false,
                webApi, NO_WAIT));

        Request play = router.next(PLAY);
        assertArrayEquals(Esperanto.playContext("spotify:track:found", null), play.body);
        assertEquals("Playing spotify:track:found alone: not in a playlist", status());
        play.callback.onResponse(200, new byte[0]);
        awaitStatus(PLAYING);
        assertEquals(0, router.count(PLAYLIST_GET));
    }

    @Test
    public void aListThatCantBeReadOrAPlayInItThatSpotifyRefusesFallsBackToTheVersionAlone() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        assertTrue(tap("NOT_IN_CATALOGUE", false, NO_WAIT));
        router.next(PLAYLIST_GET).callback.onError(new IllegalStateException("no list"));
        Request alone = router.next(PLAY);
        assertArrayEquals(Esperanto.playContext("spotify:track:found", null), alone.body);
        assertEquals("Playing spotify:track:found alone: couldn't read " + LIST
                + ": java.lang.IllegalStateException: no list", status());
        alone.callback.onResponse(200, new byte[0]);
        awaitStatus(PLAYING);

        Extensions.status(null, Extensions.UNAVAILABLE_SONGS, "again");
        assertTrue(tap("NOT_IN_CATALOGUE", false, NO_WAIT)); // the version is cached now
        router.next(PLAYLIST_GET).callback.onResponse(200, page(1, ORIGINAL, ROW));
        router.next(PLAY).callback.onResponse(200, forbidden());
        Request retried = router.next(PLAY);
        assertArrayEquals(Esperanto.playContext("spotify:track:found", null), retried.body);
        assertEquals("Playing spotify:track:found alone: Spotify refused it in the list: Spotify answered error 1",
                status());
        retried.callback.onResponse(200, new byte[0]);
        awaitStatus(PLAYING);
    }

    @Test
    public void whenSpotifyRefusesTheVersionAloneTooTheTapSaysSo() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        assertTrue(UnavailableSongs.onGreyedRowTap("spotify:album:x", ORIGINAL, ROW, "NOT_IN_CATALOGUE", false,
                webApi, NO_WAIT));

        router.next(PLAY).callback.onResponse(200, forbidden());

        String line = "Couldn't play the available version: Spotify answered error 1";
        awaitStatus(line);
        awaitToast(line);
    }

    @Test
    public void withNoVersionInTheCountryTheTapSaysSoAndPlaysNothing() throws Exception {
        // No ISRC and no artist to search by, so the one request answers.
        webApi.answer(TRACK_URL, "{\"uri\":\"" + ORIGINAL + "\",\"is_playable\":false}");
        assertTrue(tap("NOT_IN_CATALOGUE", false, NO_WAIT));

        awaitStatus(AvailableVersions.NONE);
        awaitToast(AvailableVersions.NONE);
        flushBridge();
        assertEquals(0, router.count(PLAY));
    }

    @Test
    public void thePhonesListPlayOfASongMissingFromTheMarketPlaysTheCoresAlternativeThatPlaysHereInItsPlace()
            throws Exception {
        // As on the phone: Get Track answers 404 for a song that isn't in the catalog of the user's market.
        String id = Esperanto.base62(RandomSongTest.gid(1));
        webApi.answer(TRACK_URL, new WebApi.NotFound());
        webApi.answer("https://api.spotify.com/v1/tracks/" + id + "?market=from_token", relinked(id, "Song"));
        listPlay(ROW);
        errors.callback.onResponse(200, skippedToNothing());
        router.next(PLAYLIST_GET).callback.onResponse(200, listing(2, row("spotify:track:a", "row-a", true, 1, false),
                row(ORIGINAL, ROW, false, 4, false)));

        Request details = router.next(GET_ENTITY);
        assertArrayEquals(Esperanto.getEntity(ORIGINAL), details.body);
        details.callback.onResponse(200, AvailableVersionsTest.described("Song", false, null, RandomSongTest.gid(1)));

        router.next(PLAYLIST_GET).callback.onResponse(200, page(2, "spotify:track:a", "row-a", ORIGINAL, ROW));
        Request play = router.next(PLAY);
        assertArrayEquals(Esperanto.playPage(LIST, tracks("spotify:track:a", "row-a", "spotify:track:" + id, ROW), ROW),
                play.body);
        play.callback.onResponse(200, new byte[0]);
        awaitStatus(PLAYING);
        awaitToast(PLAYING);
        awaitLogged("alternatives 1, spotify:track:" + id + " plays here");
    }

    @Test
    public void aSongMissingFromTheMarketWithNoVersionHereSaysSoWithoutAnHttpCode() throws Exception {
        webApi.answer(TRACK_URL, new WebApi.NotFound());
        assertTrue(tap("NOT_IN_CATALOGUE", false, NO_WAIT));

        // No alternative, no ISRC and no title to search by, so the core's answer ends the lookup.
        router.next(GET_ENTITY).callback.onResponse(200, AvailableVersionsTest.described("", false, null));

        awaitStatus(AvailableVersions.NONE);
        awaitToast(AvailableVersions.NONE);
        flushBridge();
        assertEquals(0, router.count(PLAY));
        assertEquals(Arrays.asList(TRACK_URL), webApi.urls);
    }

    // ---- Races ----

    @Test
    public void theOriginalPlayingDuringTheLookupKeepsPlaying() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        webApi.hold();
        assertTrue(tap("NOT_IN_CATALOGUE", false, NO_WAIT));
        webApi.awaitAsked();

        states.callback.onResponse(200, state(ORIGINAL, ROW));
        awaitStatus("Spotify played the original: " + ORIGINAL);
        webApi.release();

        awaitWebApi();
        flushBridge();
        assertEquals(0, router.count(PLAYLIST_GET));
        assertEquals(0, router.count(PLAY));
        assertEquals("Spotify played the original: " + ORIGINAL, status());
    }

    @Test
    public void aStateForTheSongAfterARegionRefusalDoesntStopTheVersion() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        webApi.hold();
        assertTrue(UnavailableSongs.onGreyedRowTap("spotify:album:x", ORIGINAL, ROW, "NOT_IN_CATALOGUE", false,
                webApi, LONG_WAIT));
        errors.callback.onResponse(200, error(20, ORIGINAL, "not_available"));
        webApi.awaitAsked();

        // Spotify refused the song, so a state that still names it, even as playing, isn't playback.
        states.callback.onResponse(200, state(ORIGINAL, ROW));
        flushBridge();
        webApi.release();

        awaitWebApi();
        flushBridge();
        assertEquals("the version plays after Spotify's region refusal, last status " + status(), 1,
                router.count(PLAY));
    }

    @Test
    public void aRegionRefusalAfterTheWaitStartedTheLookupStillOutranksALaterState() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        webApi.hold();
        assertTrue(UnavailableSongs.onGreyedRowTap("spotify:album:x", ORIGINAL, ROW, "NOT_IN_CATALOGUE", false,
                webApi, NO_WAIT));
        webApi.awaitAsked(); // the wait ended, and the lookup started

        errors.callback.onResponse(200, error(20, ORIGINAL, "not_available"));
        states.callback.onResponse(200, state(ORIGINAL, ROW));
        flushBridge();
        webApi.release();

        awaitWebApi();
        flushBridge();
        assertEquals("the version plays after Spotify's region refusal, last status " + status(), 1,
                router.count(PLAY));
    }

    @Test
    public void aNewTapTakesOverFromOneStillLookingAndTheFirstPlaysNothing() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        webApi.hold();
        // Outside a playlist, where the first tap's version would play alone as soon as it's found.
        assertTrue(UnavailableSongs.onGreyedRowTap("spotify:album:x", ORIGINAL, ROW, "NOT_IN_CATALOGUE", false,
                webApi, NO_WAIT));
        webApi.awaitAsked();

        assertTrue(tap("NOT_IN_CATALOGUE", false, LONG_WAIT));
        flushBridge();
        webApi.release();

        awaitWebApi();
        flushBridge();
        assertEquals(0, router.count(PLAY));
        assertEquals(0, router.count(PLAYLIST_GET));
        assertEquals(TRYING, status());
    }

    @Test
    public void ourOwnVersionPlayingUnderTheRowsUidIsntTakenForTheOriginal() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        assertTrue(tap("NOT_IN_CATALOGUE", false, NO_WAIT));
        router.next(PLAYLIST_GET).callback.onResponse(200, page(1, ORIGINAL, ROW));
        Request play = router.next(PLAY);

        // The version plays under the row's uid before Spotify answers the Play.
        states.callback.onResponse(200, state("spotify:track:found", ROW));
        play.callback.onResponse(200, new byte[0]);

        awaitStatus(PLAYING);
    }

    // ---- The switch ----

    @Test
    public void offClosesTheErrorStreamAndForgetsATap() throws Exception {
        webApi.answer(TRACK_URL, relinked("found", "Song"));
        assertTrue(tap("NOT_IN_CATALOGUE", false, LONG_WAIT));

        Extensions.setOn(context, Extensions.UNAVAILABLE_SONGS, false);
        assertTrue(errors.cancelled);

        Extensions.setOn(context, Extensions.UNAVAILABLE_SONGS, true);
        Request reopened = router.next(GET_ERROR);
        reopened.callback.onResponse(200, error(20, ORIGINAL, "not_available"));
        flushBridge();
        assertEquals("GetError 20: reasons=not_available track=" + ORIGINAL + " context=null", status());
        assertNothingLookedUp();
    }

    // ---- Helpers ----

    private boolean tap(String restriction, boolean banned) {
        return tap(restriction, banned, LONG_WAIT);
    }

    /** Hook B1's call for the row {@link #ORIGINAL} in {@link #LIST}, waiting {@code waitMillis} for Spotify. */
    private boolean tap(String restriction, boolean banned, long waitMillis) {
        return UnavailableSongs.onGreyedRowTap(LIST, ORIGINAL, ROW, restriction, banned, webApi, waitMillis);
    }

    /** Hook B2's call for a row of {@link #LIST}, made at {@code now}, giving Spotify a minute to answer. */
    private void omni(String trackUri, String rowId, String restriction, boolean banned, boolean playable, long now) {
        UnavailableSongs.onOmniRow(LIST, trackUri, rowId, restriction, banned, playable, webApi, LONG_WAIT, now);
    }

    /** Hook L's call for a list play of {@link #LIST} that skips to {@code rowId}, or to a song for null. */
    private void listPlay(String rowId) {
        UnavailableSongs.onListPlay(LIST, rowId, webApi);
    }

    /** A list play from {@link #ROW}, the core's code 22, then a listing of {@code row} alone, all handled. */
    private void refusedListPlay(Wire.Writer row) throws Exception {
        listPlay(ROW);
        errors.callback.onResponse(200, skippedToNothing());
        router.next(PLAYLIST_GET).callback.onResponse(200, listing(1, row));
        flushBridge();
    }

    private static String status() {
        return Extensions.latestStatus(Extensions.UNAVAILABLE_SONGS);
    }

    /** Waits for Unavailable songs' {@code line} in the extensions log, which keeps the lines before the latest. */
    static void awaitLogged(String line) throws Exception {
        File log = new File(RuntimeEnvironment.getApplication().getFilesDir(), "spicetify_extensions.log");
        String entry = " " + Extensions.UNAVAILABLE_SONGS + ": " + line + "\n";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!log.exists() || !new String(Files.readAllBytes(log.toPath()), UTF_8).contains(entry)) {
            assertTrue("no log line \"" + line + "\" within 60 s", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    /** No token was asked for and the Web API heard nothing, so no lookup started. */
    private void assertNothingLookedUp() throws Exception {
        flushBridge();
        assertEquals(0, router.count(WebApi.TOKEN_URI));
        assertTrue(webApi.urls.toString(), webApi.urls.isEmpty());
    }

    /**
     * Returns once the bridge thread has run what was posted before, and what that posted in turn:
     * it runs tasks in the order they were posted.
     */
    private static void flushBridge() throws Exception {
        FutureTask<Void> probe = new FutureTask<>(() -> null);
        PlayerBridge.post(() -> PlayerBridge.post(probe));
        probe.get(60, TimeUnit.SECONDS);
    }

    /** Returns once the Web API thread has run what was queued there before. */
    private static void awaitWebApi() throws Exception {
        FutureTask<Void> probe = new FutureTask<>(() -> null);
        WebApi.run(probe);
        probe.get(60, TimeUnit.SECONDS);
    }

    private static void awaitStatus(String line) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!line.equals(status())) {
            assertTrue("no status \"" + line + "\" within 60 s, the last was " + status(),
                    System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    /** Runs the main looper, where the Toasts are posted, until one reads {@code text}. */
    private static void awaitToast(String text) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            shadowOf(Looper.getMainLooper()).idle();
            if (text.equals(ShadowToast.getTextOfLatestToast())) return;
            assertTrue("no Toast \"" + text + "\" within 60 s, the last was " + ShadowToast.getTextOfLatestToast(),
                    System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue("no signal within 60 s", latch.await(60, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** A Web API track that plays here under another uri: the relinked version of the original. */
    private static String relinked(String id, String name) {
        return "{\"uri\":\"spotify:track:" + id + "\",\"name\":\"" + name + "\",\"is_playable\":true}";
    }

    /** A {@code ContextPlayerState} playing {@code trackUri} with {@code uid} in {@link #LIST}, not paused. */
    private static byte[] state(String trackUri, String uid) {
        return state(trackUri, uid, true, false);
    }

    /** The same with {@code is_playing} and {@code is_paused} as given, false for a stopped player. */
    private static byte[] state(String trackUri, String uid, boolean playing, boolean paused) {
        Wire.Writer track = new Wire.Writer();
        track.string(1, trackUri);
        track.string(2, uid);
        Wire.Writer provided = new Wire.Writer();
        provided.message(1, track);
        Wire.Writer state = new Wire.Writer();
        state.string(2, LIST);
        state.message(7, provided);
        state.bool(13, playing);
        state.bool(14, paused);
        return state.toByteArray();
    }

    /** A {@code ContextPlayerError} with {@code code}, and a track and reasons unless they're null. */
    private static byte[] error(int code, String trackUri, String reasons) {
        Wire.Writer error = new Wire.Writer();
        error.varint(1, code);
        if (trackUri != null) error.message(3, entry("track_uri", trackUri));
        if (reasons != null) error.message(3, entry("reasons", reasons));
        return error.toByteArray();
    }

    /** The phone's {@code ContextPlayerError} after an omni tap on a greyed-out song: code 22, its message, no data. */
    private static byte[] skippedToNothing() {
        Wire.Writer error = new Wire.Writer();
        error.varint(1, 22);
        error.string(2, "skip_to_non_existent_track_auto_stopped");
        return error.toByteArray();
    }

    private static Wire.Writer entry(String key, String value) {
        Wire.Writer entry = new Wire.Writer();
        entry.string(1, key);
        entry.string(2, value);
        return entry;
    }

    /** A {@code PlaylistGetResponse} with status 200, the list's {@code length} and one item per uri and row id. */
    private static byte[] page(int length, String... urisAndRowIds) {
        Wire.Writer data = new Wire.Writer();
        for (int i = 0; i < urisAndRowIds.length; i += 2) {
            Wire.Writer item = new Wire.Writer();
            item.string(7, urisAndRowIds[i + 1]);
            item.string(18, urisAndRowIds[i]);
            data.message(1, item);
        }
        data.varint(4, length);
        Wire.Writer status = new Wire.Writer();
        status.varint(1, 200);
        Wire.Writer response = new Wire.Writer();
        response.message(1, status);
        response.message(2, data);
        return response.toByteArray();
    }

    /**
     * A listed {@code Item{7 row_id, 18 uri, 4 track_metadata{11 is_local}, 8 track_play_state{1 is_playable,
     * 2 playability_restriction}}}, the restriction by number: 0 UNKNOWN, 1 NO_RESTRICTION, 4 NOT_IN_CATALOGUE,
     * 6 PREMIUM_ONLY.
     */
    private static Wire.Writer row(String uri, String rowId, boolean playable, int restriction, boolean local) {
        Wire.Writer metadata = new Wire.Writer();
        metadata.bool(11, local);
        Wire.Writer state = new Wire.Writer();
        state.bool(1, playable);
        state.varint(2, restriction);
        Wire.Writer item = new Wire.Writer();
        item.string(7, rowId);
        item.string(18, uri);
        item.message(4, metadata);
        item.message(8, state);
        return item;
    }

    /** A {@code PlaylistGetResponse} with status 200, the list's {@code length} and {@code rows}. */
    private static byte[] listing(int length, Wire.Writer... rows) {
        Wire.Writer data = new Wire.Writer();
        for (Wire.Writer row : rows) data.message(1, row);
        data.varint(4, length);
        Wire.Writer status = new Wire.Writer();
        status.varint(1, 200);
        Wire.Writer response = new Wire.Writer();
        response.message(1, status);
        response.message(2, data);
        return response.toByteArray();
    }

    private static List<Esperanto.ContextTrack> tracks(String... urisAndUids) {
        List<Esperanto.ContextTrack> tracks = new ArrayList<>();
        for (int i = 0; i < urisAndUids.length; i += 2) {
            Esperanto.ContextTrack track = new Esperanto.ContextTrack();
            track.uri = urisAndUids[i];
            track.uid = urisAndUids[i + 1];
            tracks.add(track);
        }
        return tracks;
    }

    /** {@code ResponseWithReasons{1 FORBIDDEN}}. */
    private static byte[] forbidden() {
        Wire.Writer response = new Wire.Writer();
        response.varint(1, Esperanto.FORBIDDEN);
        return response.toByteArray();
    }

    private static final class Request {
        final String uri;
        final byte[] body;
        final CosmosRouter.Callback callback;
        volatile boolean cancelled;

        Request(String uri, byte[] body, CosmosRouter.Callback callback) {
            this.uri = uri;
            this.body = body;
            this.callback = callback;
        }
    }

    /** Spotify's router: it answers each token request at once and keeps every other request for the test. */
    private static final class Router implements CosmosRouter {
        private final List<Request> sent = new CopyOnWriteArrayList<>();
        private final List<Request> unread = new ArrayList<>(); // guarded by this

        @Override
        public Cancel resolve(String action, String uri, byte[] body, Callback callback) {
            Request request = new Request(uri, body, callback);
            sent.add(request);
            if (WebApi.TOKEN_URI.equals(uri)) {
                callback.onResponse(200, "{\"accessToken\":\"token\",\"expiresIn\":3600,\"errorCode\":0}".getBytes(UTF_8));
            } else {
                synchronized (this) {
                    unread.add(request);
                    notifyAll();
                }
            }
            return () -> request.cancelled = true;
        }

        @Override
        public boolean destroyed() {
            return false;
        }

        /** The oldest request to {@code uri} not taken yet. */
        synchronized Request next(String uri) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (true) {
                for (Iterator<Request> requests = unread.iterator(); requests.hasNext(); ) {
                    Request request = requests.next();
                    if (request.uri.equals(uri)) {
                        requests.remove();
                        return request;
                    }
                }
                long left = deadline - System.nanoTime();
                assertTrue("no request to " + uri + " within 60 s", left > 0);
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
        }

        int count(String uri) {
            int count = 0;
            for (Request request : sent) {
                if (request.uri.equals(uri)) count++;
            }
            return count;
        }
    }

    /**
     * A stubbed Web API: each url answers its canned body, or throws its canned failure. While held, a
     * request waits for the release.
     */
    private static final class FakeWebApi implements WebApi.Http {
        final List<String> urls = new CopyOnWriteArrayList<>();
        private final Map<String, Object> answers = new ConcurrentHashMap<>();
        private final CountDownLatch asked = new CountDownLatch(1);
        private volatile CountDownLatch held;

        void answer(String url, Object bodyOrFailure) {
            answers.put(url, bodyOrFailure);
        }

        void hold() {
            held = new CountDownLatch(1);
        }

        void release() {
            CountDownLatch latch = held;
            if (latch != null) latch.countDown();
        }

        void awaitAsked() {
            await(asked);
        }

        @Override
        public String get(String url, String token) throws IOException {
            urls.add(url);
            asked.countDown();
            CountDownLatch latch = held;
            if (latch != null) await(latch);
            Object answer = answers.get(url);
            if (answer == null) throw new IOException("no canned answer for " + url);
            if (answer instanceof IOException) throw (IOException) answer;
            return answer.toString();
        }
    }
}
