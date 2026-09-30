package app.spicetify.extension.spotify.extensions;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/**
 * Play a random song's pill in Home's filter row (entry-points report, section 1). The patch's
 * {@code HomeChipBridge} builds Spotify's chip for the pill and asks {@link #chips} where it goes
 * (hook A), and offers every chip tap to {@link #onTap} before Home's loop hears of it (hook B). So a
 * tap on the pill opens a small chooser, and Home never selects the pill or loads a feed for it.
 */
public final class HomeChips {
    /** The pill's id, which Home uses as its key and its facet, and which a tap on it hands back. */
    public static final String PILL_ID = "spicetify-random";
    public static final String PILL_TITLE = "Random";
    /** The chooser's items, and the ids RandomSong registered their actions under. */
    private static final String[] LABELS = {"Random song from Spotify", "Random song from my library"};
    private static final String[] ACTIONS = {"random_spotify", "random_library"};

    private HomeChips() {}

    /**
     * Hook A, on the thread that maps Home's feeds: while Play a random song is on, a copy of
     * {@code chips} with {@code pill} right after the first chip, All. Home selects the first chip when
     * nothing is selected and can highlight the last, so the pill needs 2 chips to stay off both
     * places. Otherwise, or on any Throwable, {@code chips} itself comes back.
     */
    public static List<?> chips(List<?> chips, Object pill) {
        try {
            Context context = Extensions.appContext();
            if (context == null || chips.size() < 2 || !Extensions.isOn(context, Extensions.RANDOM_SONG)) return chips;
            List<Object> withPill = new ArrayList<>(chips);
            withPill.add(1, pill);
            return withPill;
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't add the Random pill to Home", e);
            return chips;
        }
    }

    /**
     * Hook B, on the main thread: true for the pill once its chooser is posted, so Home drops the
     * tap. A pill left on Home after Random is turned off still answers, since a tap Home took would
     * select it. False for every other chip, so Home handles the tap, and on any Throwable.
     */
    public static boolean onTap(String id) {
        try {
            if (!PILL_ID.equals(id)) return false;
            new Handler(Looper.getMainLooper()).post(HomeChips::choose);
            return true;
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't answer the Random pill", e);
            return false;
        }
    }

    /** On the main thread: the chooser on the resumed Activity, or a Toast when there's none. */
    private static void choose() {
        try {
            Activity activity = ActivityTracker.resumed();
            if (activity == null) {
                Toast.makeText(Extensions.appContext(), "Open Home again and retry", Toast.LENGTH_LONG).show();
                return;
            }
            new AlertDialog.Builder(activity)
                    .setItems(LABELS, (dialog, which) -> run(activity, ACTIONS[which]))
                    .show();
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't show the Random chooser", e);
        }
    }

    /** A chooser item: Random's action {@code id}, which only posts its run to the bridge thread. */
    private static void run(Context context, String id) {
        try {
            Extensions.Action action = Extensions.action(id);
            if (action != null) action.run(context);
        } catch (Throwable e) {
            Log.w("Spicetify", "Random chooser item " + id + " failed", e);
        }
    }
}
