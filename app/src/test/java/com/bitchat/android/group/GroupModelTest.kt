package com.bitchat.android.group

import com.bitchat.android.model.GroupId
import com.bitchat.android.model.GroupName
import com.bitchat.android.model.PrivateGroup
import com.bitchat.android.protocol.MessageType
import org.junit.Assert.*
import org.junit.Test

class GroupModelTest {

    // --- MessageType Protocol Test ---

    @Test
    fun testMessageTypeEnum_groupTypesExist() {
        assertEquals("GROUP_CONTROL value must be 0x07", 0x07u.toUByte(), MessageType.GROUP_CONTROL.value)
        assertEquals("GROUP_MESSAGE value must be 0x08", 0x08u.toUByte(), MessageType.GROUP_MESSAGE.value)
        assertEquals(MessageType.GROUP_CONTROL, MessageType.fromValue(0x07u))
        assertEquals(MessageType.GROUP_MESSAGE, MessageType.fromValue(0x08u))
    }

    // --- GroupId Tests ---

    @Test
    fun testGroupId_generationAndFormat() {
        val groupId = GroupId.generate()
        assertTrue("GroupId must start with grp_ prefix", groupId.startsWith("grp_"))
        assertEquals("GroupId must be grp_ + 32 hex chars (36 chars total)", 36, groupId.length)
        assertTrue("GroupId must pass validation", GroupId.isValid(groupId))
    }

    @Test
    fun testGroupId_rawBytesRoundTrip() {
        val groupId = GroupId.generate()
        val rawBytes = GroupId.toRawBytes(groupId)
        assertNotNull("Raw bytes must not be null for valid GroupId", rawBytes)
        assertEquals("Raw bytes must be 16 bytes (128 bits)", 16, rawBytes?.size)

        val restoredGroupId = GroupId.fromRawBytes(rawBytes!!)
        assertEquals("Restored GroupId must match original", groupId, restoredGroupId)
    }

    @Test
    fun testGroupId_invalidRejection() {
        assertFalse(GroupId.isValid(null))
        assertFalse(GroupId.isValid(""))
        assertFalse(GroupId.isValid("invalid_prefix_123456789012345678901234567890"))
        assertFalse(GroupId.isValid("grp_short"))
        assertFalse(GroupId.isValid("grp_1234567890123456789012345678901Z")) // 'Z' invalid hex
        assertNull(GroupId.toRawBytes("invalid"))
        assertNull(GroupId.fromRawBytes(ByteArray(15))) // Invalid byte length
    }

    // --- GroupName Tests ---

    @Test
    fun testGroupName_validationAndSanitization() {
        val validName = "Festival Crew"
        assertTrue(GroupName.isValid(validName))
        assertEquals("Festival Crew", GroupName.sanitize(validName))

        // Control characters & newlines
        val dirtyName = "Festival\n\r\tCrew\u0000 2026"
        val sanitized = GroupName.sanitize(dirtyName)
        assertFalse("Control characters must be stripped", sanitized.contains("\n") || sanitized.contains("\r") || sanitized.contains("\t") || sanitized.contains("\u0000"))
        assertEquals("Festival Crew 2026", sanitized)

        // Truncation at 30 chars
        val longName = "A".repeat(50)
        val truncated = GroupName.sanitize(longName)
        assertEquals(30, truncated.length)

        // Invalid inputs
        assertFalse(GroupName.isValid(""))
        assertFalse(GroupName.isValid("   \n\t  "))
        assertFalse(GroupName.isValid(null))
    }

    // --- PrivateGroup Model Test ---

    @Test
    fun testPrivateGroupModel_instantiationWithoutSecretKeys() {
        val creatorPubKey = ByteArray(32) { 0x11.toByte() }
        val memberPubKey = ByteArray(32) { 0x22.toByte() }
        val groupId = GroupId.generate()

        val group = PrivateGroup(
            groupId = groupId,
            groupName = "VIP Lounge",
            creatorPeerId = "a1b2c3d4e5f67890",
            creatorSigningPubKey = creatorPubKey,
            adminPeerIds = setOf("a1b2c3d4e5f67890"),
            memberPeerIds = setOf("a1b2c3d4e5f67890", "b2c3d4e5f67890a1"),
            memberSigningKeys = mapOf(
                "a1b2c3d4e5f67890" to creatorPubKey,
                "b2c3d4e5f67890a1" to memberPubKey
            ),
            activeEpoch = 1,
            createdAtMs = 1700000000000L
        )

        assertEquals(groupId, group.groupId)
        assertEquals("VIP Lounge", group.groupName)
        assertEquals(1, group.activeEpoch)
        assertEquals(2, group.memberPeerIds.size)
    }

