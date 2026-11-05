package ug.kuuma.guardian

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianManifestTest {
    /** A permission listed for the setup wizard but missing from the manifest can never be granted. */
    @Test fun `every declared permission is in the manifest`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (p in GuardianModule().permissions) {
            assertTrue(p.permission, manifest.contains("android:name=\"${p.permission}\""))
        }
    }
}
