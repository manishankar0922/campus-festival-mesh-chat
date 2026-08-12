package com.bitchat.android.group

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec

/**
 * Android Keystore-backed secure storage for Private Friend Group epoch secret keys.
 *
 * CRITICAL SECURITY REQUIREMENT:
 * Group secret keys MUST NOT be stored in plaintext files, normal SQLite databases,
 * or embedded inside PrivateGroup metadata objects. All epoch secret keys are
 * stored in EncryptedSharedPreferences using MasterKey AES-256-GCM.
 *
 * Key Alias Format: grp_key_<groupId>_<epoch>
 */
class SecureGroupKeyStore {

    companion object {
        private const val TAG = "SecureGroupKeyStore"
        private const val PREFS_NAME = "bitchat_group_keys"
        private const val KEY_PREFIX = "grp_key_"

        @Volatile
        private var INSTANCE: SecureGroupKeyStore? = null

        fun getInstance(context: Context): SecureGroupKeyStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureGroupKeyStore(context.applicationContext).also { INSTANCE = it }
            }
        }

        internal fun createTestInstance(testPrefs: SharedPreferences): SecureGroupKeyStore {
            return SecureGroupKeyStore(testPrefs, isTest = true)
        }

        internal fun makeKeyAlias(groupId: String, epoch: Int): String {
            return "${KEY_PREFIX}${groupId}_${epoch}"
        }
    }

    private val prefs: SharedPreferences
    private val memoryCache = ConcurrentHashMap<String, SecretKeySpec>()

    constructor(context: Context) {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    internal constructor(testPrefs: SharedPreferences, isTest: Boolean) {
        require(isTest) { "Test constructor is strictly for unit testing" }
        this.prefs = testPrefs
    }

    /**
     * Saves a 256-bit AES secret key for a specific group and epoch.
     */
    fun saveGroupKey(groupId: String, epoch: Int, secretKey: SecretKeySpec): Boolean {
        require(groupId.isNotBlank()) { "groupId must not be blank" }
        require(epoch >= 1) { "epoch must be >= 1" }
        require(secretKey.encoded.size == 32) { "Group key must be 256 bits (32 bytes)" }

        val alias = makeKeyAlias(groupId, epoch)
        return try {
            val base64Key = java.util.Base64.getEncoder().encodeToString(secretKey.encoded)
            prefs.edit().putString(alias, base64Key).apply()
            memoryCache[alias] = secretKey
            Log.d(TAG, "Saved group key for $groupId epoch $epoch")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save group key for $groupId epoch $epoch: ${e.message}")
            false
        }
    }

    /**
     * Retrieves the 256-bit AES secret key for a specific group and epoch.
     * Returns null if key does not exist or fails to parse.
     */
    fun getGroupKey(groupId: String, epoch: Int): SecretKeySpec? {
        if (groupId.isBlank() || epoch < 1) return null
        val alias = makeKeyAlias(groupId, epoch)

        memoryCache[alias]?.let { return it }

        return try {
            val base64Key = prefs.getString(alias, null) ?: return null
            val keyBytes = java.util.Base64.getDecoder().decode(base64Key)
            if (keyBytes.size != 32) {
                Log.e(TAG, "Invalid key length stored for $alias")
                return null
            }
            SecretKeySpec(keyBytes, "AES").also {
                memoryCache[alias] = it
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve group key for $groupId epoch $epoch: ${e.message}")
            null
        }
    }

    /**
     * Checks if a group key exists for a given group and epoch.
     */
    fun hasGroupKey(groupId: String, epoch: Int): Boolean {
        if (groupId.isBlank() || epoch < 1) return false
        val alias = makeKeyAlias(groupId, epoch)
        return memoryCache.containsKey(alias) || prefs.contains(alias)
    }

    /**
     * Removes all epoch keys associated with a group.
     */
    fun removeGroupKeys(groupId: String) {
        if (groupId.isBlank()) return
        val prefixMatch = "${KEY_PREFIX}${groupId}_"
        try {
            val editor = prefs.edit()
            val keysToRemove = prefs.all.keys.filter { it.startsWith(prefixMatch) }
            for (key in keysToRemove) {
                editor.remove(key)
                memoryCache.remove(key)
            }
            editor.apply()
            Log.d(TAG, "Removed all keys for group $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove keys for group $groupId: ${e.message}")
        }
    }

    /**
     * Clears in-memory cache (for testing or memory pressure management).
     */
    fun clearCacheForTesting() {
        memoryCache.clear()
    }
}
