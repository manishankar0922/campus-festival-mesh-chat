package com.bitchat.android.group

import android.content.SharedPreferences
import com.bitchat.android.mesh.MeshPacketUtils
import com.bitchat.android.model.GroupId
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.util.hexEncodedString
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

class GroupMessagingSecurityTest {

    private lateinit var keyStore: SecureGroupKeyStore
    private lateinit var groupManager: GroupManager

    private val nowMs = System.currentTimeMillis()

    // Keypairs for Peer A, Peer B, Peer C
    private lateinit var keyPairA: AsymmetricCipherKeyPair
    private lateinit var keyPairB: AsymmetricCipherKeyPair
    private lateinit var keyPairC: AsymmetricCipherKeyPair

    private lateinit var privA: ByteArray
    private lateinit var pubA: ByteArray
    private lateinit var peerA: String

    private lateinit var privB: ByteArray
    private lateinit var pubB: ByteArray
    private lateinit var peerB: String

    private lateinit var privC: ByteArray
    private lateinit var pubC: ByteArray
    private lateinit var peerC: String

    @Before
    fun setUp() {
        keyStore = SecureGroupKeyStore.createTestInstance(MapSharedPreferences())
        groupManager = GroupManager.createTestInstance(keyStore)
        groupManager.clearAllForTesting()

        keyPairA = generateEd25519KeyPair()
        privA = (keyPairA.private as Ed25519PrivateKeyParameters).encoded
        pubA = (keyPairA.public as Ed25519PublicKeyParameters).encoded
        peerA = pubA.sliceArray(0 until 8).hexEncodedString()

        keyPairB = generateEd25519KeyPair()
        privB = (keyPairB.private as Ed25519PrivateKeyParameters).encoded
        pubB = (keyPairB.public as Ed25519PublicKeyParameters).encoded
        peerB = pubB.sliceArray(0 until 8).hexEncodedString()

        keyPairC = generateEd25519KeyPair()
        privC = (keyPairC.private as Ed25519PrivateKeyParameters).encoded
        pubC = (keyPairC.public as Ed25519PublicKeyParameters).encoded
        peerC = pubC.sliceArray(0 until 8).hexEncodedString()
    }

    private fun generateEd25519KeyPair(): AsymmetricCipherKeyPair {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        return gen.generateKeyPair()
    }

    // --- 1. Encryption Tests ---

    @Test
    fun test1_validEncryptionAndDecryptionRoundTrip() {
        val group = groupManager.createGroup("Crypto Test Group", peerA, pubA)!!
        val packet = GroupMessagingService.encryptAndBuildPacket(
            groupId = group.groupId,
            content = "Hello Festival Friends!",
            senderPeerId = peerA,
            senderSigningPrivateKey = privA,
            groupManager = groupManager,
            nowMs = nowMs
        )
        assertNotNull("Packet encryption must succeed", packet)
        assertEquals(MessageType.GROUP_MESSAGE.value, packet?.type)

        val decrypted = GroupMessagingService.decryptAndValidatePacket(
            packet = packet!!,
            signingPublicKey = pubA,
            groupManager = groupManager,
            nowMs = nowMs
        )
        assertNotNull("Packet decryption must succeed", decrypted)
        assertEquals("Hello Festival Friends!", decrypted?.content)
        assertEquals(peerA, decrypted?.senderPeerId)
        assertEquals(group.groupId, decrypted?.groupId)
    }

    @Test
    fun test2_freshIvPerMessage() {
        val group = groupManager.createGroup("IV Test Group", peerA, pubA)!!
        val p1 = GroupMessagingService.encryptAndBuildPacket(group.groupId, "Msg 1", peerA, privA, groupManager, nowMs)!!
        val p2 = GroupMessagingService.encryptAndBuildPacket(group.groupId, "Msg 2", peerA, privA, groupManager, nowMs)!!

        val payload1 = GroupMessagePayload.decode(p1.payload)!!
        val payload2 = GroupMessagePayload.decode(p2.payload)!!

        assertFalse("IVs must be cryptographically unique per message", payload1.iv.contentEquals(payload2.iv))
    }

