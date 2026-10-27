package com.voicebridge.android.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PREFERENCES_NAME = "voicebridge-credentials"
private const val ENTRY_CREDENTIAL = "credential"
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val KEY_ALIAS = "voicebridge-credentials"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val GCM_IV_BYTES = 12
private const val AES_KEY_BITS = 256

private val credentialJson = Json { ignoreUnknownKeys = true }

/** Everything needed to reconnect to one paired Windows device. Never log this type. */
data class StoredCredential(
    val serverDeviceId: String,
    val serverName: String,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val token: String,
) {
    // A generated data class toString would print the access token into logs and crash reports.
    override fun toString() =
        "StoredCredential(serverDeviceId=$serverDeviceId, serverName=$serverName, host=$host, " +
            "port=$port, fingerprint=$fingerprint, token=<redacted>)"
}

interface CredentialStore {
    suspend fun load(): StoredCredential?

    suspend fun save(credential: StoredCredential)

    suspend fun clear()
}

/**
 * Stores the single paired credential in private SharedPreferences, encrypted with an AES-GCM key
 * that lives in the Android Keystore and cannot be exported or read back as bytes.
 *
 * Decryption failure is treated as "not paired": the unreadable blob is dropped so the user can
 * pair again, and no plaintext fallback exists.
 */
class AndroidCredentialStore(context: Context) : CredentialStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): StoredCredential? = withContext(Dispatchers.IO) {
        val blob = preferences.getString(ENTRY_CREDENTIAL, null) ?: return@withContext null
        val plaintext = try {
            decrypt(blob)
        } catch (error: GeneralSecurityException) {
            discardUnreadable()
            return@withContext null
        } catch (error: IllegalArgumentException) {
            discardUnreadable()
            return@withContext null
        }
        try {
            val payload = credentialJson.decodeFromString<StoredCredentialPayload>(
                plaintext.toString(Charsets.UTF_8),
            )
            StoredCredential(
                serverDeviceId = payload.serverDeviceId,
                serverName = payload.serverName,
                host = payload.host,
                port = payload.port,
                fingerprint = payload.fingerprint,
                token = payload.token,
            )
        } catch (error: SerializationException) {
            discardUnreadable()
            null
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun save(credential: StoredCredential) {
        withContext(Dispatchers.IO) {
            val payload = StoredCredentialPayload(
                serverDeviceId = credential.serverDeviceId,
                serverName = credential.serverName,
                host = credential.host,
                port = credential.port,
                fingerprint = credential.fingerprint,
                token = credential.token,
            )
            val plaintext = credentialJson.encodeToString(payload).toByteArray(Charsets.UTF_8)
            val blob = try {
                encrypt(plaintext)
            } finally {
                plaintext.fill(0)
            }
            // commit() rather than apply(): a silently dropped write would cost the user a re-pair.
            if (!preferences.edit().putString(ENTRY_CREDENTIAL, blob).commit()) {
                throw IOException("Cannot store the pairing credential")
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            if (!preferences.edit().remove(ENTRY_CREDENTIAL).commit()) {
                throw IOException("Cannot clear the pairing credential")
            }
        }
    }

    private fun decrypt(blob: String): ByteArray {
        val bytes = Base64.getDecoder().decode(blob)
        if (bytes.size <= GCM_IV_BYTES) throw IllegalArgumentException("Stored credential is too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES),
        )
        return cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES)
    }

    private fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore generates the IV; it is stored in front of the ciphertext.
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plaintext))
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    /**
     * Fail closed: an unreadable blob means "not paired", so the user pairs again. A failed removal
     * needs no separate error because the next [save] overwrites the same entry.
     */
    private fun discardUnreadable() {
        preferences.edit().remove(ENTRY_CREDENTIAL).apply()
    }
}

@Serializable
private class StoredCredentialPayload(
    val serverDeviceId: String,
    val serverName: String,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val token: String,
)
