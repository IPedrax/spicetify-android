package app.spicetify.extension.spotify.theme;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
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
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Spike: draws a theme's image behind Spotify's window instead of the stock background. Applying a
 * theme saves its image in app-private storage, or clears it for a theme without one.
 */
final class ThemeBackground {
    private static final String FILE = "spicetify_background";
    private static final String PREFERENCES = "spicetify_theme";
    private static final String BLUR = "background_blur";
    private static final int SCRIM = 0x80000000;
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
        Bitmap full = BitmapFactory.decodeFile(file.getPath(), options);
        if (full == null) return null;
        Bitmap crop = cropToAspect(full, (float) display.widthPixels / display.heightPixels);
        if (crop != full) full.recycle();
        if (!blurEnabled(activity)) return crop;
        // A real blur at the crop's own resolution; an upscaled thumbnail looks blocky instead of soft.
        Bitmap blurred = blur(crop, Math.max(2, crop.getHeight() / 80));
        crop.recycle();
        return blurred;
    }

    /** Whether an image is saved to draw behind Spotify. */
    static boolean hasImage(Context context) {
        return new File(context.getFilesDir(), FILE).exists();
    }

    /** Decodes just the image's size, refusing bytes Android can't read as an image. */
    static BitmapFactory.Options bounds(byte[] image) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Not an image Android can read");
        return bounds;
    }

    /**
     * Saves the image to draw behind Spotify, refusing bytes Android can't read as one. Writes to a
     * temporary file and renames it, so a failure never leaves half an image.
     */
    static void save(Context context, byte[] image) throws IOException {
        bounds(image);
        File file = new File(context.getFilesDir(), FILE);
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(tmp)) {
            output.write(image);
        }
        if (!tmp.renameTo(file)) throw new IOException("Could not replace " + file);
        cached = null;
    }

    /** Deletes the image, so Spotify draws its own background again. */
    static void clear(Context context) {
        new File(context.getFilesDir(), FILE).delete();
        cached = null;
    }

    static boolean blurEnabled(Context context) {
        return preferences(context).getBoolean(BLUR, false);
    }

    /** Saves the choice; the next activity start decodes the image again with it. */
    static void setBlur(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(BLUR, enabled).apply();
        cached = null;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** Three box blurs in a row approximate a Gaussian blur. */
    private static Bitmap blur(Bitmap source, int radius) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] scratch = new int[pixels.length];
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(pixels, scratch, width, height, radius, true);
            boxBlur(scratch, pixels, width, height, radius, false);
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    /** One opaque box blur along every row (horizontal) or column, clamping at the edges. */
    private static void boxBlur(int[] in, int[] out, int width, int height, int radius, boolean horizontal) {
        int lines = horizontal ? height : width;
        int length = horizontal ? width : height;
        int step = horizontal ? 1 : width;
        int window = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int start = horizontal ? line * width : line;
            int red = 0;
            int green = 0;
            int blue = 0;
            for (int i = -radius; i <= radius; i++) {
                int pixel = in[start + clamp(i, length) * step];
                red += (pixel >> 16) & 0xFF;
                green += (pixel >> 8) & 0xFF;
                blue += pixel & 0xFF;
            }
            for (int i = 0; i < length; i++) {
                out[start + i * step] = 0xFF000000 | ((red / window) << 16) | ((green / window) << 8) | (blue / window);
                int add = in[start + clamp(i + radius + 1, length) * step];
                int remove = in[start + clamp(i - radius, length) * step];
                red += ((add >> 16) & 0xFF) - ((remove >> 16) & 0xFF);
                green += ((add >> 8) & 0xFF) - ((remove >> 8) & 0xFF);
                blue += (add & 0xFF) - (remove & 0xFF);
            }
        }
    }

    private static int clamp(int index, int length) {
        return index < 0 ? 0 : index >= length ? length - 1 : index;
    }

    /** The middle of the image with the screen's aspect ratio: the part a center crop shows. */
    private static Bitmap cropToAspect(Bitmap image, float aspect) {
        int width = image.getWidth();
        int height = image.getHeight();
        int cropWidth = width;
        int cropHeight = height;
        if ((float) width / height > aspect) {
            cropWidth = Math.max(1, Math.round(height * aspect));
        } else {
            cropHeight = Math.max(1, Math.round(width / aspect));
        }
        return Bitmap.createBitmap(image, (width - cropWidth) / 2, (height - cropHeight) / 2, cropWidth, cropHeight);
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
            // Dim the image so text stays readable, like Galaxy's darkened backgrounds.
            canvas.drawColor(SCRIM);
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
