package app.spicetify.extension.spotify.extensions;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The esperanto messages the extensions send and receive over {@code sp://esperanto/<service>/
 * <method>}: request builders, response parsers and the player state value. Field numbers come
 * from the marketplace extensions research report, sections 1.2, 2.1 to 2.3 and 3.2 to 3.4, the
 * Your Library trace, section 9, and the unavailable songs report, sections 1.3, 1.4, 3.2, 5.2, 7.1 and 7.4.
 * They are checked again at patch time against the installed Spotify build.
 */
final class Esperanto {
    static final String CONTEXT_PLAYER = "spotify.player.esperanto.proto.ContextPlayer";
    static final String PLAYLIST = "spotify.playlist_esperanto.proto.PlaylistDataService";
    static final String METADATA = "spotify.metadata_esperanto.proto.ClassicMetadataService";
    /** With an underscore in {@code your_library_esperanto}; the dotted name has no route. */
    static final String YOUR_LIBRARY = "spotify.your_library_esperanto.proto.YourLibraryService";
    /** Its {@code GetState} is a SUB with an empty body, answered by {@code SettingsState}. */
    static final String SETTINGS = "spotify.settings.esperanto.proto.Settings";
    static final String LIKED_SONGS = "spotify:playlist:37i9dQZF1F5p3rmiWPIYgZ";
    /** Two of {@link #parseResult}'s answers; the others are 2 NOT_FOUND and 3 CONFLICT. */
    static final int OK = 0;
    static final int FORBIDDEN = 1;

    private static final String BASE62_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int FILTER_ALBUM = 0;
    private static final int FILTER_PLAYLIST = 2;
    private static final int ENTITY_ALBUM = 2;
    private static final int ENTITY_PLAYLIST = 4;
    private static final int LINK_TYPE_TRACK = 4;

    private Esperanto() {}

    /** A parsed {@code ContextPlayerState}: the fields the extensions read. */
    static final class PlayerState {
        String contextUri;
        String trackUri;
        String trackUid;
        List<String> artistUris = new ArrayList<>();
        boolean advertisement;
        boolean episode;
        /** The id's bytes as lowercase hex, as the app shows it; null without one. */
        String playbackId;
        long queueRevision;
        /** {@code is_playing}, false once the player has stopped. */
        boolean playing;
        boolean paused;
        /** What plays next, as far as {@link #getState()}'s cap reaches. */
        List<ContextTrack> nextTracks = new ArrayList<>();
    }

    /**
     * A track the player lists: its uri, and its uid, which in a playlist is the item's row id
     * (unavailable report, section 2.3).
     */
    static final class ContextTrack {
        String uri;
        String uid;
    }

    static PlayerState parseState(byte[] contextPlayerState) throws IOException {
        PlayerState state = new PlayerState();
        Wire.Reader reader = new Wire.Reader(contextPlayerState);
        while (reader.next()) {
            switch (reader.field()) {
                case 2:
                    state.contextUri = reader.string();
                    break;
                case 7:
                    readProvidedTrack(reader.message(), state);
                    break;
                case 8:
                    // bytes, which the app shows as lowercase hex (Lp/v3w;->apply 193 to 211)
                    byte[] playbackId = reader.bytes();
                    if (playbackId.length > 0) state.playbackId = hex(playbackId);
                    break;
                case 13:
                    state.playing = reader.varint() != 0;
                    break;
                case 14:
                    state.paused = reader.varint() != 0;
                    break;
                case 21:
                    state.nextTracks.add(readNextTrack(reader.message()));
                    break;
                case 25:
                    state.queueRevision = reader.varint();
                    break;
                default:
                    reader.skip();
            }
        }
        return state;
    }

    private static void readProvidedTrack(Wire.Reader providedTrack, PlayerState state) throws IOException {
        while (providedTrack.next()) {
            if (providedTrack.field() == 1) {
                readContextTrack(providedTrack.message(), state);
            } else {
                providedTrack.skip();
            }
        }
    }

