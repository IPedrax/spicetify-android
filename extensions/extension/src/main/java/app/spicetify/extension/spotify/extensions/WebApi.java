package app.spicetify.extension.spotify.extensions;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Spotify's Web API, for the one job the bridge can't do: a search across the whole catalog. Its
 * token comes from the core, over the bridge, the way the app's own Web API calls get theirs
 * (report section 3.4). A request blocks, so it runs on the Web API thread, never the bridge thread.
 */
final class WebApi {
    static final String TOKEN_URI = "sp://auth/v2/token?renew=0";
    private static final int TIMEOUT_MILLIS = 15_000;
    /** One daemon thread, parked while idle, for the requests. */
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Spicetify Web API");
        thread.setDaemon(true);
        return thread;
    });

    /** A GET with a bearer token: the body of a 200 answer, or an IOException. It blocks. */
    interface Http {
        String get(String url, String token) throws IOException;
    }

    /** The real GET, which {@link #get} runs on the Web API thread. */
    static final Http HTTP = WebApi::blockingGet;

    /** The first track of a search, or null when the page was empty, and how many tracks matched. */
    static final class SearchResult {
        String trackUri;
        int total;
    }

    private WebApi() {}

    /** Asks the core for a token. {@code result} gets its UTF-8 bytes, or why not, on the bridge thread. */
    static void token(PlayerBridge.Result result) {
        PlayerBridge.get(TOKEN_URI, new PlayerBridge.Result() {
            @Override
            public void done(byte[] body) {
                String token;
                try {
                    token = parseToken(new String(body, StandardCharsets.UTF_8));
                } catch (Throwable e) {
                    result.failed(e.getMessage() != null ? e.getMessage() : e.toString());
                    return;
                }
                result.done(token.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public void failed(String reason) {
                result.failed(reason);
            }
        });
    }

    /**
     * A {@code TokenResponse}'s {@code accessToken}. An {@code errorCode} above 0 fails, as it does in
     * the app. No failure quotes the answer, since the answer holds the token.
     */
    static String parseToken(String json) throws IOException {
        JSONObject response;
        try {
            response = new JSONObject(json);
        } catch (JSONException e) {
            // Not e's message: org.json quotes its whole input there.
            throw new IOException("sp://auth/v2/token gave an answer that isn't a JSON object");
        }
        int error = response.optInt("errorCode");
        if (error > 0) {
            throw new IOException("sp://auth/v2/token responded with an error: " + error + ", "
                    + response.optString("errorDescription"));
        }
        String token = response.optString("accessToken");
        if (token.isEmpty()) throw new IOException("sp://auth/v2/token gave no access token");
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            // A header can't carry it, and the exception that would say so quotes the header.
            if (c < 0x20 || c > 0x7e) {
                throw new IOException("sp://auth/v2/token gave a token with a character outside printable ASCII");
            }
        }
        return token;
    }

    /** One track matching {@code query}, at {@code offset}, in the market of the token's user. */
    static String searchUrl(String query, int offset) {
        try {
            return "https://api.spotify.com/v1/search?q=" + URLEncoder.encode(query, "UTF-8")
                    + "&type=track&limit=1&offset=" + offset + "&market=from_token";
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static SearchResult parseSearch(String json) throws JSONException {
        JSONObject tracks = new JSONObject(json).getJSONObject("tracks");
        SearchResult result = new SearchResult();
        result.total = tracks.optInt("total");
        JSONArray items = tracks.optJSONArray("items");
        JSONObject first = items == null ? null : items.optJSONObject(0);
        String uri = first == null ? "" : first.optString("uri");
        if (!uri.isEmpty()) result.trackUri = uri;
        return result;
    }

    /**
     * GETs {@code url} through {@code http} on the Web API thread. {@code result} gets the body's
     * UTF-8 bytes, or why not, back on the bridge thread.
     */
    static void get(Http http, String url, String token, PlayerBridge.Result result) {
        NETWORK.execute(() -> {
            try {
                PlayerBridge.post(answer(http, url, token, result));
            } catch (Throwable e) {
                Log.w("Spicetify", "Couldn't hand a Web API answer to the bridge", e);
            }
        });
    }

    /** On the Web API thread: runs the GET, and returns what the bridge thread does with its outcome. */
    private static Runnable answer(Http http, String url, String token, PlayerBridge.Result result) {
        try {
            byte[] body = http.get(url, token).getBytes(StandardCharsets.UTF_8);
            return () -> result.done(body);
        } catch (Throwable e) {
            Log.w("Spicetify", "A Web API request failed", e);
            String reason = e.getMessage() != null ? e.getMessage() : e.toString();
            return () -> result.failed(reason);
        }
    }

    private static String blockingGet(String url, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("the Web API answered HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) != -1; ) body.write(buffer, 0, read);
                return new String(body.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
