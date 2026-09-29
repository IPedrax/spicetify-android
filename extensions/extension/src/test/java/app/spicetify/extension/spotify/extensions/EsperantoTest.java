package app.spicetify.extension.spotify.extensions;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        Wire.Writer contextTrack = new Wire.Writer();
        contextTrack.string(1, "spotify:track:x");
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
        state.string(8, "playback-1");
        state.bool(14, true);
        state.varint(25, 7);

        Esperanto.PlayerState parsed = Esperanto.parseState(state.toByteArray());

        assertEquals("spotify:playlist:p", parsed.contextUri);
        assertEquals("spotify:track:x", parsed.trackUri);
        assertEquals("uid-1", parsed.trackUid);
        assertEquals(Arrays.asList("spotify:artist:a1", "spotify:artist:a2"), parsed.artistUris);
        assertFalse(parsed.advertisement);
        assertEquals("playback-1", parsed.playbackId);
        assertTrue(parsed.paused);
        assertEquals(7L, parsed.queueRevision);
    }

    private static Wire.Writer metadataEntry(String key, String value) {
        Wire.Writer entry = new Wire.Writer();
        entry.string(1, key);
        entry.string(2, value);
        return entry;
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
    public void simpleCommandsEncodeTheirFields() throws IOException {
        assertEquals(0, Esperanto.skipNext().length);
        assertEquals(0, Esperanto.getState().length);

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
    private static byte[] nestedBytes(byte[] data, int field) throws IOException {
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

    private static long varintField(byte[] data, int field) throws IOException {
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
