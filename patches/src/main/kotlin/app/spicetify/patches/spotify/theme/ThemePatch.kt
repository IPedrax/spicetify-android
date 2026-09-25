package app.spicetify.patches.spotify.theme

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.spicetify.patches.spotify.settings.themeSettingsPatch
import app.spicetify.patches.spotify.spotifyCompatibility
import javax.xml.parsers.DocumentBuilderFactory

private const val ROLE_MAP_CLASS = "Lapp/spicetify/extension/spotify/theme/ThemeRoleMap;"

/** Filled by [themeResourcesPatch], which [themePatch] depends on. */
private var roleTableForExtension: String? = null

private val themeResourcesPatch = resourcePatch {
    execute {
        val roleMap = loadRoleMap()
        // Read-only: the colors keep their stock values; only overlayable.xml changes.
        val colors = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(get("res/values/colors.xml"))
        roleTableForExtension = roleTable(colors, roleMap)
        val overlayable = get("res/values/overlayable.xml")
        val declaration = overlayableXml(roleMap.values.flatten())
        if (overlayable.exists()) {
            val inner = declaration.substringAfter("<resources>\n").substringBefore("</resources>")
            overlayable.writeText(overlayable.readText().replaceFirst("</resources>", "$inner</resources>"))
        } else {
            overlayable.writeText(declaration)
        }
    }
}

@Suppress("unused")
val themePatch = bytecodePatch(
    name = "Theme colors",
    description = "Adds a theme picker to Spicetify settings: presets, Material You and Spicetify themes. " +
        "Requires Android 14 or later. Some hardcoded colors and animations keep Spotify's look.",
    default = false,
) {
    compatibleWith(spotifyCompatibility)
    dependsOn(themeSettingsPatch, themeResourcesPatch)

    execute {
        val table = requireNotNull(roleTableForExtension) { "The theme resource patch did not run first." }
        mutableClassDefBy(ROLE_MAP_CLASS).methods.single { it.name == "encoded" }
            .replaceInstruction(0, "const-string v0, \"$table\"")
    }
}
