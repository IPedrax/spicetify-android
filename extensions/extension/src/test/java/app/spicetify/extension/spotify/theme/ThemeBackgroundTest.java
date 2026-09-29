package app.spicetify.extension.spotify.theme;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Android's own decoder, which refuses what isn't an image
public class ThemeBackgroundTest {
    private final Application context = RuntimeEnvironment.getApplication();
    private final File file = new File(context.getFilesDir(), "spicetify_background");

    @Test
    public void doesNothingWhenNoBackgroundFileIsSaved() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Drawable before = activity.getWindow().getDecorView().getBackground();

        ThemeBackground.applyTo(activity);

        assertSame(before, activity.getWindow().getDecorView().getBackground());
    }

    @Test
    public void saveKeepsTheImageAndClearRemovesIt() throws IOException {
        byte[] png = png();
        ThemeBackground.save(context, png);
        assertTrue(ThemeBackground.hasImage(context));
        assertArrayEquals(png, Files.readAllBytes(file.toPath()));
        assertFalse(new File(file.getPath() + ".tmp").exists());

        ThemeBackground.clear(context);
        assertFalse(ThemeBackground.hasImage(context));
    }

    @Test
    public void saveRefusesBytesThatArentAnImageAndKeepsTheSavedOne() throws IOException {
        byte[] png = png();
        ThemeBackground.save(context, png);
        try {
            ThemeBackground.save(context, "<html>Not Found</html>".getBytes(StandardCharsets.UTF_8));
            fail("Bytes that aren't an image were saved");
        } catch (IOException expected) {
            assertEquals("Not an image Android can read", expected.getMessage());
        }
        assertArrayEquals(png, Files.readAllBytes(file.toPath()));
    }

    static byte[] png() {
        return png(8, 4);
    }

    static byte[] png(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }
}
