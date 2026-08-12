package com.bitchat.android.sos

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.util.AppConstants
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SosMessagingTest {

    private val nowMs = System.currentTimeMillis()
    private val testPrivBytes = ByteArray(32) { 0x55.toByte() }
    private val testPrivParams = Ed25519PrivateKeyParameters(testPrivBytes, 0)
    private val testPubKey = testPrivParams.generatePublicKey()
    private val senderPeerIdBytes = ByteArray(8) { 0xAA.toByte() }
    private val senderPeerIdHex = "aaaaaaaaaaaaaaaa"

    @Before
    fun setUp() {
        ActiveSosManager.clearAllForTesting()
    }

    private fun createSignedSosPacket(
        privParams: Ed25519PrivateKeyParameters = testPrivParams,
        type: MessageType = MessageType.SOS,
        senderID: ByteArray = senderPeerIdBytes,
        content: String = "Location: Near Main Stage",
        channel: String = "Main Stage",
        locationNote: String = "Near Main Stage",
        isCancel: Boolean = false,
        originalSosId: String? = null,
        timestamp: ULong = nowMs.toULong(),
        ttl: UByte = 7u
    ): BitchatPacket {
        val payloadObj = SosPayload(
            id = if (isCancel) "CANCEL-123" else "SOS-123",
            sender = "Alice",
            channel = channel,
            locationNote = locationNote,
            isCancel = isCancel,
            originalSosId = originalSosId,
            timestamp = timestamp.toLong()
        )
        val payload = payloadObj.encode()

        val packet = BitchatPacket(
            type = type.value,
            senderID = senderID,
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = timestamp,
            payload = payload,
            ttl = ttl
        )

        val signer = Ed25519Signer()
        signer.init(true, privParams)
        val dataToSign = packet.toBinaryDataForSigning()!!
        signer.update(dataToSign, 0, dataToSign.size)
        packet.signature = signer.generateSignature()
        return packet
    }

    private fun verifySignature(packet: BitchatPacket, pubKey: ByteArray = testPubKey.encoded): Boolean {
        return try {
            val dataToSign = packet.toBinaryDataForSigning() ?: return false
            val sig = packet.signature ?: return false
            val verifier = Ed25519Signer()
            val pubParams = org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(pubKey, 0)
            verifier.init(false, pubParams)
            verifier.update(dataToSign, 0, dataToSign.size)
            verifier.verifySignature(sig)
        } catch (_: Exception) {
            false
        }
    }

    private fun isTimestampFresh(packetTimestamp: ULong, referenceNowMs: Long = nowMs): Boolean {
        val now = referenceNowMs.coerceAtLeast(0).toULong()
        val clockSkew = if (packetTimestamp >= now) packetTimestamp - now else now - packetTimestamp
        return clockSkew <= AppConstants.Security.MESSAGE_TIMEOUT_MS.toULong()
    }

    // --- Authentication ---

    @Test
    fun test1_validSenderSignature_accept() {
        val packet = createSignedSosPacket()
        assertTrue("Signature must be valid for registered sender key", verifySignature(packet))
    }

    @Test
    fun test2_unannouncedSender_reject() {
        val fakeKey = ByteArray(32) { 0x99.toByte() }
        val packet = createSignedSosPacket()
        assertFalse("Verification must fail against unannounced/wrong public key", verifySignature(packet, fakeKey))
    }

    @Test
    fun test3_modifiedSenderID_reject() {
        val packet = createSignedSosPacket()
        val tampered = packet.copy(senderID = ByteArray(8) { 0xBB.toByte() })
        assertFalse("Modified sender ID must invalidate signature", verifySignature(tampered))
    }

    @Test
    fun test4_modifiedContent_reject() {
        val packet = createSignedSosPacket()
        val tampered = packet.copy(payload = SosPayload("SOS-123", "Alice", "Main Stage", "Tampered note", false, null, nowMs).encode())
        assertFalse("Modified payload content must invalidate signature", verifySignature(tampered))
    }

    @Test
    fun test5_modifiedChannel_reject() {
        val packet = createSignedSosPacket()
        val tampered = packet.copy(payload = SosPayload("SOS-123", "Alice", "Tampered Channel", "Near Main Stage", false, null, nowMs).encode())
        assertFalse("Modified channel must invalidate signature", verifySignature(tampered))
    }

    @Test
    fun test6_modifiedLocationNote_reject() {
        val packet = createSignedSosPacket()
        val tampered = packet.copy(payload = SosPayload("SOS-123", "Alice", "Main Stage", "Forged location", false, null, nowMs).encode())
        assertFalse("Modified location note must invalidate signature", verifySignature(tampered))
    }

    // --- Replay & Freshness ---

    @Test
    fun test7_duplicateSos_deduplicate() {
        val entry = ActiveSosEntry("SOS-123", senderPeerIdHex, "Alice", "Main Stage", "Near Gate 2", nowMs)
        assertTrue("First incoming SOS must accept", ActiveSosManager.processIncomingSos(entry, nowMs))
        assertEquals("Single active entry must exist", 1, ActiveSosManager.getAllActiveSos(nowMs).size)
    }

    @Test
    fun test8_expiredSosTimestamp_reject() {
        val oldTimestamp = (nowMs - 6 * 60 * 1000L).toULong() // 6 minutes ago (> 5 min freshness)
        val packet = createSignedSosPacket(timestamp = oldTimestamp)
        assertFalse("SOS older than 5 minutes must be rejected as stale", isTimestampFresh(packet.timestamp, nowMs))
    }

    @Test
    fun test9_expiredSosAfterCacheEviction_reject() {
        val tenMinutesAgo = (nowMs - 10 * 60 * 1000L).toULong()
        val packet = createSignedSosPacket(timestamp = tenMinutesAgo)
        assertFalse("Replayed 10-min old packet must be rejected by freshness even if missing from cache", isTimestampFresh(packet.timestamp, nowMs))
    }

    @Test
    fun test10_multiHopLegitimateSos_accept() {
        val MultiHopDelayMs = 25000L // 25s multi-hop delay
        val packet = createSignedSosPacket(timestamp = (nowMs - MultiHopDelayMs).toULong())
        assertTrue("Multi-hop packet within 5-min freshness window must be accepted", isTimestampFresh(packet.timestamp, nowMs))
    }

    // --- Cancellation ---

    @Test
    fun test11_validSenderCancellation_accept() {
        val entry = ActiveSosEntry("SOS-123", senderPeerIdHex, "Alice", "Main Stage", "Near Gate 2", nowMs)
        ActiveSosManager.processIncomingSos(entry, nowMs)
        val cancelled = ActiveSosManager.processIncomingCancel(senderPeerIdHex, "SOS-123")
        assertNotNull("Matching sender cancellation must succeed", cancelled)
        assertNull("Active SOS must be removed", ActiveSosManager.getActiveSos(senderPeerIdHex, nowMs))
    }

    @Test
    fun test12_wrongSenderCancellation_reject() {
        val entry = ActiveSosEntry("SOS-123", senderPeerIdHex, "Alice", "Main Stage", "Near Gate 2", nowMs)
        ActiveSosManager.processIncomingSos(entry, nowMs)
        val wrongSenderHex = "bbbbbbbbbbbbbbbb"
        val cancelled = ActiveSosManager.processIncomingCancel(wrongSenderHex, "SOS-123")
        assertNull("Cancellation from non-owner must be rejected", cancelled)
        assertNotNull("Active SOS must remain intact", ActiveSosManager.getActiveSos(senderPeerIdHex, nowMs))
    }

    @Test
    fun test13_unknownSosCancellation_reject() {
        val cancelled = ActiveSosManager.processIncomingCancel(senderPeerIdHex, "SOS-NONEXISTENT")
        assertNull("Cancelling unknown SOS must return null", cancelled)
    }

    @Test
    fun test14_modifiedOriginalSosId_reject() {
        val entry = ActiveSosEntry("SOS-123", senderPeerIdHex, "Alice", "Main Stage", "Near Gate 2", nowMs)
        ActiveSosManager.processIncomingSos(entry, nowMs)
        val cancelled = ActiveSosManager.processIncomingCancel(senderPeerIdHex, "SOS-WRONG-ID")
        assertNull("Cancellation with mismatched original SOS ID must be rejected", cancelled)
    }

    @Test
    fun test15_expiredCancellation_reject() {
        val oldCancelTimestamp = (nowMs - 6 * 60 * 1000L).toULong()
        val packet = createSignedSosPacket(type = MessageType.SOS_CANCEL, isCancel = true, originalSosId = "SOS-123", timestamp = oldCancelTimestamp)
        assertFalse("Stale cancellation packet must fail freshness check", isTimestampFresh(packet.timestamp, nowMs))
    }

    // --- Rate Limiting & Payload ---

    @Test
    fun test16_outgoingRateLimit_blockSecondSosWithin60s() {
        assertTrue("First outgoing SOS allowed", ActiveSosManager.canSendOutgoingSos(nowMs))
        ActiveSosManager.recordOutgoingSos(nowMs)
        assertFalse("Second outgoing SOS within 60s blocked", ActiveSosManager.canSendOutgoingSos(nowMs + 30000L))
        assertTrue("Outgoing SOS allowed after 60s", ActiveSosManager.canSendOutgoingSos(nowMs + 61000L))
    }

    @Test
    fun test17_incomingRateLimit_updateActiveSosInPlace() {
        val entry1 = ActiveSosEntry("SOS-1", senderPeerIdHex, "Alice", "Main Stage", "Gate 1", nowMs)
        val entry2 = ActiveSosEntry("SOS-2", senderPeerIdHex, "Alice", "Main Stage", "Gate 2", nowMs + 10000L)
        ActiveSosManager.processIncomingSos(entry1, nowMs)
        ActiveSosManager.processIncomingSos(entry2, nowMs + 10000L)

        val active = ActiveSosManager.getActiveSos(senderPeerIdHex, nowMs + 10000L)
        assertNotNull(active)
        assertEquals("Active SOS location must update in-place", "Gate 2", active?.locationNote)
        assertEquals("Total active SOS count remains 1 per sender", 1, ActiveSosManager.getAllActiveSos(nowMs + 10000L).size)
    }

    @Test
    fun test18_oversizedLocationNote_truncate() {
        val longNote = "A".repeat(100)
        val sanitized = SosPayload.sanitizeLocationNote(longNote)
        assertEquals("Location note must be truncated to 60 characters", 60, sanitized.length)
    }

    @Test
    fun test19_controlCharactersInLocationNote_sanitize() {
        val noteWithControlChars = "Main Stage\nGate 2\r\t\u0000Entrance"
        val sanitized = SosPayload.sanitizeLocationNote(noteWithControlChars)
        assertFalse("Control characters and newlines must be stripped", sanitized.contains("\n") || sanitized.contains("\r") || sanitized.contains("\u0000"))
        assertEquals("Cleaned text should format smoothly", "Main Stage Gate 2 Entrance", sanitized)
    }

    // --- Relay & TTL ---

    @Test
    fun test20_ttlDecrement_correct() {
        val packet = createSignedSosPacket(ttl = 7u)
        val decrementedTtl = (packet.ttl - 1u).toUByte()
        assertEquals("TTL decrements from 7 to 6", 6u.toUByte(), decrementedTtl)
    }

    @Test
    fun test21_zeroTtl_notForwarded() {
        val packet = createSignedSosPacket(ttl = 0u)
        assertEquals("Packet with TTL 0 cannot be relayed further", 0u.toUByte(), packet.ttl)
    }

    @Test
    fun test22_duplicatePacket_notRelayedRepeatedly() {
        val entry = ActiveSosEntry("SOS-100", senderPeerIdHex, "Alice", "Main Stage", "Near Gate 1", nowMs)
        assertTrue("Initial packet processed", ActiveSosManager.processIncomingSos(entry, nowMs))
        val listAfterFirst = ActiveSosManager.getAllActiveSos(nowMs)
        assertEquals(1, listAfterFirst.size)
    }

    // --- Notifications & Lifetime ---

    @Test
    fun test23_firstValidSos_addsToActiveSos() {
        val entry = ActiveSosEntry("SOS-101", senderPeerIdHex, "Alice", "Food Court", "Stall 5", nowMs)
        ActiveSosManager.processIncomingSos(entry, nowMs)
        assertEquals(1, ActiveSosManager.getAllActiveSos(nowMs).size)
    }

    @Test
    fun test24_relayDuplicate_doesNotAddDuplicateActive() {
        val entry = ActiveSosEntry("SOS-101", senderPeerIdHex, "Alice", "Food Court", "Stall 5", nowMs)
        ActiveSosManager.processIncomingSos(entry, nowMs)
        ActiveSosManager.processIncomingSos(entry, nowMs + 1000L)
        assertEquals(1, ActiveSosManager.getAllActiveSos(nowMs).size)
    }

    @Test
    fun test25_cancellation_removesActiveSos() {
        val entry = ActiveSosEntry("SOS-101", senderPeerIdHex, "Alice", "Food Court", "Stall 5", nowMs)
        ActiveSosManager.processIncomingSos(entry, nowMs)
        assertNotNull(ActiveSosManager.processIncomingCancel(senderPeerIdHex, "SOS-101"))
        assertEquals(0, ActiveSosManager.getAllActiveSos(nowMs).size)
    }

    @Test
    fun test26_expiration_removesActiveSosAfter15Mins() {
        val entry = ActiveSosEntry("SOS-101", senderPeerIdHex, "Alice", "Food Court", "Stall 5", nowMs)
        ActiveSosManager.processIncomingSos(entry, nowMs)
        assertNotNull("Active at 14 minutes", ActiveSosManager.getActiveSos(senderPeerIdHex, nowMs + 14 * 60 * 1000L))
        assertNull("Expired at 16 minutes", ActiveSosManager.getActiveSos(senderPeerIdHex, nowMs + 16 * 60 * 1000L))
    }

    // --- Phase 6.1 Security Verification Additions ---

    @Test
    fun test27_canonicalSerializationDeterministic() {
        val payload = SosPayload("SOS-777", "Bob", "Main Stage", "Near entrance gate <A&B>", false, null, nowMs)
        val firstEncode = payload.encode()
        for (i in 1..100) {
            val nextEncode = payload.encode()
            assertArrayEquals("Serialization must be 100% byte-identical across multiple calls", firstEncode, nextEncode)
        }
        val decoded = SosPayload.decode(firstEncode)
        assertNotNull(decoded)
        assertEquals("Decoded fields match original", payload.id, decoded?.id)
        assertEquals("Decoded fields match original", payload.sender, decoded?.sender)
        assertEquals("Decoded fields match original", payload.channel, decoded?.channel)
    }

    @Test
    fun test28_locationNoteValidationMatrix() {
        val validNote = "Gate 4"
        assertEquals("Gate 4", SosPayload.sanitizeLocationNote(validNote))

        val exact60 = "A".repeat(60)
        assertEquals(60, SosPayload.sanitizeLocationNote(exact60).length)

        val oversized = "B".repeat(120)
        assertEquals(60, SosPayload.sanitizeLocationNote(oversized).length)

        val multibyteUtf8 = "🎪 Stage 1 Gate 🎪 Emergency!"
        val sanitizedMultibyte = SosPayload.sanitizeLocationNote(multibyteUtf8)
        assertTrue("Multibyte UTF-8 preserved safely", sanitizedMultibyte.contains("Stage 1"))

        val controls = "Line1\nLine2\rLine3\tTab\u0000Null"
        val sanitizedControls = SosPayload.sanitizeLocationNote(controls)
        assertFalse("Controls stripped", sanitizedControls.contains("\n") || sanitizedControls.contains("\r") || sanitizedControls.contains("\t") || sanitizedControls.contains("\u0000"))

        assertEquals("No location details provided", SosPayload.sanitizeLocationNote(""))
        assertEquals("No location details provided", SosPayload.sanitizeLocationNote("   \n\t "))
    }

    @Test
    fun test29_sosUpdateImmutability() {
        val entryA = ActiveSosEntry("SOS-A", senderPeerIdHex, "Alice", "Main Stage", "Gate 1", nowMs)
        ActiveSosManager.processIncomingSos(entryA, nowMs)

        val entryB = ActiveSosEntry("SOS-B", senderPeerIdHex, "Alice", "Main Stage", "Gate 2", nowMs + 5000L)
        ActiveSosManager.processIncomingSos(entryB, nowMs + 5000L)

        val active = ActiveSosManager.getActiveSos(senderPeerIdHex, nowMs + 5000L)
        assertNotNull(active)
        assertEquals("Active entry ID updated to SOS-B", "SOS-B", active?.sosId)
        assertEquals("Location updated to Gate 2", "Gate 2", active?.locationNote)
        assertEquals("Original entryA object properties remain unchanged", "Gate 1", entryA.locationNote)
    }

    @Test
    fun test30_cancellationResurrectionDefense() {
        val entry = ActiveSosEntry("SOS-EXPIRED", senderPeerIdHex, "Alice", "Main Stage", "Gate 1", nowMs)
        ActiveSosManager.processIncomingSos(entry, nowMs)

        // Fast forward 16 mins (expired)
        val now16m = nowMs + 16 * 60 * 1000L
        assertNull("SOS expired after 16 mins", ActiveSosManager.getActiveSos(senderPeerIdHex, now16m))

        val cancelResult = ActiveSosManager.processIncomingCancel(senderPeerIdHex, "SOS-EXPIRED")
        assertNull("Cancellation of expired SOS returns null", cancelResult)
        assertNull("Cancellation cannot resurrect expired SOS", ActiveSosManager.getActiveSos(senderPeerIdHex, now16m))
    }

    @Test
    fun test31_multipleSendersActiveAlertsLimit() {
        val sender1 = "1111111111111111"
        val sender2 = "2222222222222222"
        ActiveSosManager.processIncomingSos(ActiveSosEntry("SOS-1", sender1, "Alice", "Main Stage", "Gate 1", nowMs), nowMs)
        ActiveSosManager.processIncomingSos(ActiveSosEntry("SOS-2", sender2, "Bob", "Food Court", "Stall 2", nowMs), nowMs)

        assertEquals("Two unique senders produce two active alerts", 2, ActiveSosManager.getAllActiveSos(nowMs).size)

        // Third SOS from sender1 updates sender1 alert in-place
        ActiveSosManager.processIncomingSos(ActiveSosEntry("SOS-3", sender1, "Alice", "Main Stage", "Gate 3", nowMs + 1000L), nowMs + 1000L)
        assertEquals("Update from existing sender retains 2 active alerts total", 2, ActiveSosManager.getAllActiveSos(nowMs + 1000L).size)
    }

    @Test
    fun test32_canonicalSerializationDeterministic_sosCancel() {
        val cancelPayload = SosPayload("CANCEL-999", "Alice", "Food Court", "SOS Cancelled", true, "SOS-777", nowMs)
        val firstEncode = cancelPayload.encode()
        for (i in 1..100) {
            val nextEncode = cancelPayload.encode()
            assertArrayEquals("SOS_CANCEL serialization must be 100% byte-identical across calls", firstEncode, nextEncode)
        }
        val decoded = SosPayload.decode(firstEncode)
        assertNotNull(decoded)
        assertEquals("CANCEL-999", decoded?.id)
        assertTrue("isCancel must round-trip", decoded?.isCancel == true)
        assertEquals("SOS-777", decoded?.originalSosId)
    }

    @Test
    fun test33_independentConstructionProducesIdenticalBytes() {
        val ts = 1700000000000L
        val payloadA = SosPayload("SOS-SAME", "Charlie", "Medical", "Gate 3", false, null, ts)
        val payloadB = SosPayload("SOS-SAME", "Charlie", "Medical", "Gate 3", false, null, ts)
        assertArrayEquals("Two independently constructed identical payloads must produce identical bytes", payloadA.encode(), payloadB.encode())

        val cancelA = SosPayload("CANCEL-SAME", "Charlie", "Medical", "SOS Cancelled", true, "SOS-SAME", ts)
        val cancelB = SosPayload("CANCEL-SAME", "Charlie", "Medical", "SOS Cancelled", true, "SOS-SAME", ts)
        assertArrayEquals("Two independently constructed identical cancels must produce identical bytes", cancelA.encode(), cancelB.encode())
    }
}
