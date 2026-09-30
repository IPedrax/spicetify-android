package app.spicetify.extension.spotify.extensions;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Finds a version of a song that plays in the user's country, for a song Spotify won't play there
 * (unavailable songs report, sections 4 and 5): the one the Web API relinks it to, else one with the
 * same ISRC, else one with the same title and first artist. When the Web API answers 404 for the
 * song, it isn't in the catalog of the user's market at all, which is the usual case for these
 * songs. Spotify's core then describes it, as it does for the greyed-out row, and the core's
 * alternatives to it come before the two searches. Every request asks for the user's own market,
 * {@code market=from_token}, and only a candidate whose {@code is_playable} is true there is taken,
 * so Spotify still decides what plays. Only a country block looks further: a song held back for
 * Premium ({@code product}) or explicit content never does.
 * <p>
 * Each outcome is cached under the original's uri: a found version for 30 days, none for 3. A
 * lookup that can't finish caches nothing, and neither does one the Web API holds back. While a
 * 429's {@code Retry-After} lasts, {@link WebApi.Paced} fails a request at once, so a lookup answers
 * "try again" with the seconds left instead of waiting, and cached songs still resolve meanwhile.
 * Each step tried puts a line on Unavailable songs' status, which the phone checks read.
 * <p>
 * Threading: {@link #resolve} returns at once. The cache is read and written on the bridge thread,
 * the token and the core's details come over the bridge, the requests run one after another on the
 * Web API thread, at its pace, and the callback hears the outcome on the bridge thread.
 */
final class AvailableVersions {
    static final String LOOKING = "Looking for an available version";
    static final String NONE = "No available version in your country";
    static final String PREFERENCES = "spicetify_available_versions";
    private static final long FOUND_MILLIS = TimeUnit.DAYS.toMillis(30);
    private static final long NONE_MILLIS = TimeUnit.DAYS.toMillis(3);
    private static final long ISRC_WITHIN_MILLIS = 5000;
    private static final long TITLE_WITHIN_MILLIS = 3000;
    private static final String COULDNT = "Couldn't look for an available version: ";
    private static final String TRACK = "spotify:track:";
    /** Only a base62 id goes into the request's path. */
    private static final Pattern TRACK_URI = Pattern.compile("spotify:track:[0-9A-Za-z]+");
    /**
     * What names a version rather than the song: bracketed text, and a {@code " - "} part with a
     * version word or a year in it, such as {@code " - Remastered 2011"} or {@code " - Live at Wembley"}.
     */
    private static final Pattern VERSION = Pattern.compile("\\s*[(\\[][^)\\]]*[)\\]]"
            + "|\\s+-\\s+(?=(?:(?!\\s-\\s).)*?\\b(?:remaster\\w*|version|edit|\\w*mix\\w*|live|mono|stereo"
            + "|acoustic|demo|instrumental|karaoke|cover|single|radio|recorded|session|anniversary|deluxe|bonus"
            + "|feat\\w*|\\d{4})\\b).*", Pattern.CASE_INSENSITIVE);
    /** The kinds of version a title match takes only when the original is one too (report 5.4). */
    private static final Pattern KIND =
            Pattern.compile("\\b(live|karaoke|instrumental|cover)\\b", Pattern.CASE_INSENSITIVE);

    /** Hears how a lookup ended, on the bridge thread. */
    interface Callback {
        /** {@code version} plays in the user's country. It's never the original. */
        void found(Version version);

        /** There's nothing to play, and {@code line} says why. It's cached, for less time than a found version. */
        void none(String line);

        /**
         * The lookup couldn't finish, so nothing was cached and the next one tries again. {@code line}
         * says why; {@code retryAfterSeconds} is above 0 when the Web API asked to wait that long.
         */
        void failed(String line, long retryAfterSeconds);
    }

    /** A version that plays in the user's country. */
    static final class Version {
        final String uri;
        final String title;

        Version(String uri, String title) {
            this.uri = uri;
            this.title = title;
        }
    }

    private AvailableVersions() {}

    /** The status once {@code title}, a found version, plays. */
    static String playing(String title) {
        return "Playing an available version: " + title;
    }

    /**
     * Looks for a version of {@code originalUri} that plays in the user's country. It returns at
     * once, and never throws.
     */
    static void resolve(Context context, String originalUri, Callback callback) {
        resolve(context, originalUri, WebApi.HTTP, System::currentTimeMillis, callback);
    }

    /** The same through {@code http}, with the cache's time from {@code clock}, in milliseconds since the epoch. */
    static void resolve(Context context, String originalUri, WebApi.Http http, LongSupplier clock,
            Callback callback) {
        try {
            Context app = context.getApplicationContext();
            PlayerBridge.post(() -> start(app, originalUri, http, clock, callback));
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't start looking for an available version", e);
        }
    }

    /** On the bridge thread: the cache first, then the token, then the lookup on the Web API thread. */
    private static void start(Context context, String originalUri, WebApi.Http http, LongSupplier clock,
            Callback callback) {
        if (originalUri == null || !TRACK_URI.matcher(originalUri).matches()) {
            callback.failed(COULDNT + "not a Spotify song: " + originalUri, 0);
            return;
        }
        SharedPreferences cache = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        JSONObject cached = cached(cache.getString(originalUri, null), clock.getAsLong());
        if (cached != null) {
            tell(cached, callback);
            return;
        }
        WebApi.token(new PlayerBridge.Result() {
            @Override
            public void done(byte[] token) {
                String bearer = new String(token, StandardCharsets.UTF_8);
                WebApi.run(() -> PlayerBridge.post(answer(http, bearer, originalUri, null, cache, clock, callback)));
            }

            @Override
            public void failed(String reason) {
                callback.failed(COULDNT + reason, 0);
            }
        });
    }

    /**
     * On the Web API thread: looks up from the Web API's answer for the song, or from {@code described},
     * the core's, after that answer was a 404. It returns what the bridge thread does next: tell the
     * outcome, or, on the 404, ask the core.
     */
    private static Runnable answer(WebApi.Http http, String token, String originalUri, Esperanto.Track described,
            SharedPreferences cache, LongSupplier clock, Callback callback) {
        try {
            JSONObject outcome = described == null ? lookUp(http, token, originalUri)
                    : notInYourMarket(http, token, originalUri, described);
            if (outcome == null) return () -> describe(http, token, originalUri, cache, clock, callback);
            String saved = outcome.put("at", clock.getAsLong()).toString();
            return () -> {
                cache.edit().putString(originalUri, saved).apply();
                tell(outcome, callback);
            };
        } catch (WebApi.RateLimited e) {
            Log.w("Spicetify", "The Web API held a lookup back: " + e.getMessage());
            String line = e.quotaExceeded ? "Spotify's Web API quota is used up: try again after restarting Spotify"
                    : "Spotify's Web API asked to wait: try again "
                            + (e.retryAfterSeconds > 0 ? "in " + e.retryAfterSeconds + " s" : "later");
            return () -> callback.failed(line, e.quotaExceeded ? 0 : e.retryAfterSeconds);
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't look for an available version", e);
            // Not a JSONException's message: org.json quotes its whole input there.
            String reason = e instanceof JSONException ? "the Web API gave an answer that can't be read"
                    : e.getMessage() != null ? e.getMessage() : e.toString();
            return () -> callback.failed(COULDNT + reason, 0);
        }
    }

    /**
     * On the bridge thread, after the Web API's 404: asks Spotify's core for the song's details,
     * then goes back to the Web API thread with them. When the core can't give them, the lookup
     * can't finish, and the callback says so.
     */
    private static void describe(WebApi.Http http, String token, String originalUri, SharedPreferences cache,
            LongSupplier clock, Callback callback) {
        PlayerBridge.call(Esperanto.METADATA, "GetEntity", Esperanto.getEntity(originalUri), RandomSong.step(body -> {
            Esperanto.Track described = Esperanto.parseTrack(body);
            step("not in your market's catalog, so Spotify describes it: "
                    + (described.isrc.isEmpty() ? "no ISRC" : "ISRC " + described.isrc));
            WebApi.run(() -> PlayerBridge.post(answer(http, token, originalUri, described, cache, clock, callback)));
        }, (reason, e) -> {
            Log.w("Spicetify", "Spotify didn't give the details of " + originalUri + ": " + reason, e);
            callback.failed(COULDNT + "Spotify didn't give the song's details (" + reason + ")", 0);
        }));
    }

    /** A cached outcome that hasn't expired, or null. */
    private static JSONObject cached(String saved, long now) {
        if (saved == null) return null;
        try {
            JSONObject entry = new JSONObject(saved);
            long age = now - entry.getLong("at");
            return age >= 0 && age < (entry.has("uri") ? FOUND_MILLIS : NONE_MILLIS) ? entry : null;
        } catch (JSONException unreadable) {
            return null; // looked up again, and overwritten
        }
    }

    private static void tell(JSONObject outcome, Callback callback) {
        String uri = outcome.optString("uri");
        if (uri.isEmpty()) {
            callback.none(outcome.optString("none", NONE));
        } else {
            callback.found(new Version(uri, outcome.optString("title")));
        }
    }

    /**
     * On the Web API thread, the steps of report section 5, each request at the Web API's pace. It
     * returns what to cache: {@code {uri, title}} for a found version, or {@code {none}} with the
     * line, and null when the Web API answers 404 for the song, so the core describes it first. It
     * throws when a request fails or an answer can't be read, since then it doesn't know.
     */
    private static JSONObject lookUp(WebApi.Http http, String token, String originalUri)
            throws IOException, JSONException {
        JSONObject original;
        try {
            original = new JSONObject(http.get(trackUrl(originalUri), token));
        } catch (WebApi.NotFound notInYourMarket) {
            return null;
        }
        // Relinked: the root is the instance that plays here; linked_from is deprecated (report 5.1).
        if (playsHere(original, originalUri)) return found(original);
        if (original.optBoolean("is_playable", true)) return notByCountry("playable");
        JSONObject restrictions = original.optJSONObject("restrictions");
        String reason = restrictions == null ? "" : restrictions.optString("reason");
        if (!reason.isEmpty() && !"market".equals(reason)) return notByCountry(reason);
        return searches(http, token, originalUri, original);
    }

    /**
     * On the Web API thread, after the 404: each of the core's alternatives in turn, taken only when
     * Get Track in the user's market answers 200 with {@code is_playable} true, then the two searches
     * with the core's details. Another 404 just means that alternative isn't in the market either.
     */
    private static JSONObject notInYourMarket(WebApi.Http http, String token, String originalUri,
            Esperanto.Track described) throws IOException, JSONException {
        String alternatives = "alternatives " + described.alternatives.size() + ", ";
        // ponytail: no cap on the alternatives, asked one a second at the pace; cap them if the phone logs long lists.
        for (String alternative : described.alternatives) {
            JSONObject track;
            try {
                track = new JSONObject(http.get(trackUrl(alternative), token));
            } catch (WebApi.NotFound notHereEither) {
                continue;
            }
            if (playsHere(track, originalUri)) {
                step(alternatives + track.optString("uri") + " plays here");
                return found(track);
            }
        }
        step(alternatives + "none playable here");
        JSONArray artists = new JSONArray();
        for (Esperanto.Artist artist : described.artists) {
            artists.put(new JSONObject().put("id", artist.id).put("name", artist.name));
        }
        // The core's details in the Web API's shape, which the matching reads.
        return searches(http, token, originalUri, new JSONObject().put("name", described.name)
                .put("artists", artists).put("duration_ms", described.durationMillis)
                .put("explicit", described.explicit).put("external_ids", new JSONObject().put("isrc", described.isrc)));
    }

    /** The ISRC search, then the title and artist search, for {@code original} in the Web API's shape. */
    private static JSONObject searches(WebApi.Http http, String token, String originalUri, JSONObject original)
            throws IOException, JSONException {
        JSONObject ids = original.optJSONObject("external_ids");
        String isrc = ids == null ? "" : ids.optString("isrc");
        if (!isrc.isEmpty()) {
            JSONArray tracks = tracks(http.get(searchUrl("isrc:" + isrc), token));
            JSONObject match = searched("ISRC search", tracks, sameIsrc(original, originalUri, isrc, tracks));
            if (match != null) return found(match);
        }
        String title = withoutVersion(original.optString("name"));
        String artist = firstArtist(original).optString("name");
        if (!title.isEmpty() && !artist.isEmpty()) {
            String query = "track:\"" + title.replace("\"", "") + "\" artist:\"" + artist.replace("\"", "") + "\"";
            JSONArray tracks = tracks(http.get(searchUrl(query), token));
            JSONObject match = searched("title search", tracks, sameTitle(original, originalUri, tracks));
            if (match != null) return found(match);
        }
        return new JSONObject().put("none", NONE);
    }

    /**
     * Puts a search's step line on the status, such as "ISRC search 0" or "title search 3, no match",
     * and returns {@code match}.
     */
    private static JSONObject searched(String search, JSONArray tracks, JSONObject match) {
        step(search + " " + tracks.length() + (match != null ? ", found " + match.optString("uri")
                : tracks.length() > 0 ? ", no match" : ""));
        return match;
    }

    /** One step's line on Unavailable songs' status and in the extensions log, for the phone. None holds the token. */
    private static void step(String line) {
        Extensions.status(Extensions.appContext(), Extensions.UNAVAILABLE_SONGS, line);
    }

    /** Get Track for {@code uri} in the user's market. */
    private static String trackUrl(String uri) {
        return "https://api.spotify.com/v1/tracks/" + uri.substring(TRACK.length()) + "?market=from_token";
    }

    private static JSONObject found(JSONObject track) throws JSONException {
        return new JSONObject().put("uri", track.optString("uri")).put("title", track.optString("name"));
    }

    private static JSONObject notByCountry(String why) throws JSONException {
        return new JSONObject().put("none",
                "Spotify doesn't block it in your country (" + why + "), so no other version is looked for");
    }

    /** Ten tracks matching {@code query} that are available in the user's market. */
    private static String searchUrl(String query) {
        try {
            return "https://api.spotify.com/v1/search?q=" + URLEncoder.encode(query, "UTF-8")
                    + "&type=track&market=from_token&limit=10";
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static JSONArray tracks(String searchAnswer) throws JSONException {
        return new JSONObject(searchAnswer).getJSONObject("tracks").getJSONArray("items");
    }

    /** The boundary: a track whose {@code is_playable} holds in the user's market, and that isn't the original. */
    private static boolean playsHere(JSONObject track, String originalUri) {
        String uri = track.optString("uri");
        return track.optBoolean("is_playable", false) && uri.startsWith(TRACK) && !uri.equals(originalUri);
    }

    /** Report 5.4's ISRC step: the same ISRC and within about 5 s, preferring the same explicit flag. */
    private static JSONObject sameIsrc(JSONObject original, String originalUri, String isrc, JSONArray tracks) {
        JSONObject otherExplicit = null;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track == null || !playsHere(track, originalUri)) continue;
            if (!closeIn(original, track, ISRC_WITHIN_MILLIS)) continue;
            JSONObject ids = track.optJSONObject("external_ids");
            if (ids == null || !isrc.equalsIgnoreCase(ids.optString("isrc"))) continue;
            if (track.optBoolean("explicit") == original.optBoolean("explicit")) return track;
            if (otherExplicit == null) otherExplicit = track;
        }
        return otherExplicit;
    }

    /**
     * Report 5.4's title step: the same title without its version text, the same first artist by id
     * or name, within about 3 s, and no live, karaoke, instrumental or cover version unless the
     * original is that kind too.
     */
    private static JSONObject sameTitle(JSONObject original, String originalUri, JSONArray tracks) {
        String name = original.optString("name");
        String title = normalized(name);
        Set<String> kinds = kinds(name);
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track == null || !playsHere(track, originalUri)) continue;
            if (!closeIn(original, track, TITLE_WITHIN_MILLIS)) continue;
            String candidate = track.optString("name");
            if (title.equals(normalized(candidate)) && sameFirstArtist(original, track)
                    && kinds.containsAll(kinds(candidate))) {
                return track;
            }
        }
        return null;
    }

    private static boolean closeIn(JSONObject a, JSONObject b, long withinMillis) {
        long first = a.optLong("duration_ms", -1);
        long second = b.optLong("duration_ms", -1);
        return first >= 0 && second >= 0 && Math.abs(first - second) <= withinMillis;
    }

    private static boolean sameFirstArtist(JSONObject a, JSONObject b) {
        JSONObject first = firstArtist(a);
        JSONObject second = firstArtist(b);
        String id = first.optString("id");
        String name = first.optString("name");
        return !id.isEmpty() && id.equals(second.optString("id"))
                || !name.isEmpty() && name.equalsIgnoreCase(second.optString("name"));
    }

    /** The track's first artist, or an empty object. */
    private static JSONObject firstArtist(JSONObject track) {
        JSONArray artists = track.optJSONArray("artists");
        JSONObject first = artists == null ? null : artists.optJSONObject(0);
        return first == null ? new JSONObject() : first;
    }

    /** {@code "Song - Remastered 2011"} gives {@code "Song"}, for the search. */
    private static String withoutVersion(String title) {
        return VERSION.matcher(title).replaceAll("").trim();
    }

    /**
     * The title without its version text, lowercased, with plain apostrophes and single spaces,
     * since releases of a song differ there.
     */
    private static String normalized(String title) {
        return withoutVersion(title).toLowerCase(Locale.ROOT).replace('\u2019', '\'').replaceAll("\\s+", " ");
    }

    /** The kinds of version the title's version text names: live, karaoke, instrumental or cover. */
    private static Set<String> kinds(String title) {
        Set<String> kinds = new HashSet<>();
        Matcher version = VERSION.matcher(title);
        while (version.find()) {
            Matcher kind = KIND.matcher(version.group());
            while (kind.find()) kinds.add(kind.group(1).toLowerCase(Locale.ROOT));
        }
        return kinds;
    }
}