    private static void readContextTrack(Wire.Reader contextTrack, PlayerState state) throws IOException {
        Map<String, String> metadata = new TreeMap<>();
        while (contextTrack.next()) {
            switch (contextTrack.field()) {
                case 1:
                    state.trackUri = contextTrack.string();
                    break;
                case 2:
                    state.trackUid = contextTrack.string();
                    break;
                case 3:
                    readMetadataEntry(contextTrack.message(), metadata);
                    break;
                default:
                    contextTrack.skip();
            }
        }
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (entry.getKey().startsWith("artist_uri") && !entry.getValue().isEmpty()) {
                state.artistUris.add(entry.getValue());
            }
        }
        state.advertisement = "true".equals(metadata.get("is_advertisement"));
        state.episode = isEpisodeUri(state.trackUri);
    }

    /** A next track's {@code ProvidedTrack}: only its context track's uri and uid, never the current track's fields. */
    private static ContextTrack readNextTrack(Wire.Reader providedTrack) throws IOException {
        ContextTrack track = new ContextTrack();
        while (providedTrack.next()) {
            if (providedTrack.field() != 1) {
                providedTrack.skip();
                continue;
            }
            Wire.Reader contextTrack = providedTrack.message();
            while (contextTrack.next()) {
                switch (contextTrack.field()) {
                    case 1:
                        track.uri = contextTrack.string();
                        break;
                    case 2:
                        track.uid = contextTrack.string();
                        break;
                    default:
                        contextTrack.skip();
                }
            }
        }
        return track;
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return hex.toString();
    }

    /**
     * A parsed {@code ContextPlayerError}, which {@code ContextPlayer/GetError} streams when the
     * player can't do something (unavailable report, section 3.2). Codes 19 to 22 are
     * ONE_TRACK_UNPLAYABLE, ONE_TRACK_UNPLAYABLE_AUTO_STOPPED, ALL_TRACKS_UNPLAYABLE_AUTO_STOPPED and
     * SKIP_TO_NON_EXISTENT_TRACK_AUTO_STOPPED.
     */
    static final class PlayerError {
        int code;
        String message = "";
        String trackUri;
        String contextUri;
        /** The data's {@code reasons}, else its {@code playback_error}, as the app reads them; null without either. */
        String reasons;
    }

    /**
     * Reads {@code ContextPlayerError{1 code, 2 message, 3 data: map<string, string>}} as
     * {@code Lp/t3w;->apply} does.
     */
    static PlayerError parseError(byte[] contextPlayerError) throws IOException {
        PlayerError error = new PlayerError();
        Map<String, String> data = new TreeMap<>();
        Wire.Reader reader = new Wire.Reader(contextPlayerError);
        while (reader.next()) {
            switch (reader.field()) {
                case 1:
                    error.code = (int) reader.varint();
                    break;
                case 2:
                    error.message = reader.string();
                    break;
                case 3:
                    readMetadataEntry(reader.message(), data);
                    break;
                default:
                    reader.skip();
            }
        }
        error.trackUri = data.get("track_uri");
        error.contextUri = data.get("context_uri");
        String reasons = data.get("reasons");
        error.reasons = reasons != null ? reasons : data.get("playback_error");
        return error;
    }

    private static void readMetadataEntry(Wire.Reader entry, Map<String, String> metadata) throws IOException {
        String key = null;
        String value = "";
        while (entry.next()) {
            switch (entry.field()) {
                case 1:
                    key = entry.string();
                    break;
                case 2:
                    value = entry.string();
                    break;
                default:
                    entry.skip();
            }
        }
        if (key != null) {
            metadata.put(key, value);
        }
    }

    private static boolean isEpisodeUri(String uri) {
        return uri != null
                && (uri.startsWith("spotify:episode:")
                        || uri.startsWith("spotify:podcast-chapter:")
                        || uri.startsWith("spotify:clip:"));
    }

    // ---- Request builders ----

    static byte[] skipNext() {
        return new byte[0];
    }

    /**
     * {@code GetStateRequest{1 prev_tracks_cap{1 0}, 2 next_tracks_cap{1 3}}}, each cap an
     * {@code OptionalInt64}: no past tracks and the next three, so a state stays small on a big
     * playlist and still says what comes next.
     */
    static byte[] getState() {
        Wire.Writer previous = new Wire.Writer();
        previous.varint(1, 0);
        Wire.Writer next = new Wire.Writer();
        next.varint(1, 3);
        Wire.Writer request = new Wire.Writer();
        request.message(1, previous);
        request.message(2, next);
        return request.toByteArray();
    }

    /** {@code GetErrorRequest} has no fields. */
    static byte[] getError() {
        return new byte[0];
    }

    static byte[] playContext(String contextUri, String skipToTrackUri) {
        Wire.Writer context = new Wire.Writer();
        context.string(3, contextUri);
        context.string(4, "context://" + contextUri);

        Wire.Writer prepare = new Wire.Writer();
        prepare.message(1, context);
        if (skipToTrackUri != null) {
            Wire.Writer skipTo = new Wire.Writer();
            skipTo.string(4, skipToTrackUri);
            Wire.Writer options = new Wire.Writer();
            options.message(3, skipTo);
            prepare.message(2, options);
        }
        Wire.Writer request = new Wire.Writer();
        request.message(1, prepare);
        return request.toByteArray();
    }

    static byte[] playOrder(String contextUri, List<String> trackUris) {
        Wire.Writer page = new Wire.Writer();
        for (String trackUri : trackUris) {
            Wire.Writer track = new Wire.Writer();
            track.string(1, trackUri);
            page.message(1, track);
        }

        // No url here: an explicit page identifies the context on its own, and report 2.2's
        // fromTrackUris recipe (context{3 uri, 1 pages[...]}) omits it. Risk 7 warns that a
        // resolvable url could let the core re-resolve the context and drop this page.
        Wire.Writer context = new Wire.Writer();
        context.message(1, page);
        context.string(3, contextUri);

        Wire.Writer prepare = new Wire.Writer();
        prepare.message(1, context);

        Wire.Writer shufflingContext = new Wire.Writer();
        shufflingContext.bool(1, false);
        Wire.Writer overrides = new Wire.Writer();
        overrides.message(1, shufflingContext);

        Wire.Writer trackIndex = new Wire.Writer();
        trackIndex.varint(1, 0);
        Wire.Writer skipTo = new Wire.Writer();
        skipTo.message(5, trackIndex);

        Wire.Writer options = new Wire.Writer();
        options.message(3, skipTo);
        options.message(7, overrides);
        prepare.message(2, options);

        Wire.Writer request = new Wire.Writer();
        request.message(1, prepare);
        return request.toByteArray();
    }

    /**
     * {@code Play{prepare{context{3 uri, 1 pages[{1 tracks[{1 uri, 2 uid}]}]}, options{3 skip_to{3
     * track_uid}}}}}: {@code tracks} as the list's one explicit page, played from the track with
     * {@code skipToUid} (unavailable report, section 7.1). Like {@link #playOrder} it has no url, and
     * unlike it no {@code player_options_override}, so the user's shuffle stays. A track without a
     * uid goes without one.
     */
    static byte[] playPage(String contextUri, List<ContextTrack> tracks, String skipToUid) {
        Wire.Writer page = new Wire.Writer();
        for (ContextTrack track : tracks) {
            Wire.Writer contextTrack = new Wire.Writer();
            contextTrack.string(1, track.uri);
            if (track.uid != null) contextTrack.string(2, track.uid);
            page.message(1, contextTrack);
        }
        Wire.Writer context = new Wire.Writer();
        context.message(1, page);
        context.string(3, contextUri);

        Wire.Writer skipTo = new Wire.Writer();
        skipTo.string(3, skipToUid);
        Wire.Writer options = new Wire.Writer();
        options.message(3, skipTo);

        Wire.Writer prepare = new Wire.Writer();
        prepare.message(1, context);
        prepare.message(2, options);
        Wire.Writer request = new Wire.Writer();
        request.message(1, prepare);
        return request.toByteArray();
    }

    static byte[] setShuffling(boolean on) {
        Wire.Writer request = new Wire.Writer();
        request.bool(1, on);
        return request.toByteArray();
    }

    static byte[] addToQueue(String trackUri) {
        Wire.Writer track = new Wire.Writer();
        track.string(1, trackUri);
        Wire.Writer request = new Wire.Writer();
        request.message(1, track);
        return request.toByteArray();
    }

    static byte[] setQueue(List<String> nextTrackUris, long queueRevision) {
        Wire.Writer request = new Wire.Writer();
        for (String uri : nextTrackUris) {
            request.message(1, providedTrack(uri));
        }
        request.message(1, providedTrack("spotify:delimiter"));
        request.varint(3, queueRevision);
        return request.toByteArray();
    }

    /**
     * {@code PlayAsNextInQueueRequest{1 tracks: [ContextTrack{1 uri}]}}: the tracks go into the
     * queue to play next, in order (unavailable report, section 3.4). The answer is a
     * {@code ResponseWithReasons}, which {@link #parseResult} reads.
     */
    static byte[] playAsNextInQueue(List<String> trackUris) {
        Wire.Writer request = new Wire.Writer();
        for (String uri : trackUris) {
            Wire.Writer track = new Wire.Writer();
            track.string(1, uri);
            request.message(1, track);
        }
        return request.toByteArray();
    }

    /** {@code ProvidedTrack}: a context track carried by {@code provider "context"}. */
    private static Wire.Writer providedTrack(String uri) {
        Wire.Writer contextTrack = new Wire.Writer();
        contextTrack.string(1, uri);
        Wire.Writer providedTrack = new Wire.Writer();
        providedTrack.message(1, contextTrack);
        providedTrack.string(4, "context");
        return providedTrack;
    }

    static byte[] playlistGet(String uri, int start, int length, boolean countsOnly) {
        return playlistRequest(uri, start, length, false);
    }

    /**
     * The same page with the songs that can't play too, and each item's row id and playability
     * (unavailable report, sections 1.3 and 7.4): {@code show_unavailable}, then {@code policy{2
     * track{1 track{5 playable, 13 is_local}}, 4 item{1 uri, 9 row_id}}}. The core decorates only
     * what a policy asks for, so {@code is_local} is asked for too.
     */
    static byte[] playlistPlayability(String uri, int start, int length) {
        return playlistRequest(uri, start, length, true);
    }

    private static byte[] playlistRequest(String uri, int start, int length, boolean playability) {
        Wire.Writer query = new Wire.Writer();
        query.bytes(1, packedVarints(4, 3, 7, 6)); // NOT_BANNED, ARTIST_NOT_BANNED, NOT_RECOMMENDATION, NOT_EPISODE
        query.bool(8, playability);
        if (length >= 0) {
            Wire.Writer range = new Wire.Writer();
            range.varint(1, start);
            range.varint(2, length);
            query.message(4, range);
        }

        Wire.Writer playlist = new Wire.Writer();
        playlist.bool(49, true);
        Wire.Writer item = new Wire.Writer();
        item.bool(1, true);
        Wire.Writer policy = new Wire.Writer();
        policy.message(1, playlist);
        if (playability) {
            Wire.Writer track = new Wire.Writer();
            track.bool(5, true);
            track.bool(13, true);
            Wire.Writer playlistTrack = new Wire.Writer();
            playlistTrack.message(1, track);
            policy.message(2, playlistTrack);
            item.bool(9, true);
        }
        policy.message(4, item);

        Wire.Writer request = new Wire.Writer();
        request.string(1, uri);
        request.message(2, query);
        request.message(3, policy);
        return request.toByteArray();
    }

    private static byte[] packedVarints(int... values) {
        Wire.Writer packed = new Wire.Writer();
        for (int value : values) {
            packed.rawVarint(value);
        }
        return packed.toByteArray();
    }

    static byte[] getEntity(String uri) {
        Wire.Writer request = new Wire.Writer();
        request.string(1, uri);
        return request.toByteArray();
    }

    // YourLibraryService (sp://esperanto/spotify.your_library_esperanto.proto.YourLibraryService/All), 9.1.80.2221
    // Request   1 header, 4 predefined_playlist_configs, 5 update_throttling
    // Header    11 skip, 12 length (0 = empty page), 14 filters{1 packed enum}, 16 folder_id (int64),
    //           17 all_playlists, 18 total_count, 22 separate_pinned_items, 25 num_link_types_in_playlists,
    //           26 ignore_pinning
    // Filter    0 ALBUM, 1 ARTIST, 2 PLAYLIST, 3 SHOW, 4 BOOK, 100 DOWNLOADED, 101 WRITABLE, 102 BY_YOU
    // Response  1 header{9 remaining_entities, 12 is_loading, 17 total_count}, 2 entity*, 3 pinned_entity*,
    //           98 status_code (200 = OK), 99 error
    // Entity    1 entity_info{2 name, 3 uri, 6 image_uri}; case 2 album, 3 artist, 4 playlist (Liked Songs too),
    //           6 folder
    // Playlist  12 number_of_items_per_link_type*{1 link_type (4 TRACK, 63 EPISODE), 2 num_items}
    // Folder    2 number_of_playlists, 3 number_of_folders; folder uri spotify:user:<u>:folder:<16 hex> = folder_id

    /**
     * Every playlist, with folders flattened, and every saved album, in one page: {@code header{12
     * length 0x7fffffff, 14 filters[PLAYLIST, ALBUM], 17 all_playlists, 25 num_link_types_in_playlists,
     * 26 ignore_pinning}}. A length of 0 would be an empty page. It asks for no predefined playlists,
     * so the Library's own Liked Songs row stays out.
     */
    static byte[] yourLibraryAll() {
        Wire.Writer filters = new Wire.Writer();
        filters.bytes(1, packedVarints(FILTER_PLAYLIST, FILTER_ALBUM));
        Wire.Writer header = new Wire.Writer();
        header.varint(12, Integer.MAX_VALUE);
        header.message(14, filters);
        header.bool(17, true);
        header.bool(25, true);
        header.bool(26, true);
        Wire.Writer request = new Wire.Writer();
        request.message(1, header);
        return request.toByteArray();
    }

    /** The four uris the app treats as Liked Songs ({@code Lp/x46;->E} in 9.1.80.2221). */
    static boolean isLikedSongs(String uri) {
        return LIKED_SONGS.equals(uri)
                || "spotify:collection:tracks".equals(uri)
                || "spotify:internal:collection:tracks".equals(uri)
                || uri.startsWith("spotify:user:") && uri.endsWith(":collection");
    }

    // ---- Response parsers ----

    /**
     * A playlist item as {@link #playlistPlayability} lists it. It's greyed out when
     * {@code isPlayable} is false; a plain listing leaves everything but the uri at its default.
     */
    static final class PlaylistItem {
        String uri;
        String rowId;
        /** {@code TrackMetadata.playable}. */
        boolean playable;
        /**
         * {@code TrackPlayState.is_playable}: true unless the item says false, as the app reads it
         * ({@code Lp/omz;->b} 134 to 144).
         */
        boolean isPlayable = true;
        /** {@code PlayabilityRestriction}, such as 4 NOT_IN_CATALOGUE; 0 UNKNOWN when absent. */
        int restriction;
        /** {@code TrackMetadata.is_local}. */
        boolean local;
    }

    /** A page of a playlist or Liked Songs: its total {@code length} and the uris read so far. */
    static final class PlaylistPage {
        int length;
        List<String> uris = new ArrayList<>();
        /** Each item read so far, with its row id and playability when the request asked for them. */
        List<PlaylistItem> items = new ArrayList<>();
        boolean loading;
    }

    static PlaylistPage parsePlaylistGet(byte[] playlistGetResponse) throws IOException {
        PlaylistPage page = new PlaylistPage();
        Wire.Reader response = new Wire.Reader(playlistGetResponse);
        int statusCode = 0;
        Wire.Reader data = null;
        while (response.next()) {
            switch (response.field()) {
                case 1:
                    statusCode = readStatusCode(response.message());
                    break;
                case 2:
                    data = response.message();
                    break;
                default:
                    response.skip();
            }
        }
        if (statusCode == 403 || statusCode == 404 || statusCode == 451) {
            return page;
        }
        if (statusCode < 200 || statusCode > 299) {
            throw new IOException("status " + statusCode);
        }
        if (data != null) {
            readPlaylistData(data, page);
        }
        return page;
    }

    private static int readStatusCode(Wire.Reader status) throws IOException {
        int statusCode = 0;
        while (status.next()) {
            if (status.field() == 1) {
                statusCode = (int) status.varint();
            } else {
                status.skip();
            }
        }
        return statusCode;
    }

    private static void readPlaylistData(Wire.Reader data, PlaylistPage page) throws IOException {
        while (data.next()) {
            switch (data.field()) {
                case 1:
                    readPlaylistItem(data.message(), page);
                    break;
                case 4:
                    page.length = (int) data.varint();
                    break;
                case 6:
                    page.loading = data.varint() != 0;
                    break;
                default:
                    data.skip();
            }
        }
    }

    /** {@code Item{4 track_metadata, 7 row_id, 8 track_play_state, 18 uri}}. */
    private static void readPlaylistItem(Wire.Reader reader, PlaylistPage page) throws IOException {
        PlaylistItem item = new PlaylistItem();
        while (reader.next()) {
            switch (reader.field()) {
                case 4:
                    readTrackMetadata(reader.message(), item);
                    break;
                case 7:
                    item.rowId = reader.string();
                    break;
                case 8:
                    readTrackPlayState(reader.message(), item);
                    break;
                case 18:
                    item.uri = reader.string();
                    break;
                default:
                    reader.skip();
            }
        }
        if (item.uri != null) page.uris.add(item.uri);
        page.items.add(item);
    }

    /** {@code TrackMetadata{6 playable, 11 is_local}}. */
    private static void readTrackMetadata(Wire.Reader metadata, PlaylistItem item) throws IOException {
        while (metadata.next()) {
            switch (metadata.field()) {
                case 6:
                    item.playable = metadata.varint() != 0;
                    break;
                case 11:
                    item.local = metadata.varint() != 0;
                    break;
                default:
                    metadata.skip();
            }
        }
    }

    /** {@code TrackPlayState{1 is_playable, 2 playability_restriction}}. */
    private static void readTrackPlayState(Wire.Reader playState, PlaylistItem item) throws IOException {
        while (playState.next()) {
            switch (playState.field()) {
                case 1:
                    item.isPlayable = playState.varint() != 0;
                    break;
                case 2:
                    item.restriction = (int) playState.varint();
                    break;
                default:
                    playState.skip();
            }
        }
    }

    /**
     * {@code SettingsState.show_unavailable_tracks} (17), the "Show unplayable songs" switch. While
     * it's off, lists leave out the songs that can't play (unavailable report, section 1.4).
     */
    static boolean parseShowUnavailableTracks(byte[] settingsState) throws IOException {
        boolean show = false;
        Wire.Reader reader = new Wire.Reader(settingsState);
        while (reader.next()) {
            if (reader.field() == 17) {
                show = reader.varint() != 0;
            } else {
                reader.skip();
            }
        }
        return show;
    }

    /**
     * Where a random song from the library can come from: Liked Songs, a playlist or a saved album.
     * The Home shortcuts picker lists them too, by name and cover.
     */
    static final class LibrarySource {
        String uri;
        /**
         * Its name and cover in Your Library, or null without one. The cover is as the core gives it,
         * such as {@code spotify:image:<id>}.
         */
        String name;
        String image;
        boolean album;
        /** A playlist's song count from Your Library, or -1 without one, as for albums and Liked Songs. */
        int trackCount = -1;
    }

    /** A {@code YourLibraryResponse}: whether it's still loading, and Liked Songs, then each playlist and album once. */
    static final class Library {
        boolean loading;
        List<LibrarySource> sources = new ArrayList<>();
    }

    /**
     * Reads a {@link #yourLibraryAll()} answer. {@code entity} and {@code pinned_entity} merge by uri,
     * first one wins, keeping albums and playlists; folders are dropped, since {@code all_playlists}
     * lists their playlists. Liked Songs comes first, once, under {@link #LIKED_SONGS}. A status other
     * than 200 throws with the core's error.
     */
    static Library parseYourLibrary(byte[] yourLibraryResponse) throws IOException {
        Library library = new Library();
        Map<String, LibrarySource> found = new LinkedHashMap<>();
        int statusCode = 0;
        String error = "";
        Wire.Reader response = new Wire.Reader(yourLibraryResponse);
        while (response.next()) {
            switch (response.field()) {
                case 1:
                    library.loading = readIsLoading(response.message());
                    break;
                case 2:
                case 3:
                    readLibraryEntity(response.message(), found);
                    break;
                case 98:
                    statusCode = (int) response.varint();
                    break;
                case 99:
                    error = response.string();
                    break;
                default:
                    response.skip();
            }
        }
        if (statusCode != 200) throw new IOException("status " + statusCode + (error.isEmpty() ? "" : ": " + error));
        LibrarySource likedSongs = new LibrarySource();
        likedSongs.uri = LIKED_SONGS;
        library.sources.add(likedSongs);
        library.sources.addAll(found.values());
        return library;
    }

    private static boolean readIsLoading(Wire.Reader header) throws IOException {
        boolean loading = false;
        while (header.next()) {
            if (header.field() == 12) {
                loading = header.varint() != 0;
            } else {
                header.skip();
            }
        }
        return loading;
    }

    /** The case comes from the tag, never the content: a member can be an empty message. */
    private static void readLibraryEntity(Wire.Reader entity, Map<String, LibrarySource> found) throws IOException {
        LibrarySource source = new LibrarySource();
        int kind = 0;
        while (entity.next()) {
            switch (entity.field()) {
                case 1:
                    readEntityInfo(entity.message(), source);
                    break;
                case ENTITY_ALBUM:
                    kind = ENTITY_ALBUM;
                    entity.skip();
                    break;
                case ENTITY_PLAYLIST:
                    kind = ENTITY_PLAYLIST;
                    source.trackCount = readTrackCount(entity.message());
                    break;
                default:
                    entity.skip();
            }
        }
        if (kind == 0 || source.uri == null || source.uri.isEmpty() || isLikedSongs(source.uri)) return;
        source.album = kind == ENTITY_ALBUM;
        found.putIfAbsent(source.uri, source);
    }

    private static void readEntityInfo(Wire.Reader entityInfo, LibrarySource source) throws IOException {
        while (entityInfo.next()) {
            switch (entityInfo.field()) {
                case 2:
                    source.name = entityInfo.string();
                    break;
                case 3:
                    source.uri = entityInfo.string();
                    break;
                case 6:
                    source.image = entityInfo.string();
                    break;
                default:
                    entityInfo.skip();
            }
        }
    }

    /** The TRACK entry of {@code number_of_items_per_link_type}, or -1 when there's none. */
    private static int readTrackCount(Wire.Reader playlist) throws IOException {
        int tracks = -1;
        while (playlist.next()) {
            if (playlist.field() != 12) {
                playlist.skip();
                continue;
            }
            Wire.Reader count = playlist.message();
            long linkType = 0;
            long items = 0;
            while (count.next()) {
                if (count.field() == 1) {
                    linkType = count.varint();
                } else if (count.field() == 2) {
                    items = count.varint();
                } else {
                    count.skip();
                }
            }
            if (linkType == LINK_TYPE_TRACK) tracks = (int) items;
        }
        return tracks;
    }

    static List<String> parseAlbumTracks(byte[] getEntityResponse) throws IOException {
        List<String> uris = new ArrayList<>();
        Wire.Reader response = new Wire.Reader(getEntityResponse);
        while (response.next()) {
            if (response.field() == 1) {
                readMetadataItem(response.message(), uris);
            } else {
                response.skip();
            }
        }
        return uris;
    }

    private static void readMetadataItem(Wire.Reader item, List<String> uris) throws IOException {
        while (item.next()) {
            if (item.field() == 3) { // oneof case 3: the album
                readAlbum(item.message(), uris);
            } else {
                item.skip();
            }
        }
    }

    private static void readAlbum(Wire.Reader album, List<String> uris) throws IOException {
        while (album.next()) {
            if (album.field() == 11) {
                readDisc(album.message(), uris);
            } else {
                album.skip();
            }
        }
    }

    private static void readDisc(Wire.Reader disc, List<String> uris) throws IOException {
        while (disc.next()) {
            if (disc.field() == 3) {
                readTrack(disc.message(), uris);
            } else {
                disc.skip();
            }
        }
    }

    private static void readTrack(Wire.Reader track, List<String> uris) throws IOException {
        while (track.next()) {
            if (track.field() == 1) {
                uris.add("spotify:track:" + base62(track.bytes()));
            } else {
                track.skip();
            }
        }
    }

    /**
     * A song as Spotify's core describes it, for the lookup of a version that plays in the user's
     * country when the Web API doesn't list the song in their market (unavailable report, section 5.2).
     */
    static final class Track {
        String name = "";
        List<Artist> artists = new ArrayList<>();
        int durationMillis;
        boolean explicit;
        /** Its first {@code external_id} of type {@code isrc}, or "" without one. */
        String isrc = "";
        /** Other instances of the same song, as {@code spotify:track:} uris. */
        List<String> alternatives = new ArrayList<>();
    }

    /** One of a {@link Track}'s artists: the Web API's id for it, base62 like a track's, or "" without a gid. */
    static final class Artist {
        String id = "";
        String name = "";
    }

    /**
     * Reads a {@code GetEntityResponse{1 item}} for a song: the item's case 4, {@code Metadata$Track{2
     * name, 4 artist[]{1 gid, 2 name}, 7 duration, 9 explicit, 10 external_id[]{1 type, 2 id}, 13
     * alternative[]{1 gid}}}. An item with the core's error, case 1, or without a song throws.
     */
    static Track parseTrack(byte[] getEntityResponse) throws IOException {
        Wire.Reader response = new Wire.Reader(getEntityResponse);
        while (response.next()) {
            if (response.field() != 1) {
                response.skip();
                continue;
            }
            Wire.Reader item = response.message();
            while (item.next()) {
                if (item.field() == 4) return readSong(item.message());
                if (item.field() == 1) throw new IOException("error " + sint32(item.varint()));
                item.skip();
            }
        }
        throw new IOException("no song in the answer");
    }

    private static Track readSong(Wire.Reader reader) throws IOException {
        Track song = new Track();
        while (reader.next()) {
            switch (reader.field()) {
                case 2:
                    song.name = reader.string();
                    break;
                case 4:
                    song.artists.add(readArtist(reader.message()));
                    break;
                case 7:
                    song.durationMillis = sint32(reader.varint());
                    break;
                case 9:
                    song.explicit = reader.varint() != 0;
                    break;
                case 10:
                    readExternalId(reader.message(), song);
                    break;
                case 13:
                    readTrack(reader.message(), song.alternatives); // its gid as a uri, as in an album
                    break;
                default:
                    reader.skip();
            }
        }
        return song;
    }

    private static Artist readArtist(Wire.Reader reader) throws IOException {
        Artist artist = new Artist();
        while (reader.next()) {
            switch (reader.field()) {
                case 1:
                    artist.id = base62(reader.bytes());
                    break;
                case 2:
                    artist.name = reader.string();
                    break;
                default:
                    reader.skip();
            }
        }
        return artist;
    }

    /** Keeps the first {@code ExternalId{1 type, 2 id}} whose type is {@code isrc}. */
    private static void readExternalId(Wire.Reader reader, Track song) throws IOException {
        String type = "";
        String id = "";
        while (reader.next()) {
            switch (reader.field()) {
                case 1:
                    type = reader.string();
                    break;
                case 2:
                    id = reader.string();
                    break;
                default:
                    reader.skip();
            }
        }
        if (song.isrc.isEmpty() && "isrc".equalsIgnoreCase(type)) song.isrc = id;
    }

    /**
     * A {@code sint32}, which the wire carries zigzag encoded, such as the duration and the item's
     * error (their message info in the APK says type 15, SINT32).
     */
    private static int sint32(long zigzag) {
        return (int) (zigzag >>> 1) ^ -(int) (zigzag & 1);
    }

    /** Encodes a 16 byte gid as 22 zero padded base62 characters, big endian. */
    static String base62(byte[] gid) {
        BigInteger value = new BigInteger(1, gid);
        BigInteger base = BigInteger.valueOf(62);
        StringBuilder encoded = new StringBuilder();
        while (value.signum() > 0) {
            BigInteger[] divRem = value.divideAndRemainder(base);
            encoded.append(BASE62_ALPHABET.charAt(divRem[1].intValue()));
            value = divRem[0];
        }
        while (encoded.length() < 22) {
            encoded.append('0');
        }
        return encoded.reverse().toString();
    }

    static int parseResult(byte[] responseWithReasons) throws IOException {
        Wire.Reader reader = new Wire.Reader(responseWithReasons);
        int error = 0;
        while (reader.next()) {
            if (reader.field() == 1) {
                error = (int) reader.varint();
            } else {
                reader.skip();
            }
        }
        return error;
    }
}