    @Test
    fun test3_wrongKeyRejection() {
        val group = groupManager.createGroup("Key Test Group", peerA, pubA)!!
        val packet = GroupMessagingService.encryptAndBuildPacket(group.groupId, "Secret", peerA, privA, groupManager, nowMs)!!

        // Overwrite key for epoch 1 with wrong key
        keyStore.saveGroupKey(group.groupId, 1, javax.crypto.spec.SecretKeySpec(ByteArray(32) { 0xFF.toByte() }, "AES"))

        val decrypted = GroupMessagingService.decryptAndValidatePacket(packet, pubA, groupManager, nowMs)
        assertNull("Decryption with wrong AES key must fail and return null", decrypted)
    }

    @Test
    fun test4_modifiedCiphertextRejection() {
        val group = groupManager.createGroup("Tamper Test Group", peerA, pubA)!!
        val packet = GroupMessagingService.encryptAndBuildPacket(group.groupId, "Tamper Test", peerA, privA, groupManager, nowMs)!!

        val payload = GroupMessagePayload.decode(packet.payload)!!
        // Flip one byte in ciphertext
        val tamperedCiphertext = payload.ciphertext.clone()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0xFF).toByte()

        val tamperedPayload = payload.copy(ciphertext = tamperedCiphertext).encode()
        val tamperedPacket = packet.copy(payload = tamperedPayload)

