package com.bitchat.android.organizer

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.bitchat.android.protocol.BitchatPacket
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

object OrganizerIdentityManager {
    private const val TAG = "OrganizerIdentityManager"
    
    // The canonical public key for the Campus Festival 2026 Organizer.
    // Every attendee app uses this to verify official announcements.
    private const val ORGANIZER_PUB_KEY_HEX = "b70c287c5292cb599bf7cb455da47dc1536b586ec6b8a41ef771fc5da789baf8"
    
    private const val MAX_PASSCODE_ATTEMPTS = 5
    private const val LOCKOUT_DURATION_MS = 30_000L // 30 seconds lockout
    private const val ANNOUNCEMENT_FRESHNESS_WINDOW_MS = 300_000L // 5 minutes (same as MESSAGE_TIMEOUT_MS)

    private var failedPasscodeAttempts = 0
    private var lockoutUntilMs = 0L

    private const val PREFS_FILE = "organizer_secure_prefs"
    private const val KEY_ORGANIZER_PRIV = "organizer_priv_key_base64"
    private const val KEY_PASSCODE_HASH = "organizer_passcode_hash_b64"
    private const val KEY_PASSCODE_SALT = "organizer_passcode_salt_b64"

    private var prefs: SharedPreferences? = null
    private var memoryPasscodeHashB64: String? = null
    private var memoryPasscodeSaltB64: String? = null

    private var privateKeyParams: Ed25519PrivateKeyParameters? = null
    private val publicKeyParams: Ed25519PublicKeyParameters by lazy {
        Ed25519PublicKeyParameters(hexStringToByteArray(ORGANIZER_PUB_KEY_HEX), 0)
    }

