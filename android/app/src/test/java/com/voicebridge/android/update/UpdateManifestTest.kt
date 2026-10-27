package com.voicebridge.android.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateManifestTest {
    private fun valid() = JSONObject()
        .put("schemaVersion", 1)
        .put("versionCode", 12)
        .put("versionName", "0.1.0-test.12")
        .put("apkUrl", "https://github.com/czczccc/VoiceBridge/releases/download/android-testing/VoiceBridge-12.apk")
        .put("sha256", "a".repeat(64))
        .put("size", 1234)

    @Test
    fun acceptsPublishedManifest() {
        assertEquals(12L, UpdateManifest.parse(valid().toString()).versionCode)
    }

    @Test
    fun rejectsUntrustedUrls() {
        for (url in listOf(
            "http://github.com/czczccc/VoiceBridge/releases/download/android-testing/test.apk",
            "https://example.com/test.apk",
            "https://github.com/other/VoiceBridge/releases/download/android-testing/test.apk",
            "https://github.com/czczccc/VoiceBridge/releases/download/android-testing/test.apk?redirect=1",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                UpdateManifest.parse(valid().put("apkUrl", url).toString())
            }
        }
    }

    @Test
    fun rejectsInvalidSizeVersionAndDigest() {
        for ((key, value) in listOf(
            "schemaVersion" to 2,
            "versionCode" to 0,
            "size" to 0,
            "size" to UpdateManifest.MAX_APK_BYTES + 1,
            "sha256" to "invalid",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                UpdateManifest.parse(valid().put(key, value).toString())
            }
        }
    }
}
