package app.spicetify.extension.spotify.theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses Spicetify themes: {@code color.ini} as the Spicetify CLI reads it, or CSS {@code --spice-*} variables. */
public final class SpicetifyTheme {
    public static final class Scheme {
        public final String name;
        public final Map<String, Integer> colors;

        Scheme(String name, Map<String, Integer> colors) {
            this.name = name;
            this.colors = Collections.unmodifiableMap(colors);
        }
    }

    private static final Pattern INLINE_COMMENT = Pattern.compile("\\s[;#]");
    private static final Pattern SPICE = Pattern.compile("--spice-([A-Za-z0-9-]+)\\s*:\\s*([^;}]+)");
    private static final Pattern RGB = Pattern.compile(
            "rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*(?:,\\s*(\\d*\\.?\\d+)\\s*)?\\)");

    private SpicetifyTheme() {}

    public static List<Scheme> parse(String text) {
        if (text.contains("--spice-")) return Collections.singletonList(parseSpiceCss(text));
        return parseColorIni(text);
    }

    /** Case-insensitive names, {@code =} or {@code :}, {@code ;} and {@code #} comments; keys before a section are skipped. */
    static List<Scheme> parseColorIni(String text) {
        Map<String, Map<String, Integer>> schemes = new LinkedHashMap<>();
        Map<String, Integer> current = null;
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int number = i + 1;
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                if (!line.endsWith("]")) throw new ThemeException("Line " + number + ": unclosed section header");
                String name = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                if (name.isEmpty()) throw new ThemeException("Line " + number + ": empty section name");
                current = schemes.get(name);
                if (current == null) {
                    current = new LinkedHashMap<>();
                    schemes.put(name, current);
                }
                continue;
            }
            int delimiter = -1;
            for (int c = 0; c < line.length(); c++) {
                if (line.charAt(c) == '=' || line.charAt(c) == ':') {
                    delimiter = c;
                    break;
                }
            }
            if (delimiter < 1) throw new ThemeException("Line " + number + ": expected \"key = value\"");
            if (current == null) continue;
            String key = line.substring(0, delimiter).trim().toLowerCase(Locale.ROOT);
            // Trimmed before the comment search, as go-ini does, so a value like "#cba6f7" is kept.
            String value = line.substring(delimiter + 1).trim();
            Matcher comment = INLINE_COMMENT.matcher(value);
            if (comment.find()) value = value.substring(0, comment.start()).trim();
            if (!value.isEmpty()) current.put(key, iniColor(key, value, number));
        }
        if (schemes.isEmpty()) throw new ThemeException("No color schemes found");
        List<Scheme> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> scheme : schemes.entrySet()) {
            result.add(new Scheme(scheme.getKey(), scheme.getValue()));
        }
        return result;
    }

    private static int iniColor(String key, String value, int line) {
        if (value.startsWith("${")) {
            throw new ThemeException("Line " + line + ": " + key + " uses \"" + value + "\", and ${...} values don't work on Android");
        }
        boolean hashed = value.startsWith("#");
        String digits = hashed ? value.substring(1) : value;
        Integer color = ArgbColors.parseHex(digits, false);
        if (color == null || (!hashed && digits.length() == 8)) {
            throw new ThemeException("Line " + line + ": " + key + " has unsupported color \"" + value + "\"");
        }
        return color;
    }

    /** Every {@code --spice-<key>: <color>} declaration becomes one scheme; everything else is ignored. */
    static Scheme parseSpiceCss(String text) {
        Map<String, Integer> colors = new LinkedHashMap<>();
        Matcher match = SPICE.matcher(text);
        while (match.find()) {
            String key = match.group(1).toLowerCase(Locale.ROOT);
            if (key.startsWith("rgb-")) continue;
            colors.put(key, cssColor(key, match.group(2).trim()));
        }
        if (colors.isEmpty()) throw new ThemeException("No --spice-* colors found");
        return new Scheme("css", colors);
    }

    private static int cssColor(String key, String value) {
        if (value.startsWith("#")) {
            Integer color = ArgbColors.parseHex(value.substring(1), true);
            if (color != null) return color;
        }
        Matcher rgb = RGB.matcher(value);
        if (rgb.matches()) {
            int red = Integer.parseInt(rgb.group(1));
            int green = Integer.parseInt(rgb.group(2));
            int blue = Integer.parseInt(rgb.group(3));
            double alpha = rgb.group(4) == null ? 1.0 : Double.parseDouble(rgb.group(4));
            if (red <= 255 && green <= 255 && blue <= 255 && alpha >= 0 && alpha <= 1) {
                return ((int) Math.round(alpha * 255) << 24) | (red << 16) | (green << 8) | blue;
            }
        }
        throw new ThemeException("--spice-" + key + " has unsupported color \"" + value + "\"");
    }
}