    fun init(context: Context) {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val securePrefs = EncryptedSharedPreferences.create(
                PREFS_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs = securePrefs
            
            // Load key if provisioned
            val storedPriv = securePrefs.getString(KEY_ORGANIZER_PRIV, null)
            if (storedPriv != null) {
                try {
                    val privBytes = Base64.decode(storedPriv, Base64.DEFAULT)
                    privateKeyParams = Ed25519PrivateKeyParameters(privBytes, 0)
                    Log.d(TAG, "Loaded organizer identity.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse stored organizer private key.", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Organizer EncryptedSharedPreferences", e)
        }
    }

    /**
     * Checks if the device has the organizer private key provisioned.
     */
    fun isOrganizerProvisioned(): Boolean {
        return privateKeyParams != null
    }

    /**
     * Checks if an organizer mode passcode has been set.
     */
    fun isPasscodeSet(): Boolean {
        return (prefs?.getString(KEY_PASSCODE_HASH, null) ?: memoryPasscodeHashB64) != null
    }

    /**
     * Sets or provisions a local passcode for Organizer Mode using salted SHA-256 hashing.
     */
    fun setPasscode(passcode: String): Boolean {
        if (passcode.isBlank()) return false
        return try {
            val salt = ByteArray(16)
            SecureRandom().nextBytes(salt)
            val hash = hashPasscodeWithSalt(passcode, salt)

            val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
            val hashB64 = Base64.encodeToString(hash, Base64.NO_WRAP)

            memoryPasscodeSaltB64 = saltB64
            memoryPasscodeHashB64 = hashB64

            prefs?.edit()
                ?.putString(KEY_PASSCODE_SALT, saltB64)
                ?.putString(KEY_PASSCODE_HASH, hashB64)
                ?.apply()

            failedPasscodeAttempts = 0
            lockoutUntilMs = 0L
            Log.d(TAG, "Organizer local passcode set successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting organizer passcode", e)
            false
        }
    }

    /**
     * Checks if organizer passcode entry is currently locked out.
     */
    fun isLockedOut(): Boolean {
        return System.currentTimeMillis() < lockoutUntilMs
    }

    /**
     * Resets lockout state and in-memory passcode (primarily for unit tests).
     */
    fun resetLockoutForTesting() {
        failedPasscodeAttempts = 0
        lockoutUntilMs = 0L
    }

    /**
     * Clears all passcode state for testing purposes.
     */
    fun resetPasscodeForTesting() {
        resetLockoutForTesting()
        memoryPasscodeHashB64 = null
        memoryPasscodeSaltB64 = null
        prefs?.edit()?.remove(KEY_PASSCODE_HASH)?.remove(KEY_PASSCODE_SALT)?.apply()
    }

    /**
     * Validates the passcode to access the Organizer UI against the stored salted SHA-256 hash.
     * Enforces rate limiting: locks out after 5 consecutive failed attempts for 30 seconds.
     */
    fun validatePasscode(passcode: String): Boolean {
        if (isLockedOut()) {
            Log.w(TAG, "Passcode entry attempted while locked out.")
            return false
        }

        val storedSaltB64 = prefs?.getString(KEY_PASSCODE_SALT, null) ?: memoryPasscodeSaltB64
        val storedHashB64 = prefs?.getString(KEY_PASSCODE_HASH, null) ?: memoryPasscodeHashB64

        if (storedSaltB64 == null || storedHashB64 == null) {
            Log.w(TAG, "No organizer passcode provisioned.")
            failedPasscodeAttempts++
            checkLockout()
            return false
        }

        val isValid = try {
            val salt = Base64.decode(storedSaltB64, Base64.DEFAULT)
            val expectedHash = Base64.decode(storedHashB64, Base64.DEFAULT)
            val computedHash = hashPasscodeWithSalt(passcode, salt)
            MessageDigest.isEqual(expectedHash, computedHash)
        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating passcode hash", e)
            false
        }

        return if (isValid) {
            failedPasscodeAttempts = 0
            true
        } else {
            failedPasscodeAttempts++
            checkLockout()
            false
        }
    }

    private fun checkLockout() {
        if (failedPasscodeAttempts >= MAX_PASSCODE_ATTEMPTS) {
            lockoutUntilMs = System.currentTimeMillis() + LOCKOUT_DURATION_MS
            Log.w(TAG, "Excessive passcode attempts. Locked out for ${LOCKOUT_DURATION_MS / 1000}s.")
        }
    }

    private fun hashPasscodeWithSalt(passcode: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(passcode.toByteArray(Charsets.UTF_8))
    }

    /**
     * Provisions the organizer private key (hex string).
     */
    fun provisionOrganizer(privateKeyHex: String): Boolean {
        return try {
            val privBytes = hexStringToByteArray(privateKeyHex)
            if (privBytes.size != 32) {
                Log.e(TAG, "Invalid private key size. Must be 32 bytes.")
                return false
            }
            val privParams = Ed25519PrivateKeyParameters(privBytes, 0)
            
            // Validate it matches the hardcoded public key
            val derivedPubKey = privParams.generatePublicKey()
            if (!derivedPubKey.encoded.contentEquals(publicKeyParams.encoded)) {
                Log.e(TAG, "Provisioned private key does not match the official public key!")
                return false
            }

            privateKeyParams = privParams
            val b64 = Base64.encodeToString(privBytes, Base64.DEFAULT)
            prefs?.edit()?.putString(KEY_ORGANIZER_PRIV, b64)?.apply()
            Log.d(TAG, "Organizer successfully provisioned.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error provisioning organizer.", e)
            false
        }
    }

    /**
     * Clears the organizer private key (logout).
     */
    fun clearOrganizer() {
        privateKeyParams = null
        prefs?.edit()?.remove(KEY_ORGANIZER_PRIV)?.apply()
        Log.d(TAG, "Organizer credentials cleared.")
    }

    /**
     * Checks if an announcement timestamp falls within the allowed freshness window (5 minutes).
     */
    fun isAnnouncementFresh(
        timestamp: ULong,
        nowMs: Long = System.currentTimeMillis(),
        freshnessWindowMs: Long = ANNOUNCEMENT_FRESHNESS_WINDOW_MS
    ): Boolean {
        val now = nowMs.coerceAtLeast(0).toULong()
        val clockSkew = if (timestamp >= now) {
            timestamp - now
        } else {
            now - timestamp
        }
        return clockSkew <= freshnessWindowMs.toULong()
    }

    /**
     * Signs a BitchatPacket if provisioned.
     * Modifies the packet in-place to add the signature and returns true if successful.
     */
    fun signAnnouncement(packet: BitchatPacket): Boolean {
        val privKey = privateKeyParams
        if (privKey == null) {
            Log.e(TAG, "Cannot sign announcement. Device is not provisioned as organizer.")
            return false
        }

        return try {
            val dataToSign = packet.toBinaryDataForSigning()
            if (dataToSign == null) {
                Log.e(TAG, "Failed to canonicalize packet for signing.")
                return false
            }

            val signer = Ed25519Signer()
            signer.init(true, privKey)
            signer.update(dataToSign, 0, dataToSign.size)
            val signature = signer.generateSignature()
            
            packet.signature = signature
            Log.d(TAG, "Successfully signed announcement packet.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error signing announcement packet.", e)
            false
        }
    }

    /**
     * Verifies that the packet was signed by the official Organizer and has a fresh timestamp.
     */
    fun verifyAnnouncement(
        packet: BitchatPacket,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val signature = packet.signature
        if (signature == null || signature.isEmpty()) {
            Log.w(TAG, "Announcement packet has no signature.")
            return false
        }

        return try {
            val dataToVerify = packet.toBinaryDataForSigning()
            if (dataToVerify == null) {
                Log.e(TAG, "Failed to canonicalize packet for verification.")
                return false
            }

            val verifier = Ed25519Signer()
            verifier.init(false, publicKeyParams)
            verifier.update(dataToVerify, 0, dataToVerify.size)
            val isValidSignature = verifier.verifySignature(signature)
            
            if (!isValidSignature) {
                Log.w(TAG, "Announcement signature verification failed!")
                return false
            }

            if (!isAnnouncementFresh(packet.timestamp, nowMs)) {
                Log.w(TAG, "Announcement timestamp validation failed (expired or future-dated)!")
                return false
            }

            Log.d(TAG, "Announcement signature and timestamp verified successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying announcement packet.", e)
            false
        }
    }

    /**
     * Returns the 8-byte Organizer ID (truncated public key) to be used as senderID
     */
    fun getOrganizerSenderId(): ByteArray {
        return publicKeyParams.encoded.take(8).toByteArray()
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val len = hexString.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hexString[i], 16) shl 4)
                    + Character.digit(hexString[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}

