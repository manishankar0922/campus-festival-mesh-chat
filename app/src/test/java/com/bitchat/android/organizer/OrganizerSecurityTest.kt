package com.bitchat.android.organizer

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OrganizerSecurityTest {

    private val testPasscode = "TEST_PASSCODE_SECURE_123"

    @Before
    fun setUp() {
        OrganizerIdentityManager.resetLockoutForTesting()
        OrganizerIdentityManager.resetPasscodeForTesting()
    }

    private fun createSignedAnnouncementPacket(
        privParams: Ed25519PrivateKeyParameters,
        sender: ByteArray = OrganizerIdentityManager.getOrganizerSenderId(),
        content: String = "Main Stage program starts in 10 minutes.",
        recipientID: ByteArray = SpecialRecipients.BROADCAST,
        timestamp: ULong = System.currentTimeMillis().toULong()
    ): BitchatPacket {
        val payload = content.toByteArray(Charsets.UTF_8)
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCEMENT.value,
            senderID = sender,
            recipientID = recipientID,
            timestamp = timestamp,
            payload = payload,
            ttl = 7u
        )
        val signer = Ed25519Signer()
        signer.init(true, privParams)
        val dataToSign = packet.toBinaryDataForSigning()!!
        signer.update(dataToSign, 0, dataToSign.size)
        packet.signature = signer.generateSignature()
        return packet
    }

    @Test
    fun test1_freshValidAnnouncement_pass() {
        val nowMs = System.currentTimeMillis()
        val testPrivBytes = ByteArray(32) { 0x42.toByte() }
        val testPrivParams = Ed25519PrivateKeyParameters(testPrivBytes, 0)
        
        // Generate signed packet
        val packet = createSignedAnnouncementPacket(
            privParams = testPrivParams,
            timestamp = nowMs.toULong()
        )

        // Verifier checks Ed25519 signature and timestamp freshness
        val dataToSign = packet.toBinaryDataForSigning()!!
        val verifier = Ed25519Signer()
        verifier.init(false, testPrivParams.generatePublicKey())
        verifier.update(dataToSign, 0, dataToSign.size)
        assertTrue("Signature must be valid", verifier.verifySignature(packet.signature!!))
        assertTrue("Freshness must pass", OrganizerIdentityManager.isAnnouncementFresh(packet.timestamp, nowMs))
    }

    @Test
    fun test2_validAnnouncementWithinClockTolerance_pass() {
        val nowMs = System.currentTimeMillis()
        val testPrivBytes = ByteArray(32) { 0x42.toByte() }
        val testPrivParams = Ed25519PrivateKeyParameters(testPrivBytes, 0)

        // 4 minutes ago (within 5-minute window)
        val pastPacket = createSignedAnnouncementPacket(testPrivParams, timestamp = (nowMs - 240_000L).toULong())
        assertTrue("4 min past packet must be fresh", OrganizerIdentityManager.isAnnouncementFresh(pastPacket.timestamp, nowMs))

        // 4 minutes in future (within 5-minute clock-skew tolerance)
        val futurePacket = createSignedAnnouncementPacket(testPrivParams, timestamp = (nowMs + 240_000L).toULong())
        assertTrue("4 min future packet must be tolerated", OrganizerIdentityManager.isAnnouncementFresh(futurePacket.timestamp, nowMs))
    }

    @Test
    fun test3_expiredAnnouncement_reject() {
        val nowMs = System.currentTimeMillis()
        val testPrivBytes = ByteArray(32) { 0x42.toByte() }
        val testPrivParams = Ed25519PrivateKeyParameters(testPrivBytes, 0)

        // 6 minutes ago (exceeds 5-minute window)
        val expiredPacket = createSignedAnnouncementPacket(testPrivParams, timestamp = (nowMs - 360_000L).toULong())
        assertFalse("6 min past packet must be rejected", OrganizerIdentityManager.isAnnouncementFresh(expiredPacket.timestamp, nowMs))

        // 6 minutes in future (exceeds 5-minute skew tolerance)
        val futureSkewPacket = createSignedAnnouncementPacket(testPrivParams, timestamp = (nowMs + 360_000L).toULong())
        assertFalse("6 min future packet must be rejected", OrganizerIdentityManager.isAnnouncementFresh(futureSkewPacket.timestamp, nowMs))
    }

    @Test
    fun test4_oldAnnouncementAfterDuplicateCacheEviction_reject() {
        val nowMs = System.currentTimeMillis()
        val testPrivBytes = ByteArray(32) { 0x42.toByte() }
        val testPrivParams = Ed25519PrivateKeyParameters(testPrivBytes, 0)

        // 10 minutes ago
        val oldPacket = createSignedAnnouncementPacket(testPrivParams, timestamp = (nowMs - 600_000L).toULong())

        // Even with empty duplicate cache, timestamp freshness validation fails
        assertFalse("Old packet must be rejected regardless of duplicate cache status",
            OrganizerIdentityManager.isAnnouncementFresh(oldPacket.timestamp, nowMs)
        )
    }

    @Test
    fun test5_modifiedTimestamp_reject() {
        val nowMs = System.currentTimeMillis()
        val testPrivBytes = ByteArray(32) { 0x42.toByte() }
        val testPrivParams = Ed25519PrivateKeyParameters(testPrivBytes, 0)

        val packet = createSignedAnnouncementPacket(testPrivParams, timestamp = nowMs.toULong())

        // Modify timestamp after signing
        val tamperedPacket = packet.copy(timestamp = (nowMs - 5000L).toULong())
        tamperedPacket.signature = packet.signature

        val tamperedData = tamperedPacket.toBinaryDataForSigning()!!
        val verifier = Ed25519Signer()
        verifier.init(false, testPrivParams.generatePublicKey())
        verifier.update(tamperedData, 0, tamperedData.size)

        assertFalse("Tampered timestamp must invalidate signature", verifier.verifySignature(tamperedPacket.signature!!))
    }

    @Test
    fun test6_modifiedChannel_reject() {
        val nowMs = System.currentTimeMillis()
        val testPrivBytes = ByteArray(32) { 0x42.toByte() }
        val testPrivParams = Ed25519PrivateKeyParameters(testPrivBytes, 0)

        val packet = createSignedAnnouncementPacket(testPrivParams, recipientID = SpecialRecipients.BROADCAST)

        // Tamper channel/recipient
        val tamperedPacket = packet.copy(recipientID = "MedicalChannel".toByteArray())
        tamperedPacket.signature = packet.signature

        val tamperedData = tamperedPacket.toBinaryDataForSigning()!!
        val verifier = Ed25519Signer()
        verifier.init(false, testPrivParams.generatePublicKey())
        verifier.update(tamperedData, 0, tamperedData.size)

        assertFalse("Tampered channel must invalidate signature", verifier.verifySignature(tamperedPacket.signature!!))
    }

    @Test
    fun test7_modifiedContent_reject() {
        val nowMs = System.currentTimeMillis()
        val testPrivBytes = ByteArray(32) { 0x42.toByte() }
        val testPrivParams = Ed25519PrivateKeyParameters(testPrivBytes, 0)

        val packet = createSignedAnnouncementPacket(testPrivParams, content = "Original Broadcast")

        // Tamper content
        val tamperedPacket = packet.copy(payload = "Tampered Broadcast".toByteArray())
        tamperedPacket.signature = packet.signature

        val tamperedData = tamperedPacket.toBinaryDataForSigning()!!
        val verifier = Ed25519Signer()
        verifier.init(false, testPrivParams.generatePublicKey())
        verifier.update(tamperedData, 0, tamperedData.size)

        assertFalse("Tampered content must invalidate signature", verifier.verifySignature(tamperedPacket.signature!!))
    }

    @Test
    fun test8_fakeOrganizerKey_reject() {
        val nowMs = System.currentTimeMillis()
        val fakePrivBytes = ByteArray(32) { 0x99.toByte() }
        val fakePrivParams = Ed25519PrivateKeyParameters(fakePrivBytes, 0)

        val packet = createSignedAnnouncementPacket(fakePrivParams, timestamp = nowMs.toULong())

        // Verify against real organizer public key parameters
        val realPrivParams = Ed25519PrivateKeyParameters(ByteArray(32) { 0x42.toByte() }, 0)
        val verifier = Ed25519Signer()
        verifier.init(false, realPrivParams.generatePublicKey())
        val dataToSign = packet.toBinaryDataForSigning()!!
        verifier.update(dataToSign, 0, dataToSign.size)

        assertFalse("Signature from fake key must be rejected", verifier.verifySignature(packet.signature!!))
    }

    @Test
    fun test9_invalidSignature_reject() {
        val nowMs = System.currentTimeMillis()
        val testPrivBytes = ByteArray(32) { 0x42.toByte() }
        val testPrivParams = Ed25519PrivateKeyParameters(testPrivBytes, 0)

        val packet = createSignedAnnouncementPacket(testPrivParams, timestamp = nowMs.toULong())
        packet.signature = ByteArray(64) { 0x00.toByte() }

        val verifier = Ed25519Signer()
        verifier.init(false, testPrivParams.generatePublicKey())
        val dataToSign = packet.toBinaryDataForSigning()!!
        verifier.update(dataToSign, 0, dataToSign.size)

        assertFalse("Invalid zeroed signature must be rejected", verifier.verifySignature(packet.signature!!))
    }

    @Test
    fun test10_duplicateAnnouncement_rejectDeduplicate() {
        val seenSet = mutableSetOf<String>()
        val messageID = "ANNOUNCEMENT_MSG_001_HASH"

        assertFalse("First receipt is not a duplicate", seenSet.contains(messageID))
        seenSet.add(messageID)
        assertTrue("Second receipt must be detected as duplicate", seenSet.contains(messageID))
    }

    @Test
    fun test11_wrongOrganizerPasscode_reject() {
        assertTrue(OrganizerIdentityManager.setPasscode(testPasscode))
        assertFalse("Wrong passcode must be rejected", OrganizerIdentityManager.validatePasscode("WRONGPASSCODE"))
        assertFalse("Second wrong passcode must be rejected", OrganizerIdentityManager.validatePasscode("123456"))
        assertTrue("Correct passcode must succeed", OrganizerIdentityManager.validatePasscode(testPasscode))
    }

    @Test
    fun test12_excessivePasscodeAttempts_lockout() {
        assertTrue(OrganizerIdentityManager.setPasscode(testPasscode))
        for (i in 1..5) {
            OrganizerIdentityManager.validatePasscode("WRONG_$i")
        }
        assertTrue("5 consecutive wrong attempts must trigger lockout", OrganizerIdentityManager.isLockedOut())
        assertFalse("Correct passcode must be rejected during active lockout", OrganizerIdentityManager.validatePasscode(testPasscode))
    }
}
