package io.ucc.core.singbox.apple

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The in-code metadata must agree with the repository pin files (single source: core/engine-singbox). */
class PinFilesTest {
    private fun pin(name: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return File(dir!!, "core/engine-singbox/$name").readText().trim()
    }
    @Test fun metadata_matches_pin_files() {
        assertEquals(pin("singbox.version"), LibboxMetadata.SING_BOX_VERSION)
        assertEquals(pin("singbox.commit"), LibboxMetadata.SING_BOX_COMMIT)
        assertEquals(pin("libbox-apple.sha256"), LibboxMetadata.pinStatus)
    }
}
