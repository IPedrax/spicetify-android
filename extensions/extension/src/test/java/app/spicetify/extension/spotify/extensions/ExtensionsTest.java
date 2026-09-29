package app.spicetify.extension.spotify.extensions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
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
}
