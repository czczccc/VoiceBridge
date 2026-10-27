package com.voicebridge.android.update

import org.json.JSONObject

data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
) {
    companion object {
        const val MAX_APK_BYTES = 100L * 1024 * 1024
        private const val APK_PREFIX = "https://github.com/czczccc/VoiceBridge/releases/download/android-testing/"

        fun parse(json: String): UpdateManifest {
            val value = JSONObject(json)
            require(value.getInt("schemaVersion") == 1)
            val code = value.getLong("versionCode")
            val name = value.getString("versionName")
            val url = value.getString("apkUrl")
            val hash = value.getString("sha256")
            val size = value.getLong("size")
            require(code in 1..2100000000L)
            require(name.length in 1..80)
            require(url == "${APK_PREFIX}VoiceBridge-$code.apk")
            require(hash.matches(Regex("[a-f0-9]{64}")))
            require(size in 1..MAX_APK_BYTES)
            return UpdateManifest(code, name, url, hash, size)
        }
    }
}