    // --- GroupControlPayload Deterministic Serialization Tests ---

    @Test
    fun testGroupControlPayload_inviteDeterministicSerialization() {
        val payload = GroupControlPayload.Invite(
            invitationId = "inv_12345678",
            groupId = "grp_12345678901234567890123456789012",
            groupName = "Main Stage Friends",
            creatorPeerId = "a1b2c3d4e5f67890",
            inviterPeerId = "a1b2c3d4e5f67890",
            invitationEpoch = 1,
            encryptedGroupKey = "bGFzdF9zZWNyZXRfa2V5X2Jhc2U2NA==",
            timestampMs = 1700000000000L,
            expiresAtMs = 1700000900000L
        )

        val firstEncode = payload.encode()
        for (i in 1..100) {
            val nextEncode = payload.encode()
            assertArrayEquals("INVITE canonical serialization must be 100% byte-identical", firstEncode, nextEncode)
        }

        val decoded = GroupControlPayload.decode(firstEncode)
        assertTrue("Decoded payload must be Invite", decoded is GroupControlPayload.Invite)
        val inviteDecoded = decoded as GroupControlPayload.Invite
        assertEquals(payload.invitationId, inviteDecoded.invitationId)
        assertEquals(payload.groupId, inviteDecoded.groupId)
        assertEquals(payload.groupName, inviteDecoded.groupName)
        assertEquals(payload.invitationEpoch, inviteDecoded.invitationEpoch)
    }

    @Test
    fun testGroupControlPayload_acceptDeterministicSerialization() {
        val payload = GroupControlPayload.GroupAccept(
            invitationId = "inv_12345678",
            groupId = "grp_12345678901234567890123456789012",
            acceptorPeerId = "b2c3d4e5f67890a1",
            acceptorSigningPubKeyB64 = "c2lnbmluZ19rZXlfYmFzZTY0",
            timestampMs = 1700000050000L
        )

        val firstEncode = payload.encode()
        for (i in 1..100) {
            val nextEncode = payload.encode()
            assertArrayEquals("GROUP_ACCEPT canonical serialization must be 100% byte-identical", firstEncode, nextEncode)
        }

        val decoded = GroupControlPayload.decode(firstEncode)
        assertTrue("Decoded payload must be GroupAccept", decoded is GroupControlPayload.GroupAccept)
        val acceptDecoded = decoded as GroupControlPayload.GroupAccept
        assertEquals(payload.invitationId, acceptDecoded.invitationId)
        assertEquals(payload.acceptorPeerId, acceptDecoded.acceptorPeerId)
    }

    @Test
    fun testGroupControlPayload_removeDeterministicSerialization() {
        val payload = GroupControlPayload.Remove(
            groupId = "grp_12345678901234567890123456789012",
            targetPeerId = "c3d4e5f67890a1b2",
            adminPeerId = "a1b2c3d4e5f67890",
            newEpoch = 2,
            timestampMs = 1700000100000L
        )

        val firstEncode = payload.encode()
        for (i in 1..100) {
            assertArrayEquals("REMOVE canonical serialization must be 100% byte-identical", firstEncode, payload.encode())
        }

        val decoded = GroupControlPayload.decode(firstEncode) as? GroupControlPayload.Remove
        assertNotNull(decoded)
        assertEquals(payload.targetPeerId, decoded?.targetPeerId)
        assertEquals(2, decoded?.newEpoch)
    }

    @Test
    fun testGroupControlPayload_leaveDeterministicSerialization() {
        val payload = GroupControlPayload.Leave(
            groupId = "grp_12345678901234567890123456789012",
            leavingPeerId = "b2c3d4e5f67890a1",
            timestampMs = 1700000200000L
        )

        val firstEncode = payload.encode()
        for (i in 1..100) {
            assertArrayEquals("LEAVE canonical serialization must be 100% byte-identical", firstEncode, payload.encode())
        }

        val decoded = GroupControlPayload.decode(firstEncode) as? GroupControlPayload.Leave
        assertNotNull(decoded)
        assertEquals(payload.leavingPeerId, decoded?.leavingPeerId)
    }

