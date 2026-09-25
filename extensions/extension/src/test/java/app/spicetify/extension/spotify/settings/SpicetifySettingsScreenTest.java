package app.spicetify.extension.spotify.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class SpicetifySettingsScreenTest {
    private final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

    @Test
    public void opensAsADialogWithoutStartingAnActivity() {
        SpicetifySettingsScreen.open(activity);

        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void showsTheTitleAndOnlyInstalledPatchControls() {
        SpicetifySettingsScreen.open(activity);

        List<String> texts = texts(ShadowDialog.getLatestDialog().getWindow().getDecorView());
        assertTrue(texts.contains("Spicetify"));
        // An unpatched extension reports no installed patches.
        assertTrue(texts.contains("No configurable Spicetify patches are installed."));
        assertFalse(texts.contains("Clean sharing links"));
    }

    @Test
    public void backDismissesTheDialog() {
        SpicetifySettingsScreen.open(activity);
        Dialog dialog = ShadowDialog.getLatestDialog();

        dialog.onBackPressed();

        assertFalse(dialog.isShowing());
    }

    @Test
    public void closeButtonDismissesTheDialog() {
        SpicetifySettingsScreen.open(activity);
        Dialog dialog = ShadowDialog.getLatestDialog();

        dialog.getWindow().getDecorView().findViewWithTag(SpicetifySettingsScreen.CLOSE_TAG).performClick();

        assertFalse(dialog.isShowing());
    }

    @Test
    public void ignoresAFinishingActivity() {
        activity.finish();

        SpicetifySettingsScreen.open(activity);

        assertNull(ShadowDialog.getLatestDialog());
    }

    private static List<String> texts(View view) {
        List<String> texts = new ArrayList<>();
        if (view instanceof TextView) texts.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) texts.addAll(texts(group.getChildAt(i)));
        }
        return texts;
    }
}
