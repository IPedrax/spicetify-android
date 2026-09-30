package app.spicetify.extension.spotify.extensions;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import app.spicetify.extension.spotify.extensions.RandomSongTest.FakeRequest;
import app.spicetify.extension.spotify.extensions.RandomSongTest.FakeRouter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class ExtensionMenusTest {
    private static final String PLAY = "sp://esperanto/spotify.player.esperanto.proto.ContextPlayer/Play";
    private static final String PLAYLIST_GET =
            "sp://esperanto/spotify.playlist_esperanto.proto.PlaylistDataService/Get";

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        Extensions.setAppContext(context);
        TrashBin.clear(context);
        // The status map outlives a test, so no test may pass on the line the last one left.
        Extensions.status(null, Extensions.SHUFFLE_PLUS, "not run");
    }

    @After
    public void tearDown() {
        Extensions.setOn(context, Extensions.TRASH_BIN, false);
        Extensions.setOn(context, Extensions.RANDOM_SONG, false);
        Extensions.setOn(context, Extensions.SHUFFLE_PLUS, false);
    }

    @Test
    public void aSongThatIsNotTrashedCanBeThrownToTrash() {
        Extensions.setOn(context, Extensions.TRASH_BIN, true);

        assertEquals(Collections.singletonList("trash_song|Throw song to trash|trash"),
                rows(ExtensionMenus.trackItems(new Track(new Metadata("spotify:track:a")))));
    }

    @Test
    public void aTrashedSongCanBeTakenOut() {
        Extensions.setOn(context, Extensions.TRASH_BIN, true);
        TrashBin.setSong(context, "spotify:track:a", true);

        assertEquals(Collections.singletonList("trash_song|Take song out of trash|trash"),
                rows(ExtensionMenus.trackItems(new Track(new Metadata("spotify:track:a")))));
    }

    @Test
    public void withEveryExtensionOffBothMenusGetNothing() {
        assertTrue(ExtensionMenus.trackItems(new Track(new Metadata("spotify:track:a"))).isEmpty());
        assertTrue(ExtensionMenus.artistItems(new Artist(new Metadata("spotify:artist:z"))).isEmpty());
    }

    @Test
    public void theSongMenuHasOnlyTrashBinsItemInEverySwitchState() {
        for (int switches = 0; switches < 8; switches++) {
            boolean trash = (switches & 1) != 0;
            Extensions.setOn(context, Extensions.TRASH_BIN, trash);
            Extensions.setOn(context, Extensions.RANDOM_SONG, (switches & 2) != 0);
            Extensions.setOn(context, Extensions.SHUFFLE_PLUS, (switches & 4) != 0);

            assertEquals("switches " + switches,
                    trash ? Collections.singletonList("trash_song|Throw song to trash|trash") : Collections.emptyList(),
                    rows(ExtensionMenus.trackItems(new Track(new Metadata("spotify:track:a")))));
        }
    }

    @Test
    public void withoutAUriThereIsNoTrashItemAndTheClickDoesNothing() throws Exception {
        Extensions.setOn(context, Extensions.TRASH_BIN, true);
        Object[] noUri = {new Object(), new Track(null), new Track(new Metadata(""))};

        for (Object track : noUri) {
            assertTrue(ExtensionMenus.trackItems(track).isEmpty());
            ExtensionMenus.onTrackItem("trash_song", track);
        }
        assertTrue(ExtensionMenus.artistItems(new Object()).isEmpty());
        ExtensionMenus.onArtistItem("trash_artist", new Artist(new Metadata("")));

        JSONObject trash = new JSONObject(TrashBin.exportJson());
        assertEquals("no song was trashed", 0, trash.getJSONObject("songs").length());
        assertEquals("no artist was trashed", 0, trash.getJSONObject("artists").length());
        // artistItems(new Object()) was the last reflection failure, and Trash Bin is still on.
        assertTrue("status lines: " + Extensions.statusLines(), Extensions.statusLines().contains(
                "Trash Bin: Couldn't read the menu's artistMetadata_: java.lang.NoSuchFieldException: artistMetadata_"));
    }

    @Test
    public void aGetLinkThatThrowsDropsTheTrashItem() {
        Extensions.setOn(context, Extensions.TRASH_BIN, true);

        assertTrue(ExtensionMenus.trackItems(new Track(new ThrowingMetadata())).isEmpty());
    }

    @Test
    public void thePlaylistMenuOffersShufflePlusForAPlaylistOrLikedSongsWhileShufflePlusIsOn() {
        Extensions.setOn(context, Extensions.SHUFFLE_PLUS, true);
        String[] lists = {"spotify:playlist:p", Esperanto.LIKED_SONGS, "spotify:collection:tracks",
                "spotify:internal:collection:tracks", "spotify:user:someone:collection"};

        for (String uri : lists) {
            assertArrayEquals(uri, new String[] {"shuffle_playlist", "Shuffle+ this playlist", "shuffle"},
                    ExtensionMenus.playlistItem(uri));
        }
    }

    @Test
    public void thePlaylistMenuOffersNothingForAnAlbumOrAnArtistOrWithShufflePlusOff() {
        Extensions.setOn(context, Extensions.SHUFFLE_PLUS, true);
        for (String uri : new String[] {"spotify:album:a", "spotify:artist:z", "", null}) {
            assertNull(uri, ExtensionMenus.playlistItem(uri));
        }

        Extensions.setOn(context, Extensions.SHUFFLE_PLUS, false);
        Extensions.setOn(context, Extensions.TRASH_BIN, true);
        Extensions.setOn(context, Extensions.RANDOM_SONG, true);
        assertNull(ExtensionMenus.playlistItem("spotify:playlist:p"));
        assertNull(ExtensionMenus.playlistItem("spotify:collection:tracks"));
    }

    @Test
    public void aTapOnThePlaylistItemListsAndPlaysThatPlaylistWithNothingPlaying() throws Exception {
        FakeRouter router = new FakeRouter();
        PlayerBridge.attach(router);
        Extensions.setOn(context, Extensions.SHUFFLE_PLUS, true);
        FakeRequest stream = router.next();
        assertEquals("SUB", stream.action);
        stream.callback.onResponse(200, ShufflePlusTest.state(null, 7)); // nothing loaded
        String playlist = "spotify:playlist:p";

        ExtensionMenus.onPlaylistItem("shuffle_playlist", playlist);

        FakeRequest get = router.next();
        assertEquals(PLAYLIST_GET, get.uri);
        assertArrayEquals(Esperanto.playlistGet(playlist, 0, 500, false), get.body);
        assertEquals("the tap only posts the run", "Spicetify player bridge", get.thread);
        get.callback.onResponse(200, RandomSongTest.playlistPage(2, "spotify:track:a", "spotify:track:b"));
        FakeRequest play = router.next();
        assertEquals(PLAY, play.uri);
        // The menu's run shuffles with SecureRandom, so either order of the two songs is right.
        assertTrue("plays that playlist in a shuffled order",
                Arrays.equals(Esperanto.playOrder(playlist, Arrays.asList("spotify:track:a", "spotify:track:b")), play.body)
                        || Arrays.equals(Esperanto.playOrder(playlist,
                                Arrays.asList("spotify:track:b", "spotify:track:a")), play.body));
        play.callback.onResponse(200, new byte[0]);
        RandomSongTest.awaitStatus(Extensions.SHUFFLE_PLUS, "Shuffled 2 songs");
    }

    @Test
    public void theTrashSongItemTogglesTheMenusSong() {
        Track track = new Track(new Metadata("spotify:track:a"));

        ExtensionMenus.onTrackItem("trash_song", track);
        assertTrue(TrashBin.isSongTrashed("spotify:track:a"));

        ExtensionMenus.onTrackItem("trash_song", track);
        assertFalse(TrashBin.isSongTrashed("spotify:track:a"));
    }

    @Test
    public void theArtistMenuThrowsTheArtistToTrashAndTakesItBack() {
        Extensions.setOn(context, Extensions.TRASH_BIN, true);
        Artist artist = new Artist(new Metadata("spotify:artist:z"));

        assertEquals(Collections.singletonList("trash_artist|Throw artist to trash|trash"),
                rows(ExtensionMenus.artistItems(artist)));
        ExtensionMenus.onArtistItem("trash_artist", artist);
        assertTrue(TrashBin.isArtistTrashed("spotify:artist:z"));
        assertEquals(Collections.singletonList("trash_artist|Take artist out of trash|trash"),
                rows(ExtensionMenus.artistItems(artist)));

        ExtensionMenus.onArtistItem("trash_artist", artist);
        assertFalse(TrashBin.isArtistTrashed("spotify:artist:z"));
    }

    @Test
    public void anyOtherIdRunsItsRegisteredActionAndAnUnknownIdDoesNothing() {
        List<Context> ran = new ArrayList<>();
        Extensions.registerAction("menus_test_action", ran::add);
        Track track = new Track(new Metadata("spotify:track:a"));

        ExtensionMenus.onTrackItem("menus_test_action", track);
        ExtensionMenus.onArtistItem("menus_test_action", new Artist(new Metadata("spotify:artist:z")));
        ExtensionMenus.onTrackItem("menus_test_unknown", track);

        assertEquals(Arrays.asList(context, context), ran);
    }

    @Test
    public void anActionThatThrowsStaysInsideTheMenus() {
        Extensions.registerAction("menus_test_throws", ignored -> {
            throw new IllegalStateException("boom");
        });

        // Both return normally: a tap must never throw into Spotify's click handler.
        ExtensionMenus.onTrackItem("menus_test_throws", new Track(new Metadata("spotify:track:a")));
        ExtensionMenus.onArtistItem("menus_test_throws", new Artist(new Metadata("spotify:artist:z")));
    }

    private static List<String> rows(List<String[]> items) {
        List<String> rows = new ArrayList<>();
        for (String[] item : items) rows.add(String.join("|", item));
        return rows;
    }

    /** Stands in for CollectionTrack: R8 renamed its getter, but protobuf-lite needs the field's name. */
    private static final class Track {
        @SuppressWarnings({"unused", "FieldCanBeLocal"})
        private final Object trackMetadata_;

        Track(Object metadata) {
            trackMetadata_ = metadata;
        }
    }

    /** Stands in for CollectionArtist, the same way. */
    private static final class Artist {
        @SuppressWarnings({"unused", "FieldCanBeLocal"})
        private final Object artistMetadata_;

        Artist(Object metadata) {
            artistMetadata_ = metadata;
        }
    }

    /** Stands in for TrackMetadata and ArtistMetadata, whose getLink() keeps its name. */
    public static final class Metadata {
        private final String link;

        Metadata(String link) {
            this.link = link;
        }

        public String getLink() {
            return link;
        }
    }

    /** Metadata whose getLink() throws. */
    public static final class ThrowingMetadata {
        public String getLink() {
            throw new IllegalStateException("boom");
        }
    }
}
