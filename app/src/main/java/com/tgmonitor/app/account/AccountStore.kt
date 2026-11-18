package com.tgmonitor.app.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.accountDataStore by preferencesDataStore(name = "account")

/** AES-GCM with a non-exportable key kept in the Android Keystore. */
class SecretStore(private val alias: String = "tgmonitor_secret_key") {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): ByteArray {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        return cipher.doFinal(all, 12, all.size - 12)
    }
}

class AccountStore(context: Context, private val secrets: SecretStore) {
    private val ds = context.applicationContext.accountDataStore
    private val apiIdKey = intPreferencesKey("api_id")
    private val apiHashKey = stringPreferencesKey("api_hash_enc")
    private val dbKeyKey = stringPreferencesKey("db_key_enc")
    private val channelsKey = stringSetPreferencesKey("account_channels")
    private val lastJoinKey = longPreferencesKey("last_join_at")

    suspend fun saveCredentials(apiId: Int, apiHash: String) {
        val enc = secrets.encrypt(apiHash.toByteArray(Charsets.UTF_8))
        ds.edit { it[apiIdKey] = apiId; it[apiHashKey] = enc }
    }

    suspend fun credentials(): Pair<Int, String>? {
        val p = ds.data.first()
        val id = p[apiIdKey] ?: return null
        val enc = p[apiHashKey] ?: return null
        return try {
            id to String(secrets.decrypt(enc), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** Random key that encrypts TDLib's local database; itself stored encrypted by the Keystore key. */
    suspend fun databaseKey(): ByteArray {
        val p = ds.data.first()
        p[dbKeyKey]?.let { enc ->
            try {
                return secrets.decrypt(enc)
            } catch (e: Exception) {
                // fall through and create a new key
            }
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val enc = secrets.encrypt(key)
        ds.edit { it[dbKeyKey] = enc }
        return key
    }

    val accountChannelIds: Flow<Set<Long>> = ds.data.map { p ->
        (p[channelsKey] ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
    }

    suspend fun channelUsesAccount(id: Long): Boolean = accountChannelIds.first().contains(id)

    suspend fun setChannelUsesAccount(id: Long, enabled: Boolean) {
        ds.edit { prefs ->
            val cur = (prefs[channelsKey] ?: emptySet()).toMutableSet()
            if (enabled) cur.add(id.toString()) else cur.remove(id.toString())
            prefs[channelsKey] = cur
        }
    }

    suspend fun lastJoinAt(): Long = ds.data.first()[lastJoinKey] ?: 0L

    suspend fun setLastJoinAt(ms: Long) {
        ds.edit { it[lastJoinKey] = ms }
    }
}
