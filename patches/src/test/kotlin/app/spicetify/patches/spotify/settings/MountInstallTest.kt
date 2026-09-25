package app.spicetify.patches.spotify.settings

import app.morphe.patcher.patch.ResourcePatch
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MountInstallTest {
    // A root mount install keeps the stock manifest, so anything a resource patch adds to the
    // manifest never exists for the system.
    @Test
    fun `settings add no manifest components`() {
        assertTrue(settingsPatch.dependencies.none { it is ResourcePatch })
    }
}
