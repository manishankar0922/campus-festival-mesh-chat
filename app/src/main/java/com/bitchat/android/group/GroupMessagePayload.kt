package com.bitchat.android.group

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary wire structure for GROUP_MESSAGE packets (MessageType 0x08u).
 *
 * Binary Layout:
 * - Group ID Bytes: 16 bytes (raw 128-bit random Group ID)
 * - Epoch Index: 4 bytes (big-endian Int)
 * - Initialization Vector (IV): 12 bytes (raw AES-GCM IV)
 * - Ciphertext: Variable length (includes AES-256-GCM ciphertext + 16-byte auth tag)
 *
 * Header size = 16 + 4 + 12 = 32 bytes.
 * Minimum total length (with 16-byte GCM tag) = 48 bytes.
 */
data class GroupMessagePayload(
    val groupIdBytes: ByteArray,
    val epoch: Int,
    val iv: ByteArray,
    val ciphertext: ByteArray
) {
    init {
        require(groupIdBytes.size == GROUP_ID_SIZE) { "groupIdBytes must be exactly 16 bytes" }
        require(iv.size == IV_SIZE) { "iv must be exactly 12 bytes" }
        require(ciphertext.isNotEmpty()) { "ciphertext must not be empty" }
    }

    /**
     * Encodes this payload into binary wire format.
     */
    fun encode(): ByteArray {
        val totalSize = HEADER_SIZE + ciphertext.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(groupIdBytes)
        buffer.putInt(epoch)
        buffer.put(iv)
        buffer.put(ciphertext)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GroupMessagePayload

        if (!groupIdBytes.contentEquals(other.groupIdBytes)) return false
        if (epoch != other.epoch) return false
        if (!iv.contentEquals(other.iv)) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = groupIdBytes.contentHashCode()
        result = 31 * result + epoch
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    companion object {
        const val GROUP_ID_SIZE = 16
        const val EPOCH_SIZE = 4
        const val IV_SIZE = 12
        const val GCM_TAG_SIZE = 16
        const val HEADER_SIZE = GROUP_ID_SIZE + EPOCH_SIZE + IV_SIZE // 32 bytes
        const val MIN_PAYLOAD_SIZE = HEADER_SIZE + GCM_TAG_SIZE // 48 bytes
        const val MAX_PAYLOAD_SIZE = 64 * 1024 // 64 KB safety bound

        /**
         * Parses binary wire format into a GroupMessagePayload object.
         * Returns null if bytes are malformed, undersized (< 48 bytes), or oversized.
         */
        fun decode(bytes: ByteArray): GroupMessagePayload? {
            if (bytes.size < MIN_PAYLOAD_SIZE || bytes.size > MAX_PAYLOAD_SIZE) return null
            return try {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                val groupIdBytes = ByteArray(GROUP_ID_SIZE)
                buffer.get(groupIdBytes)
                val epoch = buffer.int
                val iv = ByteArray(IV_SIZE)
                buffer.get(iv)
                val ciphertext = ByteArray(buffer.remaining())
                buffer.get(ciphertext)

                if (ciphertext.size < GCM_TAG_SIZE) return null

                GroupMessagePayload(
                    groupIdBytes = groupIdBytes,
                    epoch = epoch,
                    iv = iv,
                    ciphertext = ciphertext
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Builds deterministic Associated Authenticated Data (AAD) for AES-256-GCM encryption/decryption.
         *
         * Layout (36 bytes total):
         * - groupIdBytes: 16 bytes
         * - epoch: 4 bytes (big-endian Int)
         * - senderIdBytes: 8 bytes
         * - timestampMs: 8 bytes (big-endian Long)
         */
        fun buildAAD(
            groupIdBytes: ByteArray,
            epoch: Int,
            senderIdBytes: ByteArray,
            timestampMs: Long
        ): ByteArray {
            require(groupIdBytes.size == GROUP_ID_SIZE) { "groupIdBytes must be exactly 16 bytes" }
            require(senderIdBytes.size == 8) { "senderIdBytes must be exactly 8 bytes" }

            val buffer = ByteBuffer.allocate(GROUP_ID_SIZE + EPOCH_SIZE + 8 + 8).order(ByteOrder.BIG_ENDIAN)
            buffer.put(groupIdBytes)
            buffer.putInt(epoch)
            buffer.put(senderIdBytes)
            buffer.putLong(timestampMs)
            return buffer.array()
        }
    }
}