    @Test
    fun testGroupControlPayload_keyDistributionDeterministicSerialization() {
        val payload = GroupControlPayload.KeyDistribution(
            groupId = "grp_12345678901234567890123456789012",
            epoch = 3,
            encryptedGroupKey = "ZW5jcnlwdGVkX2tleV9kYXRh",
            timestampMs = 1700000300000L
        )

        val firstEncode = payload.encode()
        for (i in 1..100) {
            assertArrayEquals("KEY_DISTRIBUTION canonical serialization must be 100% byte-identical", firstEncode, payload.encode())
        }

        val decoded = GroupControlPayload.decode(firstEncode) as? GroupControlPayload.KeyDistribution
        assertNotNull(decoded)
        assertEquals(3, decoded?.epoch)
        assertEquals(payload.encryptedGroupKey, decoded?.encryptedGroupKey)
    }

    @Test
    fun testGroupControlPayload_malformedAndOversizedRejection() {
        assertNull("Empty payload rejected", GroupControlPayload.decode(ByteArray(0)))
        assertNull("Malformed JSON rejected", GroupControlPayload.decode("not_valid_json".toByteArray()))
        assertNull("Missing controlType rejected", GroupControlPayload.decode("{\"groupId\":\"grp_123\"}".toByteArray()))
        assertNull("Unknown controlType rejected", GroupControlPayload.decode("{\"controlType\":\"UNKNOWN\"}".toByteArray()))

        val oversized = ByteArray(5000) { 'A'.toByte() }
        assertNull("Oversized payload > 4KB rejected", GroupControlPayload.decode(oversized))
    }

    // --- GroupMessagePayload Binary Wire Format Tests ---

    @Test
    fun testGroupMessagePayload_encodeAndDecode() {
        val groupIdBytes = ByteArray(16) { (it + 1).toByte() }
        val epoch = 2
        val iv = ByteArray(12) { (it + 10).toByte() }
        val ciphertext = ByteArray(32) { (it + 20).toByte() } // 16B ciphertext + 16B GCM tag

        val payload = GroupMessagePayload(
            groupIdBytes = groupIdBytes,
            epoch = epoch,
            iv = iv,
            ciphertext = ciphertext
        )

        val encodedBytes = payload.encode()
        assertEquals("Total encoded size must be 16 + 4 + 12 + 32 = 64 bytes", 64, encodedBytes.size)

        val decoded = GroupMessagePayload.decode(encodedBytes)
        assertNotNull("Decoded payload must not be null", decoded)
        assertArrayEquals("GroupIdBytes must match", groupIdBytes, decoded?.groupIdBytes)
        assertEquals("Epoch must match", epoch, decoded?.epoch)
        assertArrayEquals("IV must match", iv, decoded?.iv)
        assertArrayEquals("Ciphertext must match", ciphertext, decoded?.ciphertext)
    }

    @Test
    fun testGroupMessagePayload_invalidRejection() {
        // Less than 48 bytes (minimum HEADER 32B + GCM 16B)
        assertNull("31 bytes rejected (< header)", GroupMessagePayload.decode(ByteArray(31)))
        assertNull("47 bytes rejected (< MIN 48 bytes)", GroupMessagePayload.decode(ByteArray(47)))

        // Oversized (> 64KB)
        val oversized = ByteArray(65 * 1024)
        assertNull("Oversized > 64KB rejected", GroupMessagePayload.decode(oversized))
    }

    @Test
    fun testGroupMessagePayload_aadLayout() {
        val groupIdBytes = ByteArray(16) { 0xAA.toByte() }
        val epoch = 5
        val senderIdBytes = ByteArray(8) { 0xBB.toByte() }
        val timestampMs = 1700000000000L

        val aad = GroupMessagePayload.buildAAD(groupIdBytes, epoch, senderIdBytes, timestampMs)
        assertEquals("AAD total byte size must be 16 + 4 + 8 + 8 = 36 bytes", 36, aad.size)

        // Verify layout slicing
        assertArrayEquals("GroupId bytes in AAD slice 0..15", groupIdBytes, aad.sliceArray(0..15))
        assertArrayEquals("SenderId bytes in AAD slice 20..27", senderIdBytes, aad.sliceArray(20..27))
    }
}
