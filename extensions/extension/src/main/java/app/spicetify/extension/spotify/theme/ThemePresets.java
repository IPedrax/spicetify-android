package app.spicetify.extension.spotify.theme;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Built-in themes, as role colors. */
public final class ThemePresets {
    public static final String STOCK = "stock";
    public static final String AMOLED = "amoled";
    public static final String MATERIAL_YOU = "material_you";
    public static final String MATERIAL_YOU_BLACK = "material_you_black";

    private ThemePresets() {}

    /** A black background, with the surfaces above it derived the same way as for any scheme. */
    static Map<String, Integer> amoled() {
        return ThemeResolver.resolve(Collections.singletonMap("main", 0xFF000000), "button").colors;
    }

    /** Android 12 tonal palettes, following the Material 3 dark scheme. Black keeps the background black. */
    @TargetApi(Build.VERSION_CODES.S)
    static Map<String, Integer> materialYou(Context context, boolean black) {
        Map<String, Integer> roles = new LinkedHashMap<>();
        if (black) {
            roles.put("main", 0xFF000000);
            roles.put("main-elevated", context.getColor(android.R.color.system_neutral1_900));
            roles.put("card", context.getColor(android.R.color.system_neutral1_900));
            roles.put("highlight", context.getColor(android.R.color.system_neutral1_900));
            roles.put("highlight-elevated", context.getColor(android.R.color.system_neutral1_800));
        } else {
            roles.put("main", context.getColor(android.R.color.system_neutral1_900));
            roles.put("main-elevated", context.getColor(android.R.color.system_neutral1_800));
            roles.put("card", context.getColor(android.R.color.system_neutral2_800));
            roles.put("highlight", context.getColor(android.R.color.system_neutral1_800));
            roles.put("highlight-elevated", context.getColor(android.R.color.system_neutral1_700));
        }
        roles.put("text", context.getColor(android.R.color.system_neutral1_50));
        roles.put("subtext", context.getColor(android.R.color.system_neutral2_200));
        roles.put("button", context.getColor(android.R.color.system_accent1_200));
        roles.put("button-active", context.getColor(android.R.color.system_accent1_300));
        roles.put("on-button", context.getColor(android.R.color.system_accent1_800));
        roles.put("button-disabled", context.getColor(android.R.color.system_neutral2_600));
        roles.put("tab-active", context.getColor(android.R.color.system_accent2_700));
        roles.put("notification", context.getColor(android.R.color.system_accent3_300));
        return roles;
    }
}
