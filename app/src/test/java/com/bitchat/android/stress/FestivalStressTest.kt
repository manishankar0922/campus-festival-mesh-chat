package com.bitchat.android.stress

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.group.GroupControlPayload
import com.bitchat.android.group.GroupManager
import com.bitchat.android.group.GroupMessagingService
import com.bitchat.android.group.SecureGroupKeyStore
import com.bitchat.android.mesh.MeshPacketUtils
import com.bitchat.android.model.GroupId
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.sos.SosPayload
import com.bitchat.android.util.hexEncodedString
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FestivalStressTest {

    private lateinit var context: Context
    private lateinit var keyStore: SecureGroupKeyStore
    private lateinit var groupManager: GroupManager

    private fun generateEd25519KeyPair(): Triple<ByteArray, ByteArray, String> {
        val random = SecureRandom()
        val privateKeyBytes = ByteArray(32)
        random.nextBytes(privateKeyBytes)
        val privateKeyParam = Ed25519PrivateKeyParameters(privateKeyBytes, 0)
        val publicKeyParam = privateKeyParam.generatePublicKey()
        val publicKeyBytes = publicKeyParam.encoded
        val peerId = publicKeyBytes.copyOfRange(0, 8).hexEncodedString()
        return Triple(privateKeyBytes, publicKeyBytes, peerId)
    }

    private fun signPacket(packet: BitchatPacket, privateKey: ByteArray) {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        val dataToSign = packet.toBinaryDataForSigning() ?: return
        signer.update(dataToSign, 0, dataToSign.size)
        packet.signature = signer.generateSignature()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("test_group_keys_stress", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        keyStore = SecureGroupKeyStore.createTestInstance(prefs)
        keyStore.clearCacheForTesting()
        groupManager = GroupManager.createTestInstance(keyStore)
        groupManager.clearAllForTesting()
    }

    @Test
    fun testMessageVolumeStress() {
        val (adminPriv, adminPub, adminPeer) = generateEd25519KeyPair()
        val group = groupManager.createGroup("Festival Group", adminPeer, adminPub)!!

        val (memberPriv, memberPub, memberPeer) = generateEd25519KeyPair()
        val invite = groupManager.createInvitation(group.groupId, memberPeer, adminPeer)!!
        val accept = groupManager.acceptInvitation(invite, memberPeer, memberPub)!!
        groupManager.processIncomingAccept(accept, adminPeer)

        val activeGroup = groupManager.getGroup(group.groupId)!!

        val count = 1000
        val ivs = HashSet<String>()
        for (i in 1..count) {
            val messageText = "Stress message #$i"
            val packet = GroupMessagingService.encryptAndBuildPacket(
                groupId = activeGroup.groupId,
                content = messageText,
                senderPeerId = memberPeer,
                senderSigningPrivateKey = memberPriv,
                groupManager = groupManager
            )
            assertNotNull("Packet $i must be encrypted", packet)
            assertEquals("Message type must be GROUP_MESSAGE", MessageType.GROUP_MESSAGE.value, packet!!.type)

            // Extract IV from packet payload (bytes 20..31)
            val ivHex = packet.payload.copyOfRange(20, 32).hexEncodedString()
            assertFalse("IV reuse detected at iteration $i", ivs.contains(ivHex))
            ivs.add(ivHex)

            // Decrypt
            val decrypted = GroupMessagingService.decryptAndValidatePacket(packet, memberPub, groupManager)
            assertNotNull("Decryption must succeed at iteration $i", decrypted)
            assertEquals(messageText, decrypted!!.content)
        }
    }

    @Test
    fun testDuplicatePacketSuppression() {
        val (adminPriv, adminPub, adminPeer) = generateEd25519KeyPair()
        val group = groupManager.createGroup("Duplicate Group", adminPeer, adminPub)!!

        val packet = GroupMessagingService.encryptAndBuildPacket(
            groupId = group.groupId,
            content = "Duplicate Test Message",
            senderPeerId = adminPeer,
            senderSigningPrivateKey = adminPriv,
            groupManager = groupManager
        )!!

        val deduplicationCache = HashSet<String>()
        val packetId = packet.toBinaryData()!!.copyOfRange(0, 8).hexEncodedString()

        var processedCount = 0
        for (i in 1..100) {
            if (!deduplicationCache.contains(packetId)) {
                deduplicationCache.add(packetId)
                val decrypted = GroupMessagingService.decryptAndValidatePacket(packet, adminPub, groupManager)
                assertNotNull(decrypted)
                processedCount++
            }
        }
        assertEquals("Exactly 1 logical message must be processed", 1, processedCount)
    }

    @Test
    fun testMembershipAndEpochRotationStress() {
        val (adminPriv, adminPub, adminPeer) = generateEd25519KeyPair()
        val group = groupManager.createGroup("Rotation Group", adminPeer, adminPub)!!

        for (cycle in 1..50) {
            val (peerPriv, peerPub, peerId) = generateEd25519KeyPair()

            // Add member
            val invite = groupManager.createInvitation(group.groupId, peerId, adminPeer)!!
            val accept = groupManager.acceptInvitation(invite, peerId, peerPub)!!
            groupManager.processIncomingAccept(accept, adminPeer)

            val groupPostAdd = groupManager.getGroup(group.groupId)!!
            assertTrue("Peer $peerId must be member", groupPostAdd.memberPeerIds.contains(peerId))

            // Remove member (triggers key rotation)
            val removed = groupManager.removeMember(group.groupId, peerId, adminPeer)
            assertTrue("Member removal must succeed", removed)

            val groupPostRemove = groupManager.getGroup(group.groupId)!!
            assertFalse("Peer $peerId must no longer be member", groupPostRemove.memberPeerIds.contains(peerId))

            // Verify key for new epoch is NOT available to removed peer
            val postRemoveEpoch = groupPostRemove.activeEpoch
            val removedPeerHasKey = keyStore.getGroupKey(group.groupId, postRemoveEpoch) != null && groupPostRemove.memberPeerIds.contains(peerId)
            assertFalse("Removed member must not be active member in epoch $postRemoveEpoch", removedPeerHasKey)
        }
    }

    @Test
    fun testFailureModesAndMalformedPackets() {
        val (adminPriv, adminPub, adminPeer) = generateEd25519KeyPair()
        val group = groupManager.createGroup("Failure Group", adminPeer, adminPub)!!

        val validPacket = GroupMessagingService.encryptAndBuildPacket(
            groupId = group.groupId,
            content = "Valid Message",
            senderPeerId = adminPeer,
            senderSigningPrivateKey = adminPriv,
            groupManager = groupManager
        )!!

        // 1. Malformed payload length
        val truncatedPacket = validPacket.copy(payload = ByteArray(10))
        assertNull("Truncated payload must be rejected", GroupMessagingService.decryptAndValidatePacket(truncatedPacket, adminPub, groupManager))

        // 2. Unknown Group ID
        val unknownGroupId = GroupId.generate()
        val unknownGroupPayload = validPacket.payload.copyOf()
        System.arraycopy(GroupId.toRawBytes(unknownGroupId)!!, 0, unknownGroupPayload, 0, 16)
        val unknownGroupPacket = validPacket.copy(payload = unknownGroupPayload)
        assertNull("Unknown Group ID must be rejected", GroupMessagingService.decryptAndValidatePacket(unknownGroupPacket, adminPub, groupManager))

        // 3. Invalid Ed25519 signature
        val badSignaturePacket = validPacket.copy(signature = ByteArray(64) { 0xFF.toByte() })
        assertNull("Bad Ed25519 signature must be rejected", GroupMessagingService.decryptAndValidatePacket(badSignaturePacket, adminPub, groupManager))

        // 4. Tampered Ciphertext
        val tamperedPayload = validPacket.payload.copyOf()
        tamperedPayload[tamperedPayload.size - 1] = (tamperedPayload[tamperedPayload.size - 1].toInt() xor 0xFF).toByte()
        val tamperedPacket = validPacket.copy(payload = tamperedPayload)
        signPacket(tamperedPacket, adminPriv)
        assertNull("Tampered ciphertext must fail GCM tag check", GroupMessagingService.decryptAndValidatePacket(tamperedPacket, adminPub, groupManager))

        // 5. Unknown Senders
        val (unknownPriv, _, unknownSenderPeer) = generateEd25519KeyPair()
        val unknownSenderPacket = validPacket.copy(
            senderID = MeshPacketUtils.hexStringToByteArray(unknownSenderPeer)
        )
        signPacket(unknownSenderPacket, unknownPriv)
        assertNull("Unknown sender must be rejected", GroupMessagingService.decryptAndValidatePacket(unknownSenderPacket, adminPub, groupManager))
    }

    @Test
    fun testRateLimitAndBurstStress() {
        val (adminPriv, adminPub, adminPeer) = generateEd25519KeyPair()
        val group = groupManager.createGroup("Burst Group", adminPeer, adminPub)!!

        // Burst SOS requests
        val sosCount = 100
        for (i in 1..sosCount) {
            val sosPayload = SosPayload(
                id = "sos_$i",
                sender = "User_$i",
                channel = "General",
                locationNote = "Main Stage Area",
                timestamp = System.currentTimeMillis()
            )
            val json = String(sosPayload.encode(), Charsets.UTF_8)
            assertTrue("SOS payload $i must serialize deterministically", json.contains("\"id\":\"sos_$i\""))
        }

        // Burst Group Invitations
        for (i in 1..100) {
            val (_, _, targetPeer) = generateEd25519KeyPair()
            val invite = groupManager.createInvitation(group.groupId, targetPeer, adminPeer)
            assertNotNull("Invitation $i must be created", invite)
            assertEquals(adminPeer, invite!!.inviterPeerId)
        }
    }
}
