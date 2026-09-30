package app.spicetify.extension.spotify.extensions;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Unavailable songs (unavailable songs report, sections 2, 4 and 7.1). A tap on a song Spotify greys
 * out first goes on into Spotify's own play path, as a playable song's does, since greying can be
 * stale. Only when Spotify refuses it for a region reason, or hasn't played it within about 2 s, does
 * a version that plays in the user's country ({@link AvailableVersions}) play instead: in the song's
 * place in its list when the list can be read, otherwise alone. Spotify's core and backend still
 * decide what plays, and nothing here touches its licensing checks.
 * <p>
 * Hook B1's bridge, {@code UnavailableRowBridge}, hands {@link #onGreyedRowTap} each tap that the
 * default track row refuses. With Spotify's omni play flag on, track taps never reach that refusal,
 * and hook B2 hands {@link #onOmniRow} each one instead. On the phone, a greyed-out row can also pass
 * the row's own check and go on into list play, whose skip to the row the core refuses with code 22,
 * so hook L hands {@link #onListPlay} every list play. While the extension is on, it listens to the
 * bridge's player state and player error streams, which keeps them open, and Spotify's answer to a
 * tap comes on them.
 * <p>
 * Threading: a tap or a list play comes on the list's thread, the main thread, and returns at once,
 * since it only posts to the bridge thread. Everything after that runs on the bridge thread, which
 * alone uses {@link #pending} and {@link #listPlay}: the answer, the wait's end, the lookup's outcome
 * (the lookup itself runs on the Web API thread), the list's pages and the Play. Toasts are posted to
 * the main thread.
 */
public final class UnavailableSongs {
    /** How long Spotify has to play the original or refuse it (report 7.1, step 2). */
    private static final long ANSWER_MILLIS = 2000;
    /** A code 22 less than this many ms after a list play is the core refusing it. */
    private static final long LIST_PLAY_MILLIS = 3000;
    /** A list is read this many songs at a time, as Shuffle+ reads one. */
    private static final int PAGE = 500;
    /** {@code PlayabilityRestriction}'s names by number (unavailable songs report, section 1.1). */
    private static final String[] RESTRICTIONS = {"UNKNOWN", "NO_RESTRICTION", "EXPLICIT_CONTENT", "AGE_RESTRICTED",
            "NOT_IN_CATALOGUE", "NOT_AVAILABLE_OFFLINE", "PREMIUM_ONLY"};
    private static final String ID = Extensions.UNAVAILABLE_SONGS;
    private static final PlayerBridge.StateListener STATES = UnavailableSongs::onState;
    private static final PlayerBridge.ErrorListener ERRORS = UnavailableSongs::onError;

    /** The latest tap, from its post until its outcome is told, or null. Only the bridge thread uses it. */
    private static Tap pending;
    /** The last list play, until a code 22 follows it or off forgets it, or null. Only the bridge thread uses it. */
    private static ListPlay listPlay;
    /** The last greyed-out row omni play let through, as its uri and row id, and when. Guarded by the class. */
    private static String omniRow;
    private static long omniRowAt;

    private UnavailableSongs() {}

    static void register() {
        Extensions.onSwitch(ID, UnavailableSongs::onSwitch);
    }

    /**
     * On, it listens to both streams; off, it stops and forgets a tap in progress, the last list play
     * and the last omni row. Synchronized, as Shuffle+'s is, so an on from Spotify's start can't add
     * the listeners after an off from the switch has removed them.
     */
    private static synchronized void onSwitch(Context context, boolean on) {
        if (!on) {
            PlayerBridge.removeStateListener(STATES);
            PlayerBridge.removeErrorListener(ERRORS);
            PlayerBridge.post(() -> {
                pending = null;
                listPlay = null;
            });
            omniRow = null;
        } else if (Extensions.isOn(context, ID)) {
            PlayerBridge.addStateListener(STATES);
            PlayerBridge.addErrorListener(ERRORS);
        }
    }

    /**
     * Hook B1: true lets the tap go on into Spotify's own play path, which tries the original. That's
     * only while Unavailable songs is on, for a Spotify song, not a local file or an episode, in a row
     * that isn't banned and that Spotify greyed out as {@code NOT_IN_CATALOGUE} or {@code UNKNOWN}. The
     * tap is then posted to the bridge thread, and this returns at once. Anything else, and any
     * Throwable, is false, which keeps Spotify's refusal. While on, each call is logged, for the phone checks.
     */
    public static boolean onGreyedRowTap(String listUri, String trackUri, String rowId, String restriction,
            boolean banned) {
        return onGreyedRowTap(listUri, trackUri, rowId, restriction, banned, WebApi.HTTP, ANSWER_MILLIS);
    }

    /** The same, looking through {@code http} and giving Spotify {@code answerMillis} to answer. */
    static boolean onGreyedRowTap(String listUri, String trackUri, String rowId, String restriction, boolean banned,
            WebApi.Http http, long answerMillis) {
        try {
            Context context = Extensions.appContext();
            if (context == null || !Extensions.isOn(context, ID)) return false;
            return tryRow(context, "refused row: ", listUri, trackUri, rowId, restriction, banned, http, answerMillis);
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't answer a tap on a greyed-out song", e);
            return false;
        }
    }

    /**
     * Hook B2. With Spotify's remote flag {@code enable_omni_play_intent_handler} on, a tap on any track
     * row goes to omni play, which checks nothing in Java, so B1 never runs (unavailable songs report,
     * sections 2.2 and 2.3). Omni play goes on whatever this does, so it only watches: a playable row is
     * left alone, a greyed-out one is logged as B1's are, and one B1 would let go on also posts its tap,
     * whose refusal then comes as a player error, code 22 on the phone. The same row again within
     * {@link #ANSWER_MILLIS} is the same tap. It returns at once, and any Throwable is logged.
     */
    public static void onOmniRow(String listUri, String trackUri, String rowId, String restriction, boolean banned,
            boolean playable) {
        onOmniRow(listUri, trackUri, rowId, restriction, banned, playable, WebApi.HTTP, ANSWER_MILLIS,
                SystemClock.elapsedRealtime());
    }

    /** The same, looking through {@code http}, giving Spotify {@code answerMillis}, and tapped at {@code now} in ms. */
    static void onOmniRow(String listUri, String trackUri, String rowId, String restriction, boolean banned,
            boolean playable, WebApi.Http http, long answerMillis, long now) {
        try {
            if (playable && !banned) return;
            Context context = Extensions.appContext();
            if (context == null || !Extensions.isOn(context, ID) || repeated(trackUri + " " + rowId, now)) return;
            tryRow(context, "refused row (omni): ", listUri, trackUri, rowId, restriction, banned, http,
                    answerMillis);
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't follow a tap on a greyed-out song", e);
        }
    }

    /**
     * Hook L, at the entry of list play ({@code Lp/vy70;->d}), for every list play: the list's uri and
     * the row id it skips to, or null when it skips to a song. On the phone, a tap on a greyed-out song
     * passed the row's own check and came here, and the core refused the skip with code 22 and no data
     * (the diagnostic build's probe). While on, this posts the list play to the bridge thread, which
     * keeps the last one for {@link #onError}, unless it's the list play of a pending tap's own row: B1
     * lets its tap go on into one, and that tap already follows it. It's cheap, logs nothing and never
     * blocks. Any Throwable is logged.
     */
    public static void onListPlay(String listUri, String rowId) {
        onListPlay(listUri, rowId, WebApi.HTTP);
    }

    /** The same, looking through {@code http}. */
    static void onListPlay(String listUri, String rowId, WebApi.Http http) {
        try {
            Context context = Extensions.appContext();
            if (context == null || !Extensions.isOn(context, ID)) return;
            ListPlay play = new ListPlay(context, listUri, rowId, http, SystemClock.elapsedRealtime());
            PlayerBridge.post(() -> {
                Tap tap = pending;
                listPlay = tap != null && rowId != null && rowId.equals(tap.rowId) ? null : play;
            });
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't follow a list play", e);
        }
    }

    /**
     * {@code UnavailableRowBridge}'s catch handlers hand over what they caught at {@code where}. While
     * on, it goes on the status line, since logcat is filtered on the test phone. Never throws.
     */
    public static void bridgeFailed(String where, Throwable error) {
        try {
            Context context = Extensions.appContext();
            if (context != null && Extensions.isOn(context, ID)) {
                Extensions.status(context, ID, "bridge failed at " + where + ": " + error);
            }
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't report a bridge failure at " + where, e);
        }
    }

    /**
     * Logs a greyed-out row's tap after {@code prefix}. For a song another version could stand in for,
     * it also posts the tap to the bridge thread and returns true.
     */
    private static boolean tryRow(Context context, String prefix, String listUri, String trackUri, String rowId,
            String restriction, boolean banned, WebApi.Http http, long answerMillis) {
        boolean trying = !banned && trackUri != null && trackUri.startsWith("spotify:track:")
                && ("NOT_IN_CATALOGUE".equals(restriction) || "UNKNOWN".equals(restriction));
        Extensions.status(context, ID, prefix + trackUri + " in " + listUri + " (" + restriction
                + (banned ? ", banned" : "") + (trying ? "), trying it" : "), left to Spotify"));
        if (!trying) return false;
        Tap tap = new Tap(context, listUri, trackUri, rowId, http, answerMillis);
        PlayerBridge.post(tap::start);
        return true;
    }

    /**
     * Whether {@code row} is the greyed-out omni row let through less than {@link #ANSWER_MILLIS}
     * before {@code now}, a double tap; if not, it's let through now. Omni play's resumes run its
     * method again too, but with no row, which the bridge skips.
     */
    private static synchronized boolean repeated(String row, long now) {
        if (row.equals(omniRow) && now - omniRowAt < ANSWER_MILLIS) return true;
        omniRow = row;
        omniRowAt = now;
        return false;
    }

    /**
     * A state that plays the song, or its row with another instance of it, means Spotify played the
     * original. A paused or stopped state that names it doesn't, since the player may have stopped on
     * the song it refused, and no state does once Spotify has refused it for a region reason.
     */
    private static void onState(Esperanto.PlayerState state) {
        Tap tap = pending;
        if (tap == null || tap.playing || tap.refused) return;
        if (!state.playing || state.paused || !tap.isOriginal(state)) return;
        pending = null;
        Extensions.status(tap.appContext, ID, "Spotify played the original: " + state.trackUri);
    }

    /**
     * Report 4's region refusal: an unplayable code, 19 to 22, for the song or for no song, with a
     * region reason among its reasons. Code 22, a skip to a song the list was played without, needs no
     * reason, since the row already gave one. A refusal starts the lookup, or joins the one the wait
     * started, and from then on outranks any state. Before a lookup starts, another reason ends the
     * tap with no lookup. With no tap pending, code 22 may be the refusal of the last list play
     * ({@link #followListPlay}). Other errors are left alone.
     */
    private static void onError(Esperanto.PlayerError error) {
        Tap tap = pending;
        if (tap == null && error.code == 22) followListPlay();
        if (tap == null || error.code < 19 || error.code > 22) return;
        if (error.trackUri != null && !error.trackUri.isEmpty() && !error.trackUri.equals(tap.trackUri)) return;
        if (error.code == 22 || region(error.reasons)) {
            tap.refused = true;
            tap.look("GetError " + error.code); // starts no second lookup
        } else if (!tap.looking) {
            pending = null;
            Extensions.status(tap.appContext, ID, "Spotify refused it for another reason: " + error.reasons);
        }
    }

    /**
     * Code 22 with no tap pending: a list play from a row less than {@link #LIST_PLAY_MILLIS} before
     * was refused, and the list's listing says whether another version could stand in. Each list play
     * is followed once.
     */
    private static void followListPlay() {
        ListPlay play = listPlay;
        listPlay = null;
        if (play == null || play.rowId == null || SystemClock.elapsedRealtime() - play.at >= LIST_PLAY_MILLIS) return;
        play.read(Esperanto.isLikedSongs(play.listUri) ? Esperanto.LIKED_SONGS : play.listUri);
    }

    /**
     * Whether {@code reasons}, one reason or several split by commas, names a region one (report 4).
     * Whole reasons only, so {@code not_available_offline} isn't {@code not_available}.
     */
    private static boolean region(String reasons) {
        if (reasons == null) return false;
        for (String reason : reasons.split("[^A-Za-z0-9_/]+")) {
            String trimmed = reason.trim();
            if (trimmed.equals("not_available") || trimmed.equals("not_available_in_current_region")) return true;
        }
        return false;
    }

    /**
     * A list play hook L saw: its list, the row it skipped to, and when, in ms. Once a code 22 refuses
     * it, it pages through the list's playability listing until the row turns up. A tap or a list play
     * after it takes over, and it stops quietly. On the bridge thread.
     */
    private static final class ListPlay {
        static final String REFUSED = "refused row (list play): ";
        final Context appContext;
        final String listUri;
        final String rowId;
        final WebApi.Http http;
        final long at;
        /** The listing's items read so far, where its next page starts. */
        int start;
        int retries;

        ListPlay(Context appContext, String listUri, String rowId, WebApi.Http http, long at) {
            this.appContext = appContext;
            this.listUri = listUri;
            this.rowId = rowId;
            this.http = http;
            this.at = at;
        }

        boolean stale() {
            return pending != null || listPlay != null;
        }

        /**
         * Reads the page after the items read so far, the greyed-out songs too, until the row turns up,
         * the list's length, or an empty page. A list still loading is asked again later, as the tap's
         * read does.
         */
        void read(String list) {
            byte[] request = Esperanto.playlistPlayability(list, start, PAGE);
            PlayerBridge.call(Esperanto.PLAYLIST, "Get", request, RandomSong.step(body -> {
                if (stale()) return;
                Esperanto.PlaylistPage page = Esperanto.parsePlaylistGet(body);
                if (page.loading) {
                    if (retries++ == RandomSong.LOADING_RETRIES) throw new IOException("the list is still loading");
                    PlayerBridge.postDelayed(() -> read(list), RandomSong.LOADING_RETRY_MILLIS);
                    return;
                }
                for (Esperanto.PlaylistItem item : page.items) {
                    if (rowId.equals(item.rowId)) {
                        decide(item);
                        return;
                    }
                }
                start += page.items.size();
                if (!page.items.isEmpty() && start < page.length) {
                    read(list);
                } else {
                    Extensions.status(appContext, ID, REFUSED + "row " + rowId + " isn't in " + listUri + ", left to Spotify");
                }
            }, (reason, e) -> {
                if (!stale()) Extensions.status(appContext, ID, REFUSED + "couldn't read " + list + ": " + reason
                        + ", left to Spotify");
            }));
        }

        /**
         * Another version can stand in only for a song the row greys out as {@code NOT_IN_CATALOGUE} or
         * {@code UNKNOWN}, as for B1, that can't play and isn't a local file. Then the refusal is a tap's:
         * the lookup, then the version. Otherwise the line says what the row holds.
         */
        void decide(Esperanto.PlaylistItem item) {
            String restriction = item.restriction >= 0 && item.restriction < RESTRICTIONS.length
                    ? RESTRICTIONS[item.restriction] : String.valueOf(item.restriction);
            boolean trying = !item.isPlayable && !item.local && item.uri != null && item.uri.startsWith("spotify:track:")
                    && ("NOT_IN_CATALOGUE".equals(restriction) || "UNKNOWN".equals(restriction));
            if (!trying) {
                Extensions.status(appContext, ID, REFUSED + item.uri + " in " + listUri + " (" + restriction
                        + ", is_playable=" + item.isPlayable + ", is_local=" + item.local + "), left to Spotify");
                return;
            }
            Extensions.status(appContext, ID, REFUSED + item.uri + " in " + listUri + " (" + restriction + "), trying it");
            Tap tap = new Tap(appContext, listUri, item.uri, rowId, http, ANSWER_MILLIS);
            pending = tap;
            tap.refused = true;
            tap.look("GetError 22");
        }
    }

    /** One tap on a greyed-out row, from Spotify's answer to the version that plays. On the bridge thread. */
    private static final class Tap {
        final Context appContext;
        final String listUri;
        final String trackUri;
        final String rowId;
        final WebApi.Http http;
        final long answerMillis;
        /** The list's items read so far. */
        final List<Esperanto.PlaylistItem> items = new ArrayList<>();
        /** Once the lookup starts, a refusal or the wait's end starts no second one. */
        boolean looking;
        /** Once a version is found, a state under the row's uid may be the version's own, not the original. */
        boolean playing;
        /** Spotify refused the song for a region reason, so a state that names it now isn't playback. */
        boolean refused;
        int retries;

        Tap(Context appContext, String listUri, String trackUri, String rowId, WebApi.Http http, long answerMillis) {
            this.appContext = appContext;
            this.listUri = listUri;
            this.trackUri = trackUri;
            this.rowId = rowId;
            this.http = http;
            this.answerMillis = answerMillis;
        }

        /** Takes over from any tap before it, then waits for Spotify's answer. */
        void start() {
            pending = this;
            PlayerBridge.postDelayed(() -> {
                if (pending == this) look("no answer within " + answerMillis + " ms");
            }, answerMillis);
        }

        boolean isOriginal(Esperanto.PlayerState state) {
            return trackUri != null && trackUri.equals(state.trackUri)
                    || rowId != null && !rowId.isEmpty() && rowId.equals(state.trackUid);
        }

        void look(String why) {
            if (looking) return;
            looking = true;
            Extensions.status(appContext, ID, AvailableVersions.LOOKING + " (" + why + ")");
            AvailableVersions.resolve(appContext, trackUri, http, System::currentTimeMillis,
                    new AvailableVersions.Callback() {
                        @Override
                        public void found(AvailableVersions.Version version) {
                            if (pending != Tap.this) return;
                            playing = true;
                            playInList(version);
                        }

                        @Override
                        public void none(String line) {
                            if (pending == Tap.this) end(line);
                        }

                        @Override
                        public void failed(String line, long retryAfterSeconds) {
                            if (pending == Tap.this) end(line);
                        }
                    });
        }

        /**
         * In the song's playlist or Liked Songs, the version takes the song's place (report 7.1, step 4).
         * Anywhere else, it plays alone.
         */
        void playInList(AvailableVersions.Version version) {
            String list = listUri != null && Esperanto.isLikedSongs(listUri) ? Esperanto.LIKED_SONGS : listUri;
            if (list != null && list.startsWith("spotify:playlist:")) {
                read(list, version);
            } else {
                playAlone(version, "not in a playlist");
            }
        }

        /**
         * Reads the page after the items read so far, the greyed-out songs too, until the list's
         * length. A list still loading is asked again later, and an empty page ends the reading, as in
         * Shuffle+. A list that can't be read plays the version alone.
         */
        void read(String list, AvailableVersions.Version version) {
            if (pending != this) return;
            byte[] request = Esperanto.playlistPlayability(list, items.size(), PAGE);
            PlayerBridge.call(Esperanto.PLAYLIST, "Get", request, RandomSong.step(body -> {
                if (pending != this) return;
                Esperanto.PlaylistPage page = Esperanto.parsePlaylistGet(body);
                if (page.loading) {
                    if (retries++ == RandomSong.LOADING_RETRIES) throw new IOException("the list is still loading");
                    PlayerBridge.postDelayed(() -> read(list, version), RandomSong.LOADING_RETRY_MILLIS);
                    return;
                }
                items.addAll(page.items);
                if (!page.items.isEmpty() && items.size() < page.length) {
                    read(list, version);
                } else {
                    playInPlace(list, version);
                }
            }, (reason, e) -> {
                if (pending != this) return;
                Log.w("Spicetify", "Couldn't read " + list + ", so the available version plays alone: " + reason, e);
                playAlone(version, "couldn't read " + list + ": " + reason);
            }));
        }

        /** Plays the list from the song's row, found by its row id or else its uri, with the version in it. */
        void playInPlace(String list, AvailableVersions.Version version) {
            int row = row();
            String uid = row < 0 ? null : items.get(row).rowId;
            if (uid == null || uid.isEmpty()) {
                playAlone(version, "the song isn't in " + list);
                return;
            }
            List<Esperanto.ContextTrack> tracks = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                Esperanto.PlaylistItem item = items.get(i);
                if (i != row && item.uri == null) continue;
                Esperanto.ContextTrack track = new Esperanto.ContextTrack();
                track.uri = i == row ? version.uri : item.uri;
                track.uid = item.rowId;
                tracks.add(track);
            }
            play(Esperanto.playPage(list, tracks, uid), version, true);
        }

        /** The song's row among the items read: by its row id, else by its uri; -1 without it. */
        private int row() {
            for (int i = 0; i < items.size(); i++) {
                if (rowId != null && rowId.equals(items.get(i).rowId)) return i;
            }
            for (int i = 0; i < items.size(); i++) {
                if (trackUri != null && trackUri.equals(items.get(i).uri)) return i;
            }
            return -1;
        }

        /** Plays the version alone, first saying why in the extensions log, since the tap's end won't. */
        void playAlone(AvailableVersions.Version version, String why) {
            Extensions.status(appContext, ID, "Playing " + version.uri + " alone: " + why);
            play(Esperanto.playContext(version.uri, null), version, false);
        }

        /** Plays the version. When Spotify won't play it in the list, it plays it alone instead. */
        void play(byte[] request, AvailableVersions.Version version, boolean inList) {
            PlayerBridge.call(Esperanto.CONTEXT_PLAYER, "Play", request, RandomSong.step(body -> {
                if (pending != this) return;
                int error = Esperanto.parseResult(body);
                if (error != Esperanto.OK) throw new IOException("Spotify answered error " + error);
                end(AvailableVersions.playing(version.title));
            }, (reason, e) -> {
                if (pending != this) return;
                if (inList) {
                    playAlone(version, "Spotify refused it in the list: " + reason);
                } else {
                    Log.w("Spicetify", "Couldn't play the available version " + version.uri + ": " + reason, e);
                    end("Couldn't play the available version: " + reason);
                }
            }));
        }

        /** The tap is over: {@code line} goes on the status line and in a Toast. */
        void end(String line) {
            pending = null;
            RandomSong.tell(appContext, ID, line);
        }
    }
}
