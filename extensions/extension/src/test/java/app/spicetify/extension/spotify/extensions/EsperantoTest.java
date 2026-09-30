package app.spicetify.extension.spotify.extensions;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class EsperantoTest {
    // ---- State ----

    @Test
    public void parseStateReadsCoreFieldsArtistsAndAdvertisement() throws IOException {
        Esperanto.PlayerState parsed = Esperanto.parseState(contextPlayerState("spotify:track:x"));

        assertEquals("spotify:playlist:p", parsed.contextUri);
        assertEquals("spotify:track:x", parsed.trackUri);
        assertEquals("uid-1", parsed.trackUid);
        assertEquals(Arrays.asList("spotify:artist:a1", "spotify:artist:a2"), parsed.artistUris);
        assertFalse(parsed.advertisement);
        // playback_id is bytes, which the app shows as lowercase hex (Lp/v3w;->apply 193 to 211).
        assertEquals("0abc7f", parsed.playbackId);
        assertTrue(parsed.playing);
        assertTrue(parsed.paused);
        assertEquals(7L, parsed.queueRevision);
        assertTrue(parsed.nextTracks.isEmpty());
    }

    @Test
    public void parseStateReadsTheNextTracksAndLeavesTheCurrentOneAlone() throws IOException {
        Wire.Writer now = contextTrack("spotify:track:now", "uid-now");
        now.message(3, metadataEntry("artist_uri", "spotify:artist:now"));
        Wire.Writer next = contextTrack("spotify:track:a", "uid-a");
        next.message(3, metadataEntry("artist_uri", "spotify:artist:a")); // not the current track's artist
        Wire.Writer state = new Wire.Writer();
        state.message(7, providedTrack(now));
        state.message(20, providedTrack(contextTrack("spotify:track:before", "uid-before")));
        state.message(21, providedTrack(next));
        state.message(21, providedTrack(contextTrack("spotify:track:b", "uid-b")));
        state.varint(25, 3);

        Esperanto.PlayerState parsed = Esperanto.parseState(state.toByteArray());

        assertEquals("spotify:track:now", parsed.trackUri);
        assertEquals("uid-now", parsed.trackUid);
        assertEquals(Arrays.asList("spotify:artist:now"), parsed.artistUris);
        assertEquals(2, parsed.nextTracks.size());
        assertEquals("spotify:track:a", parsed.nextTracks.get(0).uri);
        assertEquals("uid-a", parsed.nextTracks.get(0).uid);
        assertEquals("spotify:track:b", parsed.nextTracks.get(1).uri);
        assertEquals("uid-b", parsed.nextTracks.get(1).uid);
        assertEquals(3L, parsed.queueRevision);
    }

    /**
     * A {@code ContextPlayerState} playing {@code trackUri} (uid {@code uid-1}) in
     * {@code spotify:playlist:p}: artists {@code a1} and {@code a2} plus a blank third,
     * {@code is_advertisement=false}, playback id bytes {@code 0a bc 7f}, playing but paused, queue
     * revision 7. Other tests send it as a state body.
     */
    static byte[] contextPlayerState(String trackUri) {
        Wire.Writer contextTrack = new Wire.Writer();
        contextTrack.string(1, trackUri);
        contextTrack.string(2, "uid-1");
        contextTrack.message(3, metadataEntry("artist_uri", "spotify:artist:a1"));
        contextTrack.message(3, metadataEntry("artist_uri:1", "spotify:artist:a2"));
        contextTrack.message(3, metadataEntry("artist_uri:2", ""));
        contextTrack.message(3, metadataEntry("is_advertisement", "false"));

        Wire.Writer providedTrack = new Wire.Writer();
        providedTrack.message(1, contextTrack);

        Wire.Writer state = new Wire.Writer();
        state.string(2, "spotify:playlist:p");
        state.message(7, providedTrack);
        state.bytes(8, new byte[] {0x0a, (byte) 0xbc, 0x7f});
        state.bool(13, true);
        state.bool(14, true);
        state.varint(25, 7);
        return state.toByteArray();
    }

    /**
     * A {@code ContextPlayerError} with {@code code} for {@code trackUri} in {@code spotify:playlist:p},
     * with {@code reasons}. Other tests send it as an error body.
     */
    static byte[] contextPlayerError(int code, String trackUri, String reasons) {
        Wire.Writer error = new Wire.Writer();
        error.varint(1, code);
        error.message(3, metadataEntry("track_uri", trackUri));
        error.message(3, metadataEntry("context_uri", "spotify:playlist:p"));
        error.message(3, metadataEntry("reasons", reasons));
        return error.toByteArray();
    }

    private static Wire.Writer metadataEntry(String key, String value) {
        Wire.Writer entry = new Wire.Writer();
        entry.string(1, key);
        entry.string(2, value);
        return entry;
    }

    private static Wire.Writer contextTrack(String uri, String uid) {
        Wire.Writer contextTrack = new Wire.Writer();
        contextTrack.string(1, uri);
        contextTrack.string(2, uid);
        return contextTrack;
    }

    private static Wire.Writer providedTrack(Wire.Writer contextTrack) {
        Wire.Writer providedTrack = new Wire.Writer();
        providedTrack.message(1, contextTrack);
        providedTrack.string(4, "context");
        return providedTrack;
    }

    // ---- Episodes ----

    @Test
    public void parseStateDetectsEpisodeTracks() throws IOException {
        Wire.Writer contextTrack = new Wire.Writer();
        contextTrack.string(1, "spotify:episode:x");

        Wire.Writer providedTrack = new Wire.Writer();
        providedTrack.message(1, contextTrack);

        Wire.Writer state = new Wire.Writer();
        state.message(7, providedTrack);

        Esperanto.PlayerState parsed = Esperanto.parseState(state.toByteArray());
        assertTrue(parsed.episode);
    }

    @Test
    public void parseStateTreatsATrackUriAsNotAnEpisode() throws IOException {
        Wire.Writer contextTrack = new Wire.Writer();
        contextTrack.string(1, "spotify:track:x");

        Wire.Writer providedTrack = new Wire.Writer();
        providedTrack.message(1, contextTrack);

        Wire.Writer state = new Wire.Writer();
        state.message(7, providedTrack);

        Esperanto.PlayerState parsed = Esperanto.parseState(state.toByteArray());
        assertFalse(parsed.episode);
    }

    // ---- Request builders ----

    @Test
    public void getStateCapsThePreviousTracksAtNoneAndTheNextAtThree() {
        // GetStateRequest{1 prev_tracks_cap{1 0}, 2 next_tracks_cap{1 3}}, each an OptionalInt64
        assertArrayEquals(hexToBytes("0a020800" + "12020803"), Esperanto.getState());
    }

    @Test
    public void simpleCommandsEncodeTheirFields() throws IOException {
        assertEquals(0, Esperanto.skipNext().length);
        assertEquals("GetErrorRequest has no fields", 0, Esperanto.getError().length);

        byte[] shuffleOn = Esperanto.setShuffling(true);
        assertEquals(1L, varintField(shuffleOn, 1));

        byte[] shuffleOff = Esperanto.setShuffling(false);
        assertEquals(0L, varintField(shuffleOff, 1));

        byte[] queued = Esperanto.addToQueue("spotify:track:a");
        assertEquals("spotify:track:a", stringField(nestedBytes(queued, 1), 1));
    }

    @Test
    public void playContextSetsContextUriAndOptionalSkipTo() throws IOException {
        byte[] withSkip = Esperanto.playContext("spotify:playlist:p", "spotify:track:a");
        byte[] prepare = nestedBytes(withSkip, 1);
        byte[] context = nestedBytes(prepare, 1);
        assertEquals("spotify:playlist:p", stringField(context, 3));
        assertEquals("context://spotify:playlist:p", stringField(context, 4));

        byte[] options = nestedBytes(prepare, 2);
        byte[] skipTo = nestedBytes(options, 3);
        assertEquals("spotify:track:a", stringField(skipTo, 4));

        byte[] withoutSkip = Esperanto.playContext("spotify:playlist:p", null);
        byte[] prepare2 = nestedBytes(withoutSkip, 1);
        assertTrue(repeatedNestedBytes(prepare2, 2).isEmpty());
    }

    // ---- playOrder ----

    @Test
    public void playOrderBuildsExplicitPageAndOverrides() throws IOException {
        byte[] request = Esperanto.playOrder("spotify:playlist:p", Arrays.asList("spotify:track:a", "spotify:track:b"));
        byte[] prepare = nestedBytes(request, 1);
        byte[] context = nestedBytes(prepare, 1);
        byte[] options = nestedBytes(prepare, 2);

        assertEquals("spotify:playlist:p", stringField(context, 3));
        List<byte[]> pages = repeatedNestedBytes(context, 1);
        assertEquals(1, pages.size());
        List<byte[]> tracks = repeatedNestedBytes(pages.get(0), 1);
        assertEquals(2, tracks.size());
        assertEquals("spotify:track:a", stringField(tracks.get(0), 1));
        assertEquals("spotify:track:b", stringField(tracks.get(1), 1));
        // No url: an explicit page must not carry a resolvable url, or the core may re-resolve
        // the context from it and drop the page (report 2.2's fromTrackUris recipe, risk 7).
        assertTrue(repeatedNestedBytes(context, 4).isEmpty());

        byte[] overrides = nestedBytes(options, 7);
        byte[] shufflingContext = nestedBytes(overrides, 1);
        assertEquals(0L, varintField(shufflingContext, 1));

        byte[] skipTo = nestedBytes(options, 3);
        byte[] trackIndex = nestedBytes(skipTo, 5);
        assertEquals(0L, varintField(trackIndex, 1));
    }

    @Test
    public void playPageSendsTheListAsOneExplicitPageFromARowAndLeavesTheUsersShuffleAlone() throws IOException {
        Esperanto.ContextTrack a = new Esperanto.ContextTrack();
        a.uri = "spotify:track:a";
        a.uid = "row-a";
        Esperanto.ContextTrack b = new Esperanto.ContextTrack();
        b.uri = "spotify:track:b"; // an item without a row id
        byte[] request = Esperanto.playPage("spotify:playlist:p", Arrays.asList(a, b), "row-a");

        byte[] prepare = nestedBytes(request, 1);
        assertEquals(Arrays.asList(1, 2), fieldNumbers(prepare));
        byte[] context = nestedBytes(prepare, 1);
        // No url, as in playOrder: the core could re-resolve the context from one and drop the page.
        assertEquals(Arrays.asList(1, 3), fieldNumbers(context));
        assertEquals("spotify:playlist:p", stringField(context, 3));
        List<byte[]> pages = repeatedNestedBytes(context, 1);
        assertEquals(1, pages.size());
        List<byte[]> tracks = repeatedNestedBytes(pages.get(0), 1);
        assertEquals(2, tracks.size());
        assertEquals(Arrays.asList(1, 2), fieldNumbers(tracks.get(0)));
        assertEquals("spotify:track:a", stringField(tracks.get(0), 1));
        assertEquals("row-a", stringField(tracks.get(0), 2));
        assertEquals(Arrays.asList(1), fieldNumbers(tracks.get(1)));
        assertEquals("spotify:track:b", stringField(tracks.get(1), 1));

        // skip_to{3 track_uid} and nothing else: no player_options_override, so shuffle stays the user's.
        byte[] options = nestedBytes(prepare, 2);
        assertEquals(Arrays.asList(3), fieldNumbers(options));
        byte[] skipTo = nestedBytes(options, 3);
        assertEquals(Arrays.asList(3), fieldNumbers(skipTo));
        assertEquals("row-a", stringField(skipTo, 3));
    }

    @Test
    public void setQueueAppendsDelimiterAfterProvidedTracks() throws IOException {
        byte[] request = Esperanto.setQueue(Arrays.asList("spotify:track:a", "spotify:track:b"), 9L);
        List<byte[]> nextTracks = repeatedNestedBytes(request, 1);
        assertEquals(3, nextTracks.size());
        assertEquals("spotify:track:a", stringField(nestedBytes(nextTracks.get(0), 1), 1));
        assertEquals("context", stringField(nextTracks.get(0), 4));
        assertEquals("spotify:track:b", stringField(nestedBytes(nextTracks.get(1), 1), 1));
        assertEquals("spotify:delimiter", stringField(nestedBytes(nextTracks.get(2), 1), 1));
        assertEquals(9L, varintField(request, 3));
    }

    @Test
    public void playAsNextInQueueSendsEachTrackByUriInOrder() throws IOException {
        byte[] request = Esperanto.playAsNextInQueue(Arrays.asList("spotify:track:a", "spotify:track:b"));

        // PlayAsNextInQueueRequest{1 tracks: [ContextTrack{1 uri}]}, and no options or logging params
        List<byte[]> tracks = repeatedNestedBytes(request, 1);
        assertEquals(2, tracks.size());
        assertEquals("spotify:track:a", stringField(tracks.get(0), 1));
        assertEquals("spotify:track:b", stringField(tracks.get(1), 1));
        assertTrue(repeatedNestedBytes(request, 2).isEmpty());
        assertTrue(repeatedNestedBytes(request, 3).isEmpty());
    }

    @Test
    public void playlistGetBuildsQueryAndPolicy() throws IOException {
        byte[] request = Esperanto.playlistGet("spotify:playlist:p", 10, 5, false);
        assertEquals("spotify:playlist:p", stringField(request, 1));

        byte[] query = nestedBytes(request, 2);
        assertArrayEquals(new byte[] {4, 3, 7, 6}, nestedBytes(query, 1));
        assertEquals(0L, varintField(query, 8));

        byte[] range = nestedBytes(query, 4);
        assertEquals(10L, varintField(range, 1));
        assertEquals(5L, varintField(range, 2));

        byte[] policy = nestedBytes(request, 3);
        byte[] playlist = nestedBytes(policy, 1);
        assertEquals(1L, varintField(playlist, 49));
        byte[] item = nestedBytes(policy, 4);
        assertEquals(1L, varintField(item, 1));
    }

    @Test
    public void playlistGetOmitsRangeWhenLengthIsNegative() throws IOException {
        byte[] request = Esperanto.playlistGet("spotify:playlist:p", 0, -1, false);
        byte[] query = nestedBytes(request, 2);
        assertTrue(repeatedNestedBytes(query, 4).isEmpty());
    }

    @Test
    public void playlistGetWritesARangeForCountsOnly() throws IOException {
        byte[] request = Esperanto.playlistGet("spotify:playlist:p", 0, 0, true);
        byte[] query = nestedBytes(request, 2);
        byte[] range = nestedBytes(query, 4);
        assertEquals(0L, varintField(range, 1));
        assertEquals(0L, varintField(range, 2));
    }

    @Test
    public void playlistPlayabilityListsUnavailableSongsTooWithTheirPlayability() throws IOException {
        byte[] request = Esperanto.playlistPlayability("spotify:playlist:p", 500, 500);
        assertEquals("spotify:playlist:p", stringField(request, 1));

        byte[] query = nestedBytes(request, 2);
        assertArrayEquals(new byte[] {4, 3, 7, 6}, nestedBytes(query, 1));
        assertEquals("show_unavailable", 1L, varintField(query, 8));
        byte[] range = nestedBytes(query, 4);
        assertEquals(500L, varintField(range, 1));
        assertEquals(500L, varintField(range, 2));

        byte[] policy = nestedBytes(request, 3);
        assertEquals(Arrays.asList(1, 2, 4), fieldNumbers(policy));
        assertEquals(1L, varintField(nestedBytes(policy, 1), 49));
        // policy{2 track{1 track{5 playable, 13 is_local}}}: a track field the policy doesn't ask for isn't filled.
        byte[] track = nestedBytes(nestedBytes(policy, 2), 1);
        assertEquals(Arrays.asList(5, 13), fieldNumbers(track));
        assertEquals(1L, varintField(track, 5));
        assertEquals(1L, varintField(track, 13));
        byte[] item = nestedBytes(policy, 4);
        assertEquals("uri and row_id", Arrays.asList(1, 9), fieldNumbers(item));
        assertEquals(1L, varintField(item, 1));
        assertEquals(1L, varintField(item, 9));

        // The listing Shuffle+ and Play a random song use stays as it was.
        byte[] plain = Esperanto.playlistGet("spotify:playlist:p", 500, 500, false);
        assertEquals(0L, varintField(nestedBytes(plain, 2), 8));
        assertEquals(Arrays.asList(1, 4), fieldNumbers(nestedBytes(plain, 3)));
        assertEquals(Arrays.asList(1), fieldNumbers(nestedBytes(nestedBytes(plain, 3), 4)));
    }

    @Test
    public void getEntityRequestsTheUri() throws IOException {
        byte[] request = Esperanto.getEntity("spotify:album:a");
        assertEquals("spotify:album:a", stringField(request, 1));
    }

    // ---- Response parsers ----

    @Test
    public void parsePlaylistGetHandlesStatusCodes() throws IOException {
        assertTrue(parsePlaylistGetWithStatus(404).uris.isEmpty());
        assertEquals(0, parsePlaylistGetWithStatus(404).length);

        try {
            parsePlaylistGetWithStatus(500);
            fail("expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void parsePlaylistGetReadsItemsAndCounts() throws IOException {
        Wire.Writer status = new Wire.Writer();
        status.varint(1, 200);

        Wire.Writer itemA = new Wire.Writer();
        itemA.string(18, "spotify:track:a");
        Wire.Writer itemB = new Wire.Writer();
        itemB.string(18, "spotify:track:b");

        Wire.Writer data = new Wire.Writer();
        data.message(1, itemA);
        data.message(1, itemB);
        data.varint(4, 42);
        data.bool(6, true);

        Wire.Writer response = new Wire.Writer();
        response.message(1, status);
        response.message(2, data);

        Esperanto.PlaylistPage page = Esperanto.parsePlaylistGet(response.toByteArray());
        assertEquals(Arrays.asList("spotify:track:a", "spotify:track:b"), page.uris);
        assertEquals(42, page.length);
        assertTrue(page.loading);
    }

    @Test
    public void parsePlaylistGetReadsEachItemsRowIdAndPlayability() throws IOException {
        // Item{7 row_id, 18 uri, 4 track_metadata{6 playable, 11 is_local},
        //     8 track_play_state{1 is_playable, 2 playability_restriction}}
        Wire.Writer greyedMetadata = new Wire.Writer();
        greyedMetadata.bool(6, false);
        greyedMetadata.string(4, "a name the parse skips");
        Wire.Writer greyedState = new Wire.Writer();
        greyedState.bool(1, false);
        greyedState.varint(2, 4); // NOT_IN_CATALOGUE
        Wire.Writer greyed = new Wire.Writer();
        greyed.string(7, "row-greyed");
        greyed.message(4, greyedMetadata);
        greyed.message(8, greyedState);
        greyed.string(18, "spotify:track:greyed");

        Wire.Writer localMetadata = new Wire.Writer();
        localMetadata.bool(6, true);
        localMetadata.bool(11, true);
        Wire.Writer local = new Wire.Writer();
        local.string(18, "spotify:local:a:b:c:1");
        local.string(7, "row-local");
        local.message(4, localMetadata);

        // Without track_play_state, or with it but no is_playable, the app counts the item as playable.
        Wire.Writer noPlayable = new Wire.Writer();
        noPlayable.varint(2, 1); // NO_RESTRICTION
        Wire.Writer quiet = new Wire.Writer();
        quiet.string(18, "spotify:track:quiet");
        quiet.message(8, noPlayable);

        Wire.Writer status = new Wire.Writer();
        status.varint(1, 200);
        Wire.Writer data = new Wire.Writer();
        data.message(1, greyed);
        data.message(1, local);
        data.message(1, quiet);
        data.varint(4, 3);
        Wire.Writer response = new Wire.Writer();
        response.message(1, status);
        response.message(2, data);

        Esperanto.PlaylistPage page = Esperanto.parsePlaylistGet(response.toByteArray());

        assertEquals(Arrays.asList("spotify:track:greyed", "spotify:local:a:b:c:1", "spotify:track:quiet"), page.uris);
        assertEquals(3, page.items.size());
        Esperanto.PlaylistItem first = page.items.get(0);
        assertEquals("spotify:track:greyed", first.uri);
        assertEquals("row-greyed", first.rowId);
        assertFalse(first.playable);
        assertFalse(first.isPlayable);
        assertEquals(4, first.restriction);
        assertFalse(first.local);
        Esperanto.PlaylistItem second = page.items.get(1);
        assertEquals("row-local", second.rowId);
        assertTrue(second.playable);
        assertTrue(second.isPlayable);
        assertEquals("UNKNOWN, the enum's default", 0, second.restriction);
        assertTrue(second.local);
        Esperanto.PlaylistItem third = page.items.get(2);
        assertNull(third.rowId);
        assertTrue(third.isPlayable);
        assertEquals(1, third.restriction);
    }

    private static Esperanto.PlaylistPage parsePlaylistGetWithStatus(int statusCode) throws IOException {
        Wire.Writer status = new Wire.Writer();
        status.varint(1, statusCode);
        Wire.Writer response = new Wire.Writer();
        response.message(1, status);
        return Esperanto.parsePlaylistGet(response.toByteArray());
    }

    @Test
    public void parseAlbumTracksWalksDiscsAndTracks() throws IOException {
        byte[] gidA = new byte[16];
        gidA[15] = 1;
        byte[] gidB = new byte[16];
        gidB[15] = 2;

        Wire.Writer trackA = new Wire.Writer();
        trackA.bytes(1, gidA);
        Wire.Writer trackB = new Wire.Writer();
        trackB.bytes(1, gidB);

        Wire.Writer disc = new Wire.Writer();
        disc.message(3, trackA);
        disc.message(3, trackB);

        Wire.Writer album = new Wire.Writer();
        album.message(11, disc);

        Wire.Writer item = new Wire.Writer();
        item.message(3, album);

        Wire.Writer response = new Wire.Writer();
        response.message(1, item);

        List<String> uris = Esperanto.parseAlbumTracks(response.toByteArray());
        assertEquals(
                Arrays.asList("spotify:track:" + Esperanto.base62(gidA), "spotify:track:" + Esperanto.base62(gidB)),
                uris);
    }

    @Test
    public void getEntityRequestsASongByItsUri() throws IOException {
        assertEquals("spotify:track:t", stringField(Esperanto.getEntity("spotify:track:t"), 1));
    }

    @Test
    public void parseTrackReadsTheDetailsOfASongFromSpotifysCore() throws IOException {
        Wire.Writer band = new Wire.Writer();
        band.bytes(1, RandomSongTest.gid(7));
        band.string(2, "The Band");
        Wire.Writer guest = new Wire.Writer();
        guest.string(2, "A Guest"); // no gid
        Wire.Writer album = new Wire.Writer();
        album.string(2, "An Album");

        Wire.Writer track = new Wire.Writer();
        track.bytes(1, RandomSongTest.gid(99));
        track.string(2, "Song");
        track.message(3, album);
        track.message(4, band);
        track.message(4, guest);
        track.varint(7, 2 * 201_234); // a sint32, zigzag on the wire
        track.bool(9, true);
        track.message(10, externalId("upc", "00602547"));
        track.message(10, externalId("isrc", "GBAYE0601498"));
        track.message(10, externalId("isrc", "USUM71703861"));
        track.message(13, alternative(RandomSongTest.gid(1)));
        track.message(13, alternative(RandomSongTest.gid(2)));
        Wire.Writer item = new Wire.Writer();
        item.message(4, track); // MetadataItem's case 4, the song
        Wire.Writer response = new Wire.Writer();
        response.message(1, item);

        Esperanto.Track song = Esperanto.parseTrack(response.toByteArray());

        assertEquals("Song", song.name);
        assertEquals(2, song.artists.size());
        assertEquals("its gid as the Web API's id", Esperanto.base62(RandomSongTest.gid(7)), song.artists.get(0).id);
        assertEquals("The Band", song.artists.get(0).name);
        assertEquals("", song.artists.get(1).id);
        assertEquals("A Guest", song.artists.get(1).name);
        assertEquals(201_234, song.durationMillis);
        assertTrue(song.explicit);
        assertEquals("the first isrc", "GBAYE0601498", song.isrc);
        assertEquals(Arrays.asList("spotify:track:" + Esperanto.base62(RandomSongTest.gid(1)),
                "spotify:track:" + Esperanto.base62(RandomSongTest.gid(2))), song.alternatives);
    }

    @Test
    public void parseTrackWithoutAnIsrcOrAlternativesLeavesThemEmpty() throws IOException {
        Wire.Writer track = new Wire.Writer();
        track.string(2, "Song");
        track.message(10, externalId("upc", "00602547"));
        Wire.Writer item = new Wire.Writer();
        item.message(4, track);
        Wire.Writer response = new Wire.Writer();
        response.message(1, item);

        Esperanto.Track song = Esperanto.parseTrack(response.toByteArray());

        assertEquals("", song.isrc);
        assertTrue(song.alternatives.isEmpty());
        assertTrue(song.artists.isEmpty());
        assertFalse(song.explicit);
    }

    @Test
    public void parseTrackOfAnAnswerWithoutASongThrowsWithTheCoresError() {
        Wire.Writer error = new Wire.Writer();
        error.varint(1, 2 * 404); // MetadataItem's case 1, the core's error, a sint32
        Wire.Writer withError = new Wire.Writer();
        withError.message(1, error);
        Wire.Writer album = new Wire.Writer();
        album.message(3, new Wire.Writer());
        Wire.Writer withAlbum = new Wire.Writer();
        withAlbum.message(1, album);

        assertParseTrackFails("error 404", withError.toByteArray());
        assertParseTrackFails("no song in the answer", withAlbum.toByteArray());
        assertParseTrackFails("no song in the answer", new byte[0]);
    }

    private static void assertParseTrackFails(String message, byte[] getEntityResponse) {
        try {
            Esperanto.parseTrack(getEntityResponse);
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals(message, expected.getMessage());
        }
    }

    private static Wire.Writer externalId(String type, String id) {
        Wire.Writer externalId = new Wire.Writer();
        externalId.string(1, type);
        externalId.string(2, id);
        return externalId;
    }

    private static Wire.Writer alternative(byte[] gid) {
        Wire.Writer alternative = new Wire.Writer();
        alternative.bytes(1, gid);
        return alternative;
    }

    // ---- Your Library ----

    @Test
    public void yourLibraryAllEncodesTheTracesRequestWithItsFieldsInOrder() {
        // header{12 length 0x7fffffff, 14 filters{1 [PLAYLIST 2, ALBUM 0]}, 17 all_playlists,
        // 25 num_link_types_in_playlists, 26 ignore_pinning}
        assertArrayEquals(hexToBytes("0a15" + "60ffffffff07" + "72040a020200" + "880101" + "c80101" + "d00101"),
                Esperanto.yourLibraryAll());
    }

    @Test
    public void parseYourLibraryGivesLikedSongsFirstThenEachPlaylistAndAlbumOnce() throws IOException {
        Wire.Writer response = new Wire.Writer();
        response.message(1, new Wire.Writer());
        response.message(2, libraryEntity("spotify:playlist:p", 4, countedPlaylist(12)));
        // An album whose member is empty: the case is in the tag.
        response.message(2, libraryEntity("spotify:album:a", 2, new Wire.Writer()));
        Wire.Writer folder = new Wire.Writer();
        folder.varint(2, 3);
        response.message(2, libraryEntity("spotify:user:u:folder:00000000000000ff", 6, folder));
        response.message(2, libraryEntity("spotify:collection:tracks", 4, countedPlaylist(40)));
        response.message(3, libraryEntity("spotify:playlist:p", 4, countedPlaylist(99)));
        response.varint(98, 200);

        Esperanto.Library library = Esperanto.parseYourLibrary(response.toByteArray());

        assertFalse(library.loading);
        List<String> uris = new ArrayList<>();
        for (Esperanto.LibrarySource source : library.sources) uris.add(source.uri);
        assertEquals(Arrays.asList(Esperanto.LIKED_SONGS, "spotify:playlist:p", "spotify:album:a"), uris);
        assertFalse(library.sources.get(0).album);
        assertEquals("Liked Songs is sized by its playable length", -1, library.sources.get(0).trackCount);
        assertFalse(library.sources.get(1).album);
        assertEquals("the TRACK count, from the first occurrence", 12, library.sources.get(1).trackCount);
        assertTrue(library.sources.get(2).album);
        assertEquals(-1, library.sources.get(2).trackCount);
    }

    @Test
    public void parseYourLibraryReportsLoadingAndFailsWithTheErrorOfAStatusOtherThan200() throws IOException {
        Wire.Writer header = new Wire.Writer();
        header.bool(12, true);
        Wire.Writer loading = new Wire.Writer();
        loading.message(1, header);
        loading.varint(98, 200);
        assertTrue(Esperanto.parseYourLibrary(loading.toByteArray()).loading);

        Wire.Writer failed = new Wire.Writer();
        failed.varint(98, 500);
        failed.string(99, "database error");
        try {
            Esperanto.parseYourLibrary(failed.toByteArray());
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals("status 500: database error", expected.getMessage());
        }
    }

    @Test
    public void likedSongsGoesByTheFourUrisTheAppAccepts() {
        assertTrue(Esperanto.isLikedSongs("spotify:playlist:37i9dQZF1F5p3rmiWPIYgZ"));
        assertTrue(Esperanto.isLikedSongs("spotify:collection:tracks"));
        assertTrue(Esperanto.isLikedSongs("spotify:internal:collection:tracks"));
        assertTrue(Esperanto.isLikedSongs("spotify:user:someone:collection"));
        assertFalse(Esperanto.isLikedSongs("spotify:playlist:37i9dQZF1EYkqdzj48dyYq"));
        assertFalse(Esperanto.isLikedSongs("spotify:user:someone:collection:artist"));
        assertFalse(Esperanto.isLikedSongs("spotify:user:someone:playlist:x"));
    }

    /** A {@code YourLibraryDecoratedEntity} for {@code uri} whose oneof member is field {@code kind}. */
    static Wire.Writer libraryEntity(String uri, int kind, Wire.Writer member) {
        Wire.Writer info = new Wire.Writer();
        info.string(3, uri);
        Wire.Writer entity = new Wire.Writer();
        entity.message(1, info);
        entity.message(kind, member);
        return entity;
    }

    /** A {@code YourLibraryPlaylistExtraInfo} counting 2 episodes (link type 63) and {@code tracks} songs (4). */
    static Wire.Writer countedPlaylist(int tracks) {
        Wire.Writer playlist = new Wire.Writer();
        playlist.message(12, linkTypeCount(63, 2));
        playlist.message(12, linkTypeCount(4, tracks));
        return playlist;
    }

    private static Wire.Writer linkTypeCount(int linkType, int items) {
        Wire.Writer count = new Wire.Writer();
        count.varint(1, linkType);
        count.varint(2, items);
        return count;
    }

    /** A {@code YourLibraryResponse} with status 200 listing {@code entities}, still loading or not. */
    static byte[] yourLibrary(boolean loading, Wire.Writer... entities) {
        Wire.Writer header = new Wire.Writer();
        header.bool(12, loading);
        Wire.Writer response = new Wire.Writer();
        response.message(1, header);
        for (Wire.Writer entity : entities) response.message(2, entity);
        response.varint(98, 200);
        return response.toByteArray();
    }

    @Test
    public void parseErrorReadsTheCodeTheMessageAndTheDataTheAppReads() throws IOException {
        // ContextPlayerError{1 code, 2 message, 3 data: map<string, string>}
        Wire.Writer error = new Wire.Writer();
        error.varint(1, 20); // ONE_TRACK_UNPLAYABLE_AUTO_STOPPED
        error.string(2, "Track is unavailable");
        error.message(3, metadataEntry("track_uri", "spotify:track:x"));
        error.message(3, metadataEntry("context_uri", "spotify:playlist:p"));
        error.message(3, metadataEntry("reasons", "not_available_in_current_region"));
        error.message(3, metadataEntry("playback_error", "ignored while reasons is there"));
        error.message(3, metadataEntry("other", "skipped"));

        Esperanto.PlayerError parsed = Esperanto.parseError(error.toByteArray());

        assertEquals(20, parsed.code);
        assertEquals("Track is unavailable", parsed.message);
        assertEquals("spotify:track:x", parsed.trackUri);
        assertEquals("spotify:playlist:p", parsed.contextUri);
        assertEquals("not_available_in_current_region", parsed.reasons);
    }

    @Test
    public void parseErrorTakesPlaybackErrorWhenThereAreNoReasonsAsTheAppDoes() throws IOException {
        Wire.Writer error = new Wire.Writer();
        error.varint(1, 19);
        error.message(3, metadataEntry("playback_error", "not_available"));
        Esperanto.PlayerError parsed = Esperanto.parseError(error.toByteArray());
        assertEquals(19, parsed.code);
        assertEquals("not_available", parsed.reasons);
        assertEquals("", parsed.message);
        assertNull(parsed.trackUri);
        assertNull(parsed.contextUri);

        assertNull(Esperanto.parseError(new byte[0]).reasons);
        assertEquals("SUCCESS, the enum's default", 0, Esperanto.parseError(new byte[0]).code);
    }

    @Test
    public void parseShowUnavailableTracksReadsSettingsField17() throws IOException {
        Wire.Writer on = new Wire.Writer();
        on.bool(1, false); // offline_mode
        on.bool(17, true);
        on.varint(28, 1);
        assertTrue(Esperanto.parseShowUnavailableTracks(on.toByteArray()));

        Wire.Writer off = new Wire.Writer();
        off.bool(1, true);
        assertFalse("absent is false", Esperanto.parseShowUnavailableTracks(off.toByteArray()));
    }

    @Test
    public void parseResultReturnsTheErrorCode() throws IOException {
        Wire.Writer forbidden = new Wire.Writer();
        forbidden.varint(1, 1);
        assertEquals(1, Esperanto.parseResult(forbidden.toByteArray()));

        assertEquals(0, Esperanto.parseResult(new byte[0]));
    }

    // ---- base62 ----

    @Test
    public void base62EncodesKnownGids() {
        byte[] one = new byte[16];
        one[15] = 1;
        assertEquals("0000000000000000000001", Esperanto.base62(one));

        byte[] allFf = new byte[16];
        Arrays.fill(allFf, (byte) 0xFF);
        assertEquals("7N42dgm5tFLK9N8MT7fHC7", Esperanto.base62(allFf));

        assertEquals(
                "4uLU6hMCjMI75M1A2tKUQC", Esperanto.base62(hexToBytes("93bc414a606747b2b612491ef83d5a3e")));
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    // ---- Shared navigation helpers ----

    /** The raw bytes of the first length-delimited {@code field} found in {@code data}. */
    static byte[] nestedBytes(byte[] data, int field) throws IOException {
        Wire.Reader reader = new Wire.Reader(data);
        while (reader.next()) {
            if (reader.field() == field) {
                return reader.bytes();
            }
            reader.skip();
        }
        throw new AssertionError("field " + field + " missing");
    }

    /** The raw bytes of every length-delimited occurrence of {@code field} in {@code data}, in order. */
    private static List<byte[]> repeatedNestedBytes(byte[] data, int field) throws IOException {
        List<byte[]> values = new ArrayList<>();
        Wire.Reader reader = new Wire.Reader(data);
        while (reader.next()) {
            if (reader.field() == field) {
                values.add(reader.bytes());
            } else {
                reader.skip();
            }
        }
        return values;
    }

    /** The field numbers at the top level of {@code data}, in wire order. */
    private static List<Integer> fieldNumbers(byte[] data) throws IOException {
        List<Integer> numbers = new ArrayList<>();
        Wire.Reader reader = new Wire.Reader(data);
        while (reader.next()) {
            numbers.add(reader.field());
            reader.skip();
        }
        return numbers;
    }

    static long varintField(byte[] data, int field) throws IOException {
        Wire.Reader reader = new Wire.Reader(data);
        while (reader.next()) {
            if (reader.field() == field) {
                return reader.varint();
            }
            reader.skip();
        }
        throw new AssertionError("field " + field + " missing");
    }

    private static String stringField(byte[] data, int field) throws IOException {
        Wire.Reader reader = new Wire.Reader(data);
        while (reader.next()) {
            if (reader.field() == field) {
                return reader.string();
            }
            reader.skip();
        }
        throw new AssertionError("field " + field + " missing");
    }
}
