package app.spicetify.extension.spotify.theme;

import static org.junit.Assert.assertSame;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class ThemeBackgroundTest {
    @Test
    public void doesNothingWhenNoBackgroundFileIsSaved() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Drawable before = activity.getWindow().getDecorView().getBackground();

        ThemeBackground.applyTo(activity);

        assertSame(before, activity.getWindow().getDecorView().getBackground());
    }
}
