package com.byd.extend.remote

import com.byd.extend.remote.security.EncryptedFileSecretStore
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CameraViewingMirrorPersistenceTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    @Test
    fun helperRestartRestoresStoredMirrorUnlessTheAppProvidesItsCanonicalValue() {
        val root = temporary.newFolder("profiles")
        val key = ByteArray(32) { (it + 1).toByte() }
        EncryptedFileSecretStore(root, "owner-1001").apply {
            provision("vdo", key, emptyMap())
            putNative("profile.vdo.config",
                JSONObject().put("viewerLocale", "en").put("viewerMirrorCabin", true)
                    .toString().toByteArray())
        }

        val restarted = EncryptedFileSecretStore(root, "owner-1001").apply {
            provision("vdo", key, emptyMap())
        }
        val profile = JSONObject(String(checkNotNull(restarted.getNative("profile.vdo.config")), Charsets.UTF_8))

        assertTrue(resolveViewerMirrorCabin(JSONObject(), listOf(profile), false))
        assertFalse(resolveViewerMirrorCabin(JSONObject().put("mirrorCabin", false), listOf(profile), true))
    }

    @Test
    fun oldProfileWithoutMirrorSettingKeepsFreshDefaultOff() {
        val oldProfile = JSONObject().put("viewerLocale", "en").put("viewerTheme", "dark")

        assertFalse(resolveViewerMirrorCabin(JSONObject(), listOf(oldProfile), false))
    }
}
