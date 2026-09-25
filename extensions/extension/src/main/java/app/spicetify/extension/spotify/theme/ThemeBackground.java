package app.spicetify.extension.spotify.theme;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.View;
import java.io.File;

/**
 * Spike: draws a user-supplied image behind Spotify's window instead of the stock background.
 * Not part of the theme patch; the image is expected to be dropped into app-private storage by
 * hand for a device test.
 */
final class ThemeBackground {
    private static final String FILE = "spicetify_background";
    private static Bitmap cached;

    private ThemeBackground() {}

    /**
     * Puts the saved image behind Spotify's main activity; does nothing if none is saved or the
     * activity isn't the one with {@code main_content}.
     */
    static void applyTo(Activity activity) {
        File file = new File(activity.getFilesDir(), FILE);
        if (!file.exists()) return;
        int mainContentId = activity.getResources().getIdentifier("main_content", "id", activity.getPackageName());
        View mainContent = mainContentId == 0 ? null : activity.findViewById(mainContentId);
        if (mainContent == null) return;
        if (cached == null) {
            cached = decode(file, activity);
            if (cached == null) return;
        }
        activity.getWindow().setBackgroundDrawable(new CenterCrop(cached));
        // SpotifyMainActivity.onCreate paints android:id/content opaque black, above the window.
        View content = activity.findViewById(android.R.id.content);
        if (content != null) content.setBackground(null);
        mainContent.setBackground(new CenterCrop(cached));
    }

    /** Reads the image once per process, downsampled so it isn't much larger than the display. */
    private static Bitmap decode(File file, Activity activity) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);

        DisplayMetrics display = activity.getResources().getDisplayMetrics();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, display.widthPixels, display.heightPixels);
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    /** Largest power-of-two sample size that keeps both dimensions at or above the display size. */
    private static int sampleSize(int width, int height, int reqWidth, int reqHeight) {
        int sampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / sampleSize) >= reqHeight && (halfWidth / sampleSize) >= reqWidth) {
                sampleSize *= 2;
            }
        }
        return sampleSize;
    }

    /** Draws the bitmap scaled to fill the bounds, center-cropping instead of stretching it. */
    private static final class CenterCrop extends Drawable {
        private final Bitmap bitmap;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Matrix matrix = new Matrix();

        CenterCrop(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            float scale = Math.max((float) bounds.width() / bitmap.getWidth(), (float) bounds.height() / bitmap.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate((bounds.width() - bitmap.getWidth() * scale) / 2f,
                    (bounds.height() - bitmap.getHeight() * scale) / 2f);
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.save();
            canvas.clipRect(getBounds());
            canvas.drawBitmap(bitmap, matrix, paint);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
