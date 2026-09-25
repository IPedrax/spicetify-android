package app.spicetify.extension.spotify.theme;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class ThemeGalleryTest {
    @Test
    public void listsThemeFoldersOnly() throws Exception {
        String json = "[{\"type\":\"dir\",\"name\":\".github\"},{\"type\":\"file\",\"name\":\"README.md\"},"
                + "{\"type\":\"dir\",\"name\":\"Sleek\"},{\"type\":\"dir\",\"name\":\"_Extra\"},"
                + "{\"type\":\"dir\",\"name\":\"Dribbblish\"},{\"type\":\"dir\",\"name\":\"text\"}]";
        assertEquals(Arrays.asList("Dribbblish", "Sleek", "text"), ThemeGallery.parseListing(json));
    }

    @Test
    public void buildsRawColorIniLinks() {
        assertEquals("https://raw.githubusercontent.com/spicetify/spicetify-themes/master/Sleek/color.ini",
                ThemeGallery.colorIniUrl("Sleek"));
        assertEquals("https://raw.githubusercontent.com/spicetify/spicetify-themes/master/Starry%20Night/color.ini",
                ThemeGallery.colorIniUrl("Starry Night"));
    }
}
