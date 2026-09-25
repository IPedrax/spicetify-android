package app.spicetify.extension.spotify.theme;

import java.util.Map;

/**
 * Spike: Spotify's Compose screens build their colors from ARGB constants through Compose's
 * Color(Long). The theme patch routes those calls through {@link #map}, which swaps Spotify's stock
 * colors for the theme's role colors by RGB value, keeping each constant's alpha. Black is left
 * alone because shadows and scrims use it.
 */
public final class ThemeCompose {
    private static final int[] STOCK = {
            0x121212, 0x1F1F1F, 0x2A2A2A, 0x1A1A1A, 0x282828, 0xFFFFFF, 0xB3B3B3, 0x7C7C7C,
            0x1ED760, 0x3BE477, 0x1ABC54, 0x333333, 0x535353, 0x0D72EA, 0x539DF5, 0xE91429, 0xF3727F,
    };
    private static final String[] ROLES = {
            "main", "main-elevated", "highlight-elevated", "highlight", "card", "text", "subtext", "subtext",
            "button", "button", "button-active", "tab-active", "button-disabled",
            "notification", "notification", "notification-error", "notification-error",
    };

    private static final class Table {
        final int[] from;
        final int[] to;

        Table(int[] from, int[] to) {
            this.from = from;
            this.to = to;
        }
    }

    private static volatile Table table = new Table(new int[0], new int[0]);

    private ThemeCompose() {}

    /** Injection point: Compose's Color(Long) passes every ARGB constant through here first. */
    public static long map(long argb) {
        Table current = table;
        int rgb = (int) argb & 0xFFFFFF;
        for (int i = 0; i < current.from.length; i++) {
            if (current.from[i] == rgb) {
                int color = current.to[i];
                long alpha = ((argb >>> 24) & 0xFF) * ((color >>> 24) & 0xFF) / 255;
                return (alpha << 24) | (color & 0xFFFFFFL);
            }
        }
        return argb;
    }

    /** Builds the table from the theme's role colors; roles the theme leaves out keep Spotify's colors. */
    static void load(Map<String, Integer> roles) {
        int[] from = new int[STOCK.length];
        int[] to = new int[STOCK.length];
        int size = 0;
        for (int i = 0; i < STOCK.length; i++) {
            Integer color = roles.get(ROLES[i]);
            if (color == null) continue;
            from[size] = STOCK[i];
            to[size] = color;
            size++;
        }
        table = new Table(java.util.Arrays.copyOf(from, size), java.util.Arrays.copyOf(to, size));
    }
}
