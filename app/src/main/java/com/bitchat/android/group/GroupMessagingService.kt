package com.bitchat.android.group

import android.util.Log
import com.bitchat.android.mesh.MeshPacketUtils
import com.bitchat.android.model.GroupId
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.util.hexEncodedString
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

data class DecryptedGroupMessage(
    val messageId: String,
    val groupId: String,
    val epoch: Int,
    val senderPeerId: String,
    val content: String,
    val timestampMs: Long
)

/**
 * Service for outgoing AES-256-GCM group message encryption and incoming
 * group message decryption, AAD validation, membership verification,
 * and Ed25519 signature authentication.
 */
object GroupMessagingService {

    private const val TAG = "GroupMessagingService"

    /**
     * Encrypts a group text message using AES-256-GCM and builds a signed BitchatPacket (Type 0x08u).
     */
    fun encryptAndBuildPacket(
        groupId: String,
        content: String,
        senderPeerId: String,
        senderSigningPrivateKey: ByteArray,
        groupManager: GroupManager,
        nowMs: Long = System.currentTimeMillis()
    ): BitchatPacket? {
        if (content.isEmpty()) return null

        val group = groupManager.getGroup(groupId) ?: run {
            Log.w(TAG, "Cannot encrypt group message: unknown group $groupId")
            return null
        }

        if (!group.memberPeerIds.contains(senderPeerId)) {
            Log.w(TAG, "Cannot encrypt group message: sender $senderPeerId is not a member of $groupId")
            return null
        }

        val (epoch, secretKey) = groupManager.getActiveEpochKey(groupId) ?: run {
            Log.e(TAG, "Cannot encrypt group message: missing active key for $groupId")
            return null
        }

        val groupIdBytes = GroupId.toRawBytes(groupId) ?: return null
        val senderIdBytes = MeshPacketUtils.hexStringToByteArray(senderPeerId)
        if (senderIdBytes.size != 8) return null

        // 1. Generate fresh random 12-byte IV
        val iv = ByteArray(GroupMessagePayload.IV_SIZE)
        SecureRandom().nextBytes(iv)

        // 2. Build 36-byte deterministic AAD
        val aad = GroupMessagePayload.buildAAD(groupIdBytes, epoch, senderIdBytes, nowMs)

        // 3. AES-256-GCM Encryption
        val ciphertext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
            cipher.updateAAD(aad)
            cipher.doFinal(content.toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "AES-GCM encryption failed: ${e.message}")
            return null
        }

        // 4. Construct GroupMessagePayload
        val messagePayload = GroupMessagePayload(
            groupIdBytes = groupIdBytes,
            epoch = epoch,
            iv = iv,
            ciphertext = ciphertext
        )
        val payloadBytes = messagePayload.encode()

        // 5. Construct BitchatPacket
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.GROUP_MESSAGE.value,
            senderID = senderIdBytes,
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = nowMs.toULong(),
            payload = payloadBytes,
            signature = null,
            ttl = 7u
        )

        // 6. Sign packet with sender's Ed25519 private key
        val dataToSign = packet.toBinaryDataForSigning() ?: return null
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(senderSigningPrivateKey, 0))
        signer.update(dataToSign, 0, dataToSign.size)
        packet.signature = signer.generateSignature()

        return packet
    }

    /**
     * Decrypts and validates an incoming GROUP_MESSAGE BitchatPacket (Type 0x08u).
     * Enforces membership verification, Ed25519 signature verification,
     * AAD validation, 2-minute key grace semantics, and AES-256-GCM authentication.
     */
    fun decryptAndValidatePacket(
        packet: BitchatPacket,
        signingPublicKey: ByteArray?,
        groupManager: GroupManager,
        nowMs: Long = System.currentTimeMillis()
    ): DecryptedGroupMessage? {
        if (packet.type != MessageType.GROUP_MESSAGE.value) return null

        val payload = GroupMessagePayload.decode(packet.payload) ?: run {
            Log.w(TAG, "Failed to parse GroupMessagePayload from packet")
            return null
        }

        val groupId = GroupId.fromRawBytes(payload.groupIdBytes) ?: run {
            Log.w(TAG, "Invalid groupId bytes in GroupMessagePayload")
            return null
        }

        val group = groupManager.getGroup(groupId) ?: run {
            Log.w(TAG, "Received group message for unknown group $groupId")
            return null
        }

        val senderPeerId = packet.senderID.hexEncodedString()

        // Membership check: sender MUST be an active member of group
        if (!group.memberPeerIds.contains(senderPeerId)) {
            Log.w(TAG, "Rejecting group message: sender $senderPeerId is not an active member of group $groupId")
            return null
        }

        // Ed25519 Outer Signature Verification
        if (signingPublicKey != null) {
            if (packet.signature == null || signingPublicKey.size != 32) {
                Log.w(TAG, "Rejecting group message: missing signature or invalid public key")
                return null
            }
            val dataToSign = packet.toBinaryDataForSigning() ?: return null
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(signingPublicKey, 0))
            verifier.update(dataToSign, 0, dataToSign.size)
            if (!verifier.verifySignature(packet.signature)) {
                Log.w(TAG, "Rejecting group message: Ed25519 signature verification failed")
                return null
            }
        }

        // Retrieve epoch key (enforces 2-minute key grace period for pre-rotation messages)
        val epochKey = groupManager.getGraceEpochKey(groupId, payload.epoch, packet.timestamp.toLong(), nowMs) ?: run {
            Log.w(TAG, "Rejecting group message: missing epoch key for $groupId epoch ${payload.epoch}")
            return null
        }

        // Reconstruct 36-byte AAD
        val aad = GroupMessagePayload.buildAAD(
            payload.groupIdBytes,
            payload.epoch,
            packet.senderID,
            packet.timestamp.toLong()
        )

        // AES-256-GCM Decryption
        val decryptedBytes = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(128, payload.iv)
            cipher.init(Cipher.DECRYPT_MODE, epochKey, gcmSpec)
            cipher.updateAAD(aad)
            cipher.doFinal(payload.ciphertext)
        } catch (e: Exception) {
            Log.w(TAG, "AES-GCM decryption/authentication failed for group $groupId: ${e.message}")
            return null
        }

        val content = String(decryptedBytes, StandardCharsets.UTF_8)
        val messageId = "${packet.timestamp}-$senderPeerId-${payload.ciphertext.contentHashCode()}"

        return DecryptedGroupMessage(
            messageId = messageId,
            groupId = groupId,
            epoch = payload.epoch,
            senderPeerId = senderPeerId,
            content = content,
            timestampMs = packet.timestamp.toLong()
        )
    }
}
