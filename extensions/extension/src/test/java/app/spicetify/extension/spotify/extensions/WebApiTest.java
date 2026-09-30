package app.spicetify.extension.spotify.extensions;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowToast;

/** Robolectric for its real {@code org.json}; the plain android.jar only has stubs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class WebApiTest {
    @Test
    public void searchUrlAsksForOneTrackAtTheOffsetInTheTokensMarket() {
        assertEquals("https://api.spotify.com/v1/search?q=a&type=track&limit=1&offset=5&market=from_token",
                WebApi.searchUrl("a", 5));
        assertEquals("the query is URL-encoded",
                "https://api.spotify.com/v1/search?q=a+b%26c&type=track&limit=1&offset=0&market=from_token",
                WebApi.searchUrl("a b&c", 0));
    }

    @Test
    public void parseSearchReadsTheFirstTrackAndTheTotal() throws Exception {
        WebApi.SearchResult found =
                WebApi.parseSearch("{\"tracks\":{\"items\":[{\"uri\":\"spotify:track:x\"}],\"total\":42}}");

        assertEquals("spotify:track:x", found.trackUri);
        assertEquals(42, found.total);
    }

    @Test
    public void parseSearchOfAnEmptyPageGivesNoUriButStillTheTotal() throws Exception {
        WebApi.SearchResult found = WebApi.parseSearch("{\"tracks\":{\"items\":[],\"total\":42}}");

        assertNull(found.trackUri);
        assertEquals(42, found.total);
    }

    @Test
    public void parseTokenReadsTheAccessTokenAndAnErrorCodeAbove0Fails() throws Exception {
        assertEquals("abc", WebApi.parseToken(
                "{\"accessToken\":\"abc\",\"expiresIn\":3600,\"tokenType\":\"Bearer\",\"errorCode\":0}"));
        try {
            WebApi.parseToken("{\"errorCode\":3,\"errorDescription\":\"logged out\"}");
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals("sp://auth/v2/token responded with an error: 3, logged out", expected.getMessage());
        }
    }

    @Test
    public void aTruncatedTokenAnswerKeepsTheTokenOutOfTheMessageTheStatusAndTheToast() throws Exception {
        String truncated = "{\"accessToken\":\"SECRET-TOKEN\",\"expiresIn\":3600"; // no closing brace
        String why = "sp://auth/v2/token gave an answer that isn't a JSON object";
        try {
            WebApi.parseToken(truncated);
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals(why, expected.getMessage());
        }

        // The same answer through a run, whose failure goes to the status line, the log and a Toast.
        Context context = RuntimeEnvironment.getApplication();
        Extensions.setAppContext(context);
        Extensions.setOn(context, Extensions.RANDOM_SONG, true);
        Extensions.status(null, Extensions.RANDOM_SONG, "not run");
        RandomSongTest.FakeRouter router = new RandomSongTest.FakeRouter();
        PlayerBridge.attach(router);
        try {
            RandomSong.playFromSpotify(context, (url, token) -> {
                throw new AssertionError("no search without a token");
            });
            router.next().callback.onResponse(200, truncated.getBytes(UTF_8));

            RandomSongTest.awaitToast("Couldn't find a random song: " + why);
            RandomSongTest.awaitStatus("Couldn't find a random song: " + why);
            assertFalse(ShadowToast.getTextOfLatestToast().contains("SECRET-TOKEN"));
            for (String line : Extensions.statusLines()) assertFalse(line, line.contains("SECRET-TOKEN"));
            for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
                String logged = item.msg + (item.throwable == null ? "" : " " + item.throwable);
                assertFalse(logged, logged.contains("SECRET-TOKEN"));
            }
        } finally {
            Extensions.setOn(context, Extensions.RANDOM_SONG, false);
        }
    }

    @Test
    public void aTokenAHeaderCantCarryIsRefusedWithoutShowingIt() throws Exception {
        // A line break, a character past ASCII and DEL: none is printable ASCII.
        for (String escaped : new String[] {"\\n", "\\u00e9", "\\u007f"}) {
            try {
                WebApi.parseToken("{\"accessToken\":\"SECRET" + escaped + "TOKEN\"}");
                fail("expected IOException for " + escaped);
            } catch (IOException expected) {
                assertEquals("sp://auth/v2/token gave a token with a character outside printable ASCII",
                        expected.getMessage());
            }
        }
    }

    @Test
    public void theRealGetSendsTheBearerTokenAndA429FailsWithoutIt() throws Exception {
        // com.sun.net.httpserver isn't on this source set's classpath, which is android.jar's, so a
        // plain socket on 127.0.0.1 serves the one request.
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            FutureTask<String> serving = new FutureTask<>(() -> {
                try (Socket client = server.accept()) {
                    BufferedReader request = new BufferedReader(new InputStreamReader(client.getInputStream(), UTF_8));
                    String authorization = null;
                    for (String line; (line = request.readLine()) != null && !line.isEmpty(); ) {
                        if (line.regionMatches(true, 0, "Authorization:", 0, 14)) {
                            authorization = line.substring(14).trim();
                        }
                    }
                    client.getOutputStream().write(("HTTP/1.1 429 Too Many Requests\r\n"
                            + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(UTF_8));
                    client.getOutputStream().flush();
                    return authorization;
                }
            });
            new Thread(serving, "Web API test server").start();

            try {
                WebApi.HTTP.get("http://127.0.0.1:" + server.getLocalPort() + "/v1/search?q=a", "tok");
                fail("expected IOException");
            } catch (IOException expected) {
                assertEquals("the Web API answered HTTP 429", expected.getMessage());
                assertFalse(expected.getMessage().contains("tok"));
            }
            assertEquals("Bearer tok", serving.get(5, TimeUnit.SECONDS));
        }
    }
}