        val decrypted = GroupMessagingService.decryptAndValidatePacket(tamperedPacket, pubA, groupManager, nowMs)
        assertNull("Modified ciphertext must trigger GCM authentication failure", decrypted)
    }

    @Test
    fun test5_modifiedIvRejection() {
        val group = groupManager.createGroup("IV Tamper Group", peerA, pubA)!!
        val packet = GroupMessagingService.encryptAndBuildPacket(group.groupId, "IV Test", peerA, privA, groupManager, nowMs)!!

        val payload = GroupMessagePayload.decode(packet.payload)!!
        val tamperedIv = payload.iv.clone()
        tamperedIv[0] = (tamperedIv[0].toInt() xor 0xFF).toByte()

        val tamperedPayload = payload.copy(iv = tamperedIv).encode()
        val tamperedPacket = packet.copy(payload = tamperedPayload)

        val decrypted = GroupMessagingService.decryptAndValidatePacket(tamperedPacket, pubA, groupManager, nowMs)
        assertNull("Modified IV must fail AES-GCM decryption", decrypted)
    }

    // --- 2. Authentication Tests ---

    @Test
    fun test6_nonMemberSenderRejected() {
        val group = groupManager.createGroup("Member Check Group", peerA, pubA)!!

        // Peer B is NOT a member of group
        val packet = GroupMessagingService.encryptAndBuildPacket(group.groupId, "Unauth", peerB, privB, groupManager, nowMs)
        assertNull("Non-member cannot encrypt packet for group", packet)
    }

    @Test
    fun test7_invalidEd25519SignatureRejected() {
        val group = groupManager.createGroup("Sig Test Group", peerA, pubA)!!
        val packet = GroupMessagingService.encryptAndBuildPacket(group.groupId, "Valid Sig", peerA, privA, groupManager, nowMs)!!

        // Corrupt signature bytes
        val tamperedSig = packet.signature!!.clone()
        tamperedSig[0] = (tamperedSig[0].toInt() xor 0xFF).toByte()
        val tamperedPacket = packet.copy(signature = tamperedSig)

        val decrypted = GroupMessagingService.decryptAndValidatePacket(tamperedPacket, pubA, groupManager, nowMs)
        assertNull("Invalid Ed25519 signature must be rejected", decrypted)
    }

    // --- 3. Epoch & Grace Period Tests ---

    @Test
    fun test8_previousEpochAcceptedWithinGracePeriod() {
        val group = groupManager.createGroup("Grace Test Group", peerA, pubA)!!
        val rotationTime = nowMs + 1000L

        // Encrypt packet at Epoch 1
        val packetE1 = GroupMessagingService.encryptAndBuildPacket(group.groupId, "Pre-rotation Msg", peerA, privA, groupManager, nowMs = nowMs)!!

        // Admin rotates key to Epoch 2 at rotationTime
        groupManager.rotateGroupKey(group.groupId, reason = "MEMBER_JOIN", nowMs = rotationTime)

        // Decrypt packetE1 1 minute after rotation (within 2-minute grace)
        val decrypted = GroupMessagingService.decryptAndValidatePacket(packetE1, pubA, groupManager, nowMs = rotationTime + 60_000L)
        assertNotNull("Epoch 1 message created BEFORE rotation is accepted within 2-minute grace period", decrypted)
        assertEquals("Pre-rotation Msg", decrypted?.content)
    }

    @Test
    fun test9_previousEpochRejectedAfterGracePeriod() {
        val group = groupManager.createGroup("Grace Expiry Group", peerA, pubA)!!
        val rotationTime = nowMs + 1000L

        val packetE1 = GroupMessagingService.encryptAndBuildPacket(group.groupId, "Old Msg", peerA, privA, groupManager, nowMs = nowMs)!!
        groupManager.rotateGroupKey(group.groupId, reason = "MEMBER_JOIN", nowMs = rotationTime)

        // Attempt decryption 3 minutes after rotation (> 2-minute grace)
        val decrypted = GroupMessagingService.decryptAndValidatePacket(packetE1, pubA, groupManager, nowMs = rotationTime + 180_000L)
        assertNull("Epoch 1 message MUST be rejected after 2-minute grace window expires", decrypted)
    }

    @Test
    fun test10_postRotationMessageCannotUseOldKey() {
        val group = groupManager.createGroup("Post Rotation Group", peerA, pubA)!!
        val rotationTime = nowMs + 1000L

        // Save Epoch 1 key
        val k1 = keyStore.getGroupKey(group.groupId, 1)!!

        // Rotate to Epoch 2
        groupManager.rotateGroupKey(group.groupId, reason = "MEMBER_JOIN", nowMs = rotationTime)

        // Attempt to encrypt a message AFTER rotation using old K1 key
        val postRotationTime = rotationTime + 5000L
        val groupIdBytes = GroupId.toRawBytes(group.groupId)!!
        val senderIdBytes = MeshPacketUtils.hexStringToByteArray(peerA)
        val iv = ByteArray(12) { 1 }
        val aad = GroupMessagePayload.buildAAD(groupIdBytes, 1, senderIdBytes, postRotationTime)

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, k1, javax.crypto.spec.GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal("Post Rotation Msg".toByteArray())

        val payload = GroupMessagePayload(groupIdBytes, epoch = 1, iv, ciphertext).encode()
        val packet = BitchatPacket(1u, MessageType.GROUP_MESSAGE.value, senderIdBytes, SpecialRecipients.BROADCAST, postRotationTime.toULong(), payload, null, 7u)

        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privA, 0))
        val dataToSign = packet.toBinaryDataForSigning()!!
        signer.update(dataToSign, 0, dataToSign.size)
        packet.signature = signer.generateSignature()

        val decrypted = GroupMessagingService.decryptAndValidatePacket(packet, pubA, groupManager, nowMs = postRotationTime + 1000L)
        assertNull("Post-rotation packet using old key MUST be rejected", decrypted)
    }

    // --- 4. Control Packet Security Tests ---

    @Test
    fun test11_nonAdminRemoveRejected() {
        val group = groupManager.createGroup("Remove Control Group", peerA, pubA)!!

        // Non-admin peerB attempts to remove peerA
        val removeSuccess = groupManager.removeMember(group.groupId, targetPeerId = peerA, adminPeerId = peerB, nowMs = nowMs)
        assertFalse("Non-admin REMOVE control operation MUST be rejected", removeSuccess)
    }

    @Test
    fun test12_unauthorizedKeyDistributionRejected() {
        val group = groupManager.createGroup("Key Dist Group", peerA, pubA)!!
        val keyDist = GroupControlPayload.KeyDistribution(group.groupId, 2, "ZW5jcnlwdGVkX2tleQ==", nowMs)

        // peerB is NOT a member of group
        val accepted = groupManager.processKeyDistribution(keyDist, receiverPeerId = peerB)
        assertFalse("Key distribution to non-member MUST be rejected", accepted)
    }

    // --- 5. MANDATORY CRITICAL SECURITY SEQUENCE (All Assertions Verified) ---

    @Test
    fun test13_MANDATORY_CRITICAL_GROUP_MESSAGING_SEQUENCE() {
        // --- STEP 1: Epoch 1 (Members A, B) ---
        val g1 = groupManager.createGroup("Full Flow Group", peerA, pubA)!!
        val groupId = g1.groupId

        val groupWithB = g1.copy(
            memberPeerIds = setOf(peerA, peerB),
            memberSigningKeys = mapOf(peerA to pubA, peerB to pubB)
        )
        groupManager.loadGroupForTesting(groupWithB)

        // Member A sends Msg 1 in Epoch 1
        val packetE1 = GroupMessagingService.encryptAndBuildPacket(groupId, "Epoch 1 Message", peerA, privA, groupManager, nowMs)!!
        val decB_E1 = GroupMessagingService.decryptAndValidatePacket(packetE1, pubA, groupManager, nowMs)
        assertNotNull("B can decrypt Epoch 1 message", decB_E1)

        // --- STEP 2: Add C -> C joins and receives K2 only (Epoch 2: A, B, C) ---
        val inviteC = groupManager.createInvitation(groupId, targetPeerId = peerC, inviterPeerId = peerA, nowMs = nowMs)!!
        val acceptC = groupManager.acceptInvitation(inviteC, acceptorPeerId = peerC, acceptorSigningPubKey = pubC, nowMs = nowMs + 1000L)!!
        val (groupEpoch2, keyDistEpoch2) = groupManager.processIncomingAccept(acceptC, adminPeerId = peerA, nowMs = nowMs + 2000L)!!

        assertEquals(2, groupEpoch2.activeEpoch)
        assertTrue(groupEpoch2.memberPeerIds.contains(peerC))

        // C sets up local manager with K2 key distribution
        val keyStoreC = SecureGroupKeyStore.createTestInstance(MapSharedPreferences())
        val groupManagerC = GroupManager.createTestInstance(keyStoreC)
        groupManagerC.loadGroupForTesting(groupEpoch2)
        assertTrue(groupManagerC.processKeyDistribution(keyDistEpoch2, receiverPeerId = peerC))

        // ASSERTION: C CANNOT decrypt Epoch 1 message
        val decC_E1 = GroupMessagingService.decryptAndValidatePacket(packetE1, pubA, groupManagerC, nowMs = nowMs + 3000L)
        assertNull("ASSERTION PASSED: C CANNOT decrypt Epoch 1 message (C received K2 only)", decC_E1)

        // --- STEP 3: Remove B -> Generate K3 and distribute to A and C only (Epoch 3: A, C) ---
        assertTrue(groupManager.removeMember(groupId, targetPeerId = peerB, adminPeerId = peerA, nowMs = nowMs + 5000L))
        val groupEpoch3 = groupManager.getGroup(groupId)!!
        assertEquals(3, groupEpoch3.activeEpoch)

        val k3 = keyStore.getGroupKey(groupId, 3)!!
        val k3Dist = GroupControlPayload.KeyDistribution(groupId, 3, java.util.Base64.getEncoder().encodeToString(k3.encoded), nowMs + 5000L)

        // Update C with K3
        groupManagerC.loadGroupForTesting(groupEpoch3)
        assertTrue(groupManagerC.processKeyDistribution(k3Dist, receiverPeerId = peerC))

        // A sends Epoch 3 message using K3
        val packetE3 = GroupMessagingService.encryptAndBuildPacket(groupId, "Epoch 3 Message", peerA, privA, groupManager, nowMs = nowMs + 6000L)!!

        // ASSERTION: A can send with K3 and C can decrypt
        val decC_E3 = GroupMessagingService.decryptAndValidatePacket(packetE3, pubA, groupManagerC, nowMs = nowMs + 6000L)
        assertNotNull("ASSERTION PASSED: C CAN decrypt Epoch 3 message", decC_E3)
        assertEquals("Epoch 3 Message", decC_E3?.content)

        // ASSERTION: B CANNOT decrypt Epoch 3 message
        val keyStoreB = SecureGroupKeyStore.createTestInstance(MapSharedPreferences())
        val groupManagerB = GroupManager.createTestInstance(keyStoreB)
        groupManagerB.loadGroupForTesting(groupEpoch3) // B is not in groupEpoch3

        val decB_E3 = GroupMessagingService.decryptAndValidatePacket(packetE3, pubA, groupManagerB, nowMs = nowMs + 6000L)
        assertNull("ASSERTION PASSED: B CANNOT decrypt Epoch 3 message (B did not receive K3)", decB_E3)

        // ASSERTION: Membership state remains consistent
        assertEquals(setOf(peerA, peerC), groupManager.getGroup(groupId)!!.memberPeerIds)
    }
}
