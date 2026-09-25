package app.spicetify.extension.spotify.theme;

import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The official Spicetify themes, read from GitHub when the user browses them. Nothing is bundled. */
final class ThemeGallery {
    static final String LISTING_URL = "https://api.github.com/repos/spicetify/spicetify-themes/contents";

    interface Fetcher {
        String get(String url) throws IOException;
    }

    static final Fetcher HTTP = url -> {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "spicetify-android-patches");
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) throw new FileNotFoundException(url);
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("GitHub answered " + status);
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) > 0; ) output.write(buffer, 0, read);
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    };

    private ThemeGallery() {}

    /** Theme folders of the repository, skipping {@code .github} and {@code _Extra}-style folders. */
    static List<String> parseListing(String json) throws JSONException {
        List<String> themes = new ArrayList<>();
        JSONArray entries = new JSONArray(json);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String name = entry.getString("name");
            if ("dir".equals(entry.getString("type")) && !name.startsWith(".") && !name.startsWith("_")) themes.add(name);
        }
        Collections.sort(themes, String.CASE_INSENSITIVE_ORDER);
        return themes;
    }

    static String colorIniUrl(String theme) {
        return "https://raw.githubusercontent.com/spicetify/spicetify-themes/master/" + Uri.encode(theme) + "/color.ini";
    }
}
