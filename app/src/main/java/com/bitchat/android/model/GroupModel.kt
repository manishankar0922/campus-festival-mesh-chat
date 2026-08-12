package com.bitchat.android.model

import java.security.SecureRandom
import java.util.Locale

/**
 * Core metadata representation of a Private Friend Group.
 *
 * CRITICAL SECURITY REQUIREMENT:
 * This model MUST NOT contain raw secret key material. Secret keys are
 * decoupled and stored exclusively in secure key storage (e.g. EncryptedSharedPreferences).
 */
data class PrivateGroup(
    val groupId: String,
    val groupName: String,
    val creatorPeerId: String,
    val creatorSigningPubKey: ByteArray,
    val adminPeerIds: Set<String>,
    val memberPeerIds: Set<String>,
    val memberSigningKeys: Map<String, ByteArray>,
    val activeEpoch: Int = 1,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PrivateGroup

        if (groupId != other.groupId) return false
        if (groupName != other.groupName) return false
        if (creatorPeerId != other.creatorPeerId) return false
        if (!creatorSigningPubKey.contentEquals(other.creatorSigningPubKey)) return false
        if (adminPeerIds != other.adminPeerIds) return false
        if (memberPeerIds != other.memberPeerIds) return false
        if (activeEpoch != other.activeEpoch) return false
        if (createdAtMs != other.createdAtMs) return false

        if (memberSigningKeys.size != other.memberSigningKeys.size) return false
        for ((key, value) in memberSigningKeys) {
            val otherValue = other.memberSigningKeys[key] ?: return false
            if (!value.contentEquals(otherValue)) return false
        }

        return true
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + groupName.hashCode()
        result = 31 * result + creatorPeerId.hashCode()
        result = 31 * result + creatorSigningPubKey.contentHashCode()
        result = 31 * result + adminPeerIds.hashCode()
        result = 31 * result + memberPeerIds.hashCode()
        result = 31 * result + activeEpoch
        result = 31 * result + createdAtMs.hashCode()
        return result
    }
}

/**
 * Utilities for 128-bit (16-byte) random Group ID generation, formatting, and raw byte extraction.
 */
object GroupId {
    private const val PREFIX = "grp_"
    private const val HEX_DIGITS_LENGTH = 32 // 16 bytes = 32 hex chars
    const val RAW_BYTE_SIZE = 16

    /**
     * Generates a new 16-byte random group ID string formatted as "grp_<32 hex chars>".
     */
    fun generate(): String {
        val randomBytes = ByteArray(RAW_BYTE_SIZE)
        SecureRandom().nextBytes(randomBytes)
        return PREFIX + bytesToHex(randomBytes)
    }

    /**
     * Checks whether a string is a valid group ID ("grp_" prefix followed by 32 hex chars).
     */
    fun isValid(groupIdStr: String?): Boolean {
        if (groupIdStr.isNullOrBlank()) return false
        if (!groupIdStr.startsWith(PREFIX)) return false
        val hexPart = groupIdStr.substring(PREFIX.length)
        if (hexPart.length != HEX_DIGITS_LENGTH) return false
        return hexPart.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    /**
     * Converts a "grp_<32 hex chars>" group ID string to raw 16 bytes.
     * Returns null if string format is invalid.
     */
    fun toRawBytes(groupIdStr: String): ByteArray? {
        if (!isValid(groupIdStr)) return null
        val hexPart = groupIdStr.substring(PREFIX.length)
        return try {
            hexToBytes(hexPart)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Converts raw 16 bytes to the standard "grp_<32 hex chars>" string.
     * Returns null if bytes size is not exactly 16.
     */
    fun fromRawBytes(bytes: ByteArray): String? {
        if (bytes.size != RAW_BYTE_SIZE) return null
        return PREFIX + bytesToHex(bytes)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}

/**
 * Validation and sanitization rules for group names.
 */
object GroupName {
    const val MAX_LENGTH = 30

    /**
     * Sanitizes a proposed group name:
     * - Replaces control characters and newlines with spaces.
     * - Collapses multiple spaces into a single space.
     * - Trims whitespace.
     * - Truncates to MAX_LENGTH (30) characters.
     */
    fun sanitize(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val cleaned = input.replace(Regex("[\\r\\n\\t\\u0000-\\u001F\\u007F-\\u009F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.take(MAX_LENGTH)
    }

    /**
     * Checks if a group name is valid (non-empty after sanitization, 1 to 30 characters).
     */
    fun isValid(name: String?): Boolean {
        val sanitized = sanitize(name)
        return sanitized.isNotEmpty() && sanitized.length <= MAX_LENGTH
    }
}
