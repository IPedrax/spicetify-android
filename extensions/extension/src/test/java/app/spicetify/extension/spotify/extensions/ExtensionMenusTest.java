package app.spicetify.extension.spotify.extensions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
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
    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        Extensions.setAppContext(context);
        TrashBin.clear(context);
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
    public void randomSongAndShufflePlusAddTheirItemsWithTheShuffleIcon() {
        Extensions.setOn(context, Extensions.RANDOM_SONG, true);
        Extensions.setOn(context, Extensions.SHUFFLE_PLUS, true);

        assertEquals(Arrays.asList(
                        "random_spotify|Random song from Spotify|shuffle",
                        "random_library|Random song from my library|shuffle",
                        "shuffle_plus|Shuffle+ what's playing|shuffle"),
                rows(ExtensionMenus.trackItems(new Track(new Metadata("spotify:track:a")))));
    }

    @Test
    public void withoutAUriThereIsNoTrashItemButTheOthersStayAndTheClickDoesNothing() throws Exception {
        Extensions.setOn(context, Extensions.TRASH_BIN, true);
        Extensions.setOn(context, Extensions.RANDOM_SONG, true);
        Object[] noUri = {new Object(), new Track(null), new Track(new Metadata(""))};

        for (Object track : noUri) {
            assertEquals(Arrays.asList(
                            "random_spotify|Random song from Spotify|shuffle",
                            "random_library|Random song from my library|shuffle"),
                    rows(ExtensionMenus.trackItems(track)));
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
    public void aGetLinkThatThrowsDropsOnlyTheTrashItem() {
        Extensions.setOn(context, Extensions.TRASH_BIN, true);
        Extensions.setOn(context, Extensions.RANDOM_SONG, true);

        assertEquals(Arrays.asList(
                        "random_spotify|Random song from Spotify|shuffle",
                        "random_library|Random song from my library|shuffle"),
                rows(ExtensionMenus.trackItems(new Track(new ThrowingMetadata()))));
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
