package app.spicetify.extension.spotify.extensions;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import app.spicetify.extension.spotify.settings.PatchSettings;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class ExtensionsTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Test
    public void idsAreStable() {
        assertEquals("trash_bin", Extensions.TRASH_BIN);
        assertEquals("random_song", Extensions.RANDOM_SONG);
        assertEquals("shuffle_plus", Extensions.SHUFFLE_PLUS);
        assertEquals("hide_podcasts", Extensions.HIDE_PODCASTS);
    }

    @Test
    public void portForMatchesTheDesktopTableCaseInsensitively() {
        assertEquals(Extensions.TRASH_BIN, Extensions.portFor("spicetify", "cli", "Extensions/trashbin.js"));
        assertEquals(Extensions.SHUFFLE_PLUS, Extensions.portFor("spicetify", "cli", "Extensions/shuffle+.js"));
        assertEquals(Extensions.HIDE_PODCASTS,
                Extensions.portFor("TheRealPadster", "Spicetify-Hide-Podcasts", "./hidePodcasts.js"));
        assertNull(Extensions.portFor("a", "b", "x.js"));
    }

    @Test
    public void eachSwitchStartsOffAndIsSavedUnderItsId() {
        assertFalse(Extensions.isOn(context, Extensions.TRASH_BIN));

        Extensions.setOn(context, Extensions.TRASH_BIN, true);
        assertTrue(Extensions.isOn(context, Extensions.TRASH_BIN));
        assertFalse(Extensions.isOn(context, Extensions.SHUFFLE_PLUS));
        assertTrue(context.getSharedPreferences("spicetify_extensions", Context.MODE_PRIVATE)
                .getBoolean("trash_bin", false));

        Extensions.setOn(context, Extensions.TRASH_BIN, false);
        assertFalse(Extensions.isOn(context, Extensions.TRASH_BIN));
    }

    @Test
    public void namesAndDescribesEachExtension() {
        assertEquals("Trash Bin", Extensions.title(Extensions.TRASH_BIN));
        assertEquals("Throw songs and artists in the trash from their menus, and Spotify skips them.",
                Extensions.description(Extensions.TRASH_BIN));
        assertEquals("Play a random song", Extensions.title(Extensions.RANDOM_SONG));
        assertEquals("Play a random song from all of Spotify, or from your library.",
                Extensions.description(Extensions.RANDOM_SONG));
        assertEquals("Shuffle+", Extensions.title(Extensions.SHUFFLE_PLUS));
        assertEquals("Shuffle what's playing in a truly random order.", Extensions.description(Extensions.SHUFFLE_PLUS));
        assertEquals("Hide podcasts", Extensions.title(Extensions.HIDE_PODCASTS));
        assertEquals("Remove podcasts and episodes from Home, Search and your Library's filters.",
                Extensions.description(Extensions.HIDE_PODCASTS));
    }

    @Test
    public void controlsAreRegisteredPerId() {
        // Ids no extension uses, so the process-wide registry keeps nothing another test could see.
        Extensions.Controls controls = View::new;
        Extensions.register("test_registered", controls);

        assertSame(controls, Extensions.controls("test_registered"));
        assertNull(Extensions.controls("test_unregistered"));
    }

    @Test
    public void actionsAreRegisteredPerId() {
        Extensions.Action action = actionContext -> {};
        Extensions.registerAction("test_action", action);

        assertSame(action, Extensions.action("test_action"));
        assertNull(Extensions.action("test_no_action"));
    }

    @Test
    public void setOnTellsTheSwitchListenerAfterSaving() {
        List<String> seen = new ArrayList<>();
        Extensions.onSwitch("test_switch", (switchContext, on) ->
                seen.add(on + ", saved " + Extensions.isOn(switchContext, "test_switch")));

        Extensions.setOn(context, "test_switch", true);
        Extensions.setOn(context, "test_switch", false);

        assertEquals(Arrays.asList("true, saved true", "false, saved false"), seen);
    }

    @Test
    public void aFailingSwitchListenerStillSavesTheSwitch() {
        Extensions.onSwitch("test_failing", (switchContext, on) -> {
            throw new IllegalStateException("listener failed");
        });

        Extensions.setOn(context, "test_failing", true);

        assertTrue(Extensions.isOn(context, "test_failing"));
    }

    @Test
    public void startEnabledStartsOnlyTheExtensionsThatAreOn() {
        List<String> started = new ArrayList<>();
        Extensions.onSwitch("test_on", (switchContext, on) -> started.add("test_on " + on));
        Extensions.onSwitch("test_off", (switchContext, on) -> started.add("test_off " + on));
        context.getSharedPreferences("spicetify_extensions", Context.MODE_PRIVATE).edit()
                .putBoolean("test_on", true).commit();

        Extensions.startEnabled(context);

        assertEquals(Collections.singletonList("test_on true"), started);
    }

    @Test
    public void statusShowsTheLastLineOfEachExtensionThatIsOnAndLogsEveryLine() throws Exception {
        Extensions.setAppContext(context);
        File log = new File(context.getFilesDir(), "spicetify_extensions.log");
        log.delete();
        // Hide podcasts has no switch listener, so turning it on here starts nothing.
        Extensions.setOn(context, Extensions.HIDE_PODCASTS, true);

        Extensions.status(context, Extensions.HIDE_PODCASTS, "Hid 1 item");
        Extensions.status(context, Extensions.HIDE_PODCASTS, "Hid 3 items");
        Extensions.status(context, "test_status", "not an extension, so only logged");

        // The lines are kept before the file is written, which happens on the log's own thread.
        List<String> lines = Extensions.statusLines();
        assertTrue(lines.get(0), lines.get(0).startsWith("Player bridge: "));
        assertEquals(Collections.singletonList("Hide podcasts: Hid 3 items"), lines.subList(1, lines.size()));
        String time = "\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ";
        String written = awaitLog(context, log);
        assertTrue(written, written.matches(time + " hide_podcasts: Hid 1 item\n"
                + time + " hide_podcasts: Hid 3 items\n"
                + time + " test_status: not an extension, so only logged\n"));
    }

    @Test
    public void enabledListsTheExtensionsThatAreOnInTheSettingsOrder() {
        context.getSharedPreferences("spicetify_extensions", Context.MODE_PRIVATE).edit()
                .putBoolean(Extensions.HIDE_PODCASTS, true).putBoolean(Extensions.TRASH_BIN, true).commit();

        assertEquals(Arrays.asList(Extensions.TRASH_BIN, Extensions.HIDE_PODCASTS), Extensions.enabled(context));
    }

    @Test
    public void latestStatusSaysOnUntilTheExtensionReportsALine() {
        // Ids no extension uses, so no other test can have reported for them.
        assertEquals("on", Extensions.latestStatus("test_quiet"));

        report("test_reporting", "Hid 1 item");
        report("test_reporting", "Hid 3 items");

        assertEquals("Hid 3 items", Extensions.latestStatus("test_reporting"));
    }

    @Test
    public void statusWritesTheFileOnTheLogsOwnThread() throws Exception {
        File log = new File(context.getFilesDir(), "spicetify_extensions.log");
        AtomicReference<String> writer = new AtomicReference<>();
        Context recording = new ContextWrapper(context) {
            @Override
            public File getFilesDir() {
                writer.set(Thread.currentThread().getName());
                return super.getFilesDir();
            }
        };

        Extensions.status(recording, "test_thread", "written off the caller's thread");

        assertTrue(awaitLog(context, log).endsWith(" test_thread: written off the caller's thread\n"));
        assertEquals("Spicetify extensions log", writer.get());
    }

    @Test
    public void theLogKeepsItsLast128KbOnceItPasses256Kb() throws Exception {
        File log = new File(context.getFilesDir(), "spicetify_extensions.log");
        log.delete();
        String filler = new String(new char[200]).replace('\0', 'x');

        // An entry is a 20 character time, " test_log: ", the line and a newline. The loop stops
        // after the line that takes the log past 256 KB, which is the append that cuts it.
        long total = 0;
        int written = 0;
        while (total <= 256 * 1024) {
            String line = written++ + " " + filler;
            Extensions.status(context, "test_log", line);
            total += 20 + " test_log: ".length() + line.length() + 1;
        }

        String kept = awaitLog(context, log);
        assertTrue(kept.length() + " bytes kept", kept.length() <= 128 * 1024);
        assertTrue("starts on a whole line",
                kept.matches("(?s)\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ test_log: \\d+ x{200}\n.*"));
        assertTrue(kept.endsWith(" test_log: " + (written - 1) + " " + filler + "\n"));
    }

    @Test
    public void appContextFallsBackToTheContextPatchSettingsGot() {
        Extensions.setAppContext(null);

        PatchSettings.initialize(context);

        assertSame(context, Extensions.appContext());
    }

    /** Keeps {@code line} as extension {@code id}'s latest status, unlogged; for tests in other packages too. */
    public static void report(String id, String line) {
        Extensions.status(null, id, line);
    }

    /**
     * The log as it stood before a marker line this writes. The log's thread appends in order, so
     * once the marker is in the file, everything logged before it is too.
     */
    static String awaitLog(Context context, File log) throws Exception {
        String token = "flush " + System.nanoTime();
        Extensions.status(context, "test_marker", token);
        String marker = " test_marker: " + token + "\n";
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            String written = log.exists() ? new String(Files.readAllBytes(log.toPath()), UTF_8) : "";
            int at = written.indexOf(marker);
            if (at >= 0) return written.substring(0, written.lastIndexOf('\n', at) + 1);
            assertTrue("the log's thread didn't catch up within 5 s", System.currentTimeMillis() < deadline);
            Thread.sleep(10);
        }
    }
}
