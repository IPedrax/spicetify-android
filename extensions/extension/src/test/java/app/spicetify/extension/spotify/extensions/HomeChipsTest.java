package app.spicetify.extension.spotify.extensions;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import app.spicetify.extension.spotify.extensions.RandomSongTest.FakeRequest;
import app.spicetify.extension.spotify.extensions.RandomSongTest.FakeRouter;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class HomeChipsTest {
    private static final String TOKEN = "sp://auth/v2/token?renew=0";
    private static final String ALL = "sp://esperanto/spotify.your_library_esperanto.proto.YourLibraryService/All";

    private final Context context = RuntimeEnvironment.getApplication();
    private final FakeRouter router = new FakeRouter();

    @Before
    public void setUp() {
        // What PatchSettings does in Spotify's onCreate with the extensions patch: a new tracker, with no
        // Activity resumed yet. PatchSettings itself stays untouched, since other tests expect it so.
        ActivityTracker.install(context);
        Extensions.setAppContext(context);
        PlayerBridge.attach(router);
        Extensions.setOn(context, Extensions.RANDOM_SONG, false);
        // The status map outlives a test, so no test may pass on the line the last one left.
        Extensions.status(null, Extensions.RANDOM_SONG, "not run");
    }

    @After
    public void tearDown() {
        Extensions.setOn(context, Extensions.RANDOM_SONG, false);
    }

    // ---- The pill (hook A) ----

    @Test
    public void onThePillGoesRightAfterTheFirstChipInANewListAndTheChipsAreLeftAlone() {
        Extensions.setOn(context, Extensions.RANDOM_SONG, true);
        List<String> chips = Arrays.asList("All", "Music", "Podcasts");

        List<?> withPill = HomeChips.chips(chips, "Random");

        assertEquals(Arrays.asList("All", "Random", "Music", "Podcasts"), withPill);
        assertNotSame(chips, withPill);
        assertEquals(Arrays.asList("All", "Music", "Podcasts"), chips);
    }

    @Test
    public void withTwoChipsThePillIsNeitherFirstNorLast() {
        Extensions.setOn(context, Extensions.RANDOM_SONG, true);

        assertEquals(Arrays.asList("All", "Random", "Music"), HomeChips.chips(Arrays.asList("All", "Music"), "Random"));
    }

    @Test
    public void fewerThanTwoChipsGetNoPill() {
        Extensions.setOn(context, Extensions.RANDOM_SONG, true);
        List<String> all = Collections.singletonList("All");
        List<String> none = Collections.emptyList();

        // Home selects the first chip when nothing is selected, so a pill there would become the feed.
        assertSame(all, HomeChips.chips(all, "Random"));
        assertSame(none, HomeChips.chips(none, "Random"));
    }

    @Test
    public void offTheChipsComeBackAsTheyWere() {
        List<String> chips = Arrays.asList("All", "Music", "Podcasts");

        assertSame(chips, HomeChips.chips(chips, "Random"));
    }

    @Test
    public void chipsThatCantBeCopiedComeBackAsTheyWere() {
        Extensions.setOn(context, Extensions.RANDOM_SONG, true);
        List<String> unreadable = new AbstractList<String>() {
            @Override
            public String get(int index) {
                throw new IllegalStateException("a chip can't be read");
            }

            @Override
            public int size() {
                return 3;
            }
        };

        assertSame(unreadable, HomeChips.chips(unreadable, "Random"));
    }

    // ---- A tap (hook B) and the chooser ----

    @Test
    public void onlyThePillsTapIsTakenAndItsChooserOpensOnTheResumedActivity() {
        Activity home = Robolectric.buildActivity(Activity.class).setup().get();

        assertFalse("another chip's tap is Home's", HomeChips.onTap("client-native:default"));
        assertFalse(HomeChips.onTap(null));
        assertTrue(HomeChips.onTap(HomeChips.PILL_ID));
        assertNull("the chooser is posted, so the tap returns at once", ShadowDialog.getLatestDialog());

        shadowOf(Looper.getMainLooper()).idle();

        AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(chooser.isShowing());
        assertSame(home, activityOf(chooser.getContext()));
        assertArrayEquals(new CharSequence[] {"Random song from Spotify", "Random song from my library"},
                shadowOf(chooser).getItems());
        assertEquals("one chooser, for the pill's tap only", 1, ShadowDialog.getShownDialogs().size());
    }

    @Test
    public void theChoosersItemsRunRandomsTwoActions() throws Exception {
        Extensions.setOn(context, Extensions.RANDOM_SONG, true); // its status shows only while it's on
        Robolectric.buildActivity(Activity.class).setup();

        choose(0);
        endWith(TOKEN, "from Spotify");
        choose(1);
        endWith(ALL, "from my library");
    }

    @Test
    public void aTapWithNoResumedActivityAsksToOpenHomeAgain() {
        assertTrue("still the pill's tap", HomeChips.onTap(HomeChips.PILL_ID));
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Open Home again and retry", ShadowToast.getTextOfLatestToast());
        assertNull(ShadowDialog.getLatestDialog());
    }

    @Test
    public void aChooserThatCantBeShownThrowsNothingIntoSpotify() {
        ActivityController<GoneActivity> home = Robolectric.buildActivity(GoneActivity.class).setup();
        home.get().gone = true;
        assertSame(home.get(), ActivityTracker.resumed());

        assertTrue(HomeChips.onTap(HomeChips.PILL_ID));
        shadowOf(Looper.getMainLooper()).idle(); // rethrows anything the posted chooser throws

        assertNull(ShadowDialog.getLatestDialog());
        assertNull("no Toast either, since there was an Activity", ShadowToast.getTextOfLatestToast());
        assertTrue(ShadowLog.getLogsForTag("Spicetify").stream().anyMatch(log ->
                log.msg.equals("Couldn't show the Random chooser") && log.throwable.getMessage().equals("the window is gone")));
    }

    // ---- The Activity tracker ----

    @Test
    public void theTrackerKeepsTheResumedActivityUntilItPauses() {
        assertNull("nothing before an Activity resumes", ActivityTracker.resumed());
        ActivityController<Activity> home = Robolectric.buildActivity(Activity.class).setup();
        assertSame(home.get(), ActivityTracker.resumed());

        home.pause();
        assertNull(ActivityTracker.resumed());

        home.resume();
        assertSame(home.get(), ActivityTracker.resumed());
    }

    @Test
    public void anotherActivitysPauseLeavesTheResumedOne() {
        // In split screen, two Activities can be resumed at once and pause in either order.
        ActivityController<Activity> first = Robolectric.buildActivity(Activity.class).setup();
        ActivityController<Activity> second = Robolectric.buildActivity(Activity.class).setup();
        assertSame(second.get(), ActivityTracker.resumed());

        first.pause();

        assertSame(second.get(), ActivityTracker.resumed());
    }

    // ---- Helpers ----

    /** Taps the pill and picks the chooser's item {@code item}. */
    private static void choose(int item) {
        assertTrue(HomeChips.onTap(HomeChips.PILL_ID));
        shadowOf(Looper.getMainLooper()).idle();
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(item);
    }

    /** The run's first request is {@code uri}; failing it ends the run with a status that says so. */
    private void endWith(String uri, String reason) throws InterruptedException {
        FakeRequest request = router.next();
        assertEquals(uri, request.uri);
        request.callback.onError(new IllegalStateException(reason));
        RandomSongTest.awaitStatus("Couldn't find a random song: java.lang.IllegalStateException: " + reason);
    }

    /** The Activity {@code context} wraps, or null. */
    private static Activity activityOf(Context context) {
        while (!(context instanceof Activity) && context instanceof ContextWrapper) {
            context = ((ContextWrapper) context).getBaseContext();
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    /** An Activity whose window goes away while it's still the resumed one, so no dialog can be shown on it. */
    public static class GoneActivity extends Activity {
        boolean gone;

        @Override
        public Object getSystemService(String name) {
            if (gone && WINDOW_SERVICE.equals(name)) throw new IllegalStateException("the window is gone");
            return super.getSystemService(name);
        }
    }
}
