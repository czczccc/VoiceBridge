package com.voicebridge.android.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.voicebridge.android.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class UpdateRepository(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun check(): UpdateManifest? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BuildConfig.UPDATE_MANIFEST_URL)
            .header("Cache-Control", "no-cache").build()
        client.newCall(request).await().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("Update server unavailable")
            val body = response.body ?: throw IOException("Missing manifest")
            val bytes = body.byteStream().readBytesLimited(16 * 1024)
            val manifest = UpdateManifest.parse(bytes.toString(Charsets.UTF_8))
            manifest.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        }
    }

    suspend fun download(manifest: UpdateManifest): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val temporary = File(folder, "update.part")
        val ready = File(folder, "update.apk")
        temporary.delete()
        ready.delete()
        try {
            val call = client.newCall(Request.Builder().url(manifest.apkUrl).build())
            val job = currentCoroutineContext()[Job]
            call.await().use { response ->
                if (!response.isSuccessful) throw IOException("APK download failed")
                val body = response.body ?: throw IOException("Missing APK")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            job?.ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            if (total > manifest.size) throw IOException("APK too large")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (total != manifest.size || hash != manifest.sha256) {
                    throw IOException("APK integrity check failed")
                }
            }
            currentCoroutineContext().ensureActive()
            verifyPackage(temporary, manifest)
            if (!temporary.renameTo(ready)) throw IOException("Cannot prepare APK")
            ready
        } finally {
            temporary.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, manifest: UpdateManifest) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
            ?: throw IOException("Invalid APK")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val expected = installed.signatures?.map { it.toCharsString() }?.toSet()
        val actual = archive.signatures?.map { it.toCharsString() }?.toSet()
        if (archive.packageName != context.packageName || code != manifest.versionCode ||
            expected.isNullOrEmpty() || actual != expected
        ) {
            throw IOException("APK identity or signing certificate mismatch")
        }
    }
}

private fun java.io.InputStream.readBytesLimited(maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count == -1) return output.toByteArray()
        if (output.size() + count > maximum) throw IOException("Manifest too large")
        output.write(buffer, 0, count)
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            // Close the response if cancellation wins before the caller receives it.
            continuation.resume(response) { _, resource, _ -> resource.close() }
        }
    })
}
