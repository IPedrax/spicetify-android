package app.spicetify.extension.spotify.extensions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ExtensionsTest {
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
}
