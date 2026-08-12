package com.bitchat.android.group

import android.content.SharedPreferences
import com.bitchat.android.model.GroupId
import com.bitchat.android.model.PrivateGroup
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

class GroupManagerSecurityTest {

    private lateinit var testPrefs: MapSharedPreferences
    private lateinit var keyStore: SecureGroupKeyStore
    private lateinit var manager: GroupManager

    private val nowMs = System.currentTimeMillis()
    private val peerA = "1111111111111111"
    private val peerB = "2222222222222222"
    private val peerC = "3333333333333333"

    private val pubKeyA = ByteArray(32) { 0x01.toByte() }
    private val pubKeyB = ByteArray(32) { 0x02.toByte() }
    private val pubKeyC = ByteArray(32) { 0x03.toByte() }

    @Before
    fun setUp() {
        testPrefs = MapSharedPreferences()
        keyStore = SecureGroupKeyStore.createTestInstance(testPrefs)
        manager = GroupManager.createTestInstance(keyStore)
        manager.clearAllForTesting()
    }

    // --- 1. Creation Tests ---

    @Test
    fun test1_validGroupCreation() {
        val group = manager.createGroup("Festival Squad", peerA, pubKeyA)
        assertNotNull("Group creation must succeed", group)
        assertTrue("GroupId must be valid", GroupId.isValid(group?.groupId))
        assertEquals("Festival Squad", group?.groupName)
        assertEquals(1, group?.activeEpoch)
        assertTrue("Creator must be admin", group?.adminPeerIds?.contains(peerA) == true)
        assertTrue("Creator must be member", group?.memberPeerIds?.contains(peerA) == true)

        // Key stored in keyStore, metadata has NO key
        assertTrue("Epoch 1 key must be stored", keyStore.hasGroupKey(group!!.groupId, 1))
    }

    @Test
    fun test2_randomGroupIdsUnique() {
        val g1 = manager.createGroup("Group One", peerA, pubKeyA)
        val g2 = manager.createGroup("Group Two", peerA, pubKeyA)
        assertNotNull(g1)
        assertNotNull(g2)
        assertNotEquals("Generated group IDs must be unique", g1?.groupId, g2?.groupId)
    }

    @Test
    fun test3_invalidGroupNameRejected() {
        assertNull("Empty group name rejected", manager.createGroup("", peerA, pubKeyA))
        assertNull("Whitespace group name rejected", manager.createGroup("   \t\n ", peerA, pubKeyA))
    }

    // --- 2. Key Storage Security ---

    @Test
    fun test4_metadataContainsNoRawKey() {
        val group = manager.createGroup("Security Test Group", peerA, pubKeyA)!!
        val activeKey = keyStore.getGroupKey(group.groupId, 1)
        assertNotNull(activeKey)

        // Reflection over group properties to verify no SecretKeySpec fields exist in PrivateGroup
        val fields = PrivateGroup::class.java.declaredFields
        val keyFields = fields.filter { it.type == SecretKeySpec::class.java }
        assertEquals("PrivateGroup metadata MUST contain zero SecretKeySpec fields", 0, keyFields.size)
    }

    @Test
    fun test5_wrongEpochKeyUnavailable() {
        val group = manager.createGroup("Epoch Test Group", peerA, pubKeyA)!!
        assertNull("Non-existent Epoch 99 key must be null", keyStore.getGroupKey(group.groupId, 99))
    }

    // --- 3. Invitation Tests ---

    @Test
    fun test6_validInvitationCreation() {
        val group = manager.createGroup("Invite Group", peerA, pubKeyA)!!
        val invite = manager.createInvitation(group.groupId, targetPeerId = peerB, inviterPeerId = peerA, nowMs = nowMs)

        assertNotNull("Admin can create invitation", invite)
        assertEquals(group.groupId, invite?.groupId)
        assertEquals(peerA, invite?.inviterPeerId)
        assertEquals(1, invite?.invitationEpoch)
        assertEquals(nowMs + 15 * 60 * 1000L, invite?.expiresAtMs)
    }

    @Test
    fun test7_nonAdminInvitationRejected() {
        val group = manager.createGroup("Admin Only Group", peerA, pubKeyA)!!
        // Inject peerB as member (non-admin)
        manager.loadGroupForTesting(group.copy(memberPeerIds = setOf(peerA, peerB)))

        val invite = manager.createInvitation(group.groupId, targetPeerId = peerC, inviterPeerId = peerB)
        assertNull("Non-admin cannot create invitation", invite)
    }

    @Test
    fun test8_expiredInvitationRejected() {
        val group = manager.createGroup("Expiry Group", peerA, pubKeyA)!!
        val invite = manager.createInvitation(group.groupId, targetPeerId = peerB, inviterPeerId = peerA, nowMs = nowMs)!!

        val acceptTimeExpired = nowMs + 16 * 60 * 1000L // 16 mins later (> 15 min expiration)
        val acceptResult = manager.acceptInvitation(invite, acceptorPeerId = peerB, acceptorSigningPubKey = pubKeyB, nowMs = acceptTimeExpired)
        assertNull("Expired invitation acceptance must be rejected", acceptResult)
    }

    @Test
    fun test9_duplicateInvitationRejected() {
        val group = manager.createGroup("Duplicate Invite Group", peerA, pubKeyA)!!
        val invite = manager.createInvitation(group.groupId, targetPeerId = peerB, inviterPeerId = peerA, nowMs = nowMs)!!

        val accept1 = manager.acceptInvitation(invite, peerB, pubKeyB, nowMs)
        assertNotNull("First acceptance succeeds", accept1)

        val accept2 = manager.acceptInvitation(invite, peerB, pubKeyB, nowMs + 1000L)
        assertNull("Duplicate invitation acceptance must be rejected", accept2)
    }

    // --- 4. Rotation & Member Management ---

    @Test
    fun test10_memberRemovalRotatesEpoch() {
        val group = manager.createGroup("Removal Test", peerA, pubKeyA)!!
        // Add peerB
        manager.loadGroupForTesting(group.copy(memberPeerIds = setOf(peerA, peerB)))
        keyStore.saveGroupKey(group.groupId, 1, SecretKeySpec(ByteArray(32) { 1 }, "AES"))

        assertTrue(manager.removeMember(group.groupId, targetPeerId = peerB, adminPeerId = peerA, nowMs = nowMs))

        val updatedGroup = manager.getGroup(group.groupId)!!
        assertEquals("Epoch must rotate to 2 after removal", 2, updatedGroup.activeEpoch)
        assertFalse("PeerB must be removed from member list", updatedGroup.memberPeerIds.contains(peerB))
        assertTrue("Epoch 2 key must exist in keyStore", keyStore.hasGroupKey(group.groupId, 2))
    }

    @Test
    fun test11_voluntaryLeaveRotatesEpoch() {
        val group = manager.createGroup("Leave Test", peerA, pubKeyA)!!
        manager.loadGroupForTesting(group.copy(memberPeerIds = setOf(peerA, peerB)))
        keyStore.saveGroupKey(group.groupId, 1, SecretKeySpec(ByteArray(32) { 1 }, "AES"))

        assertTrue(manager.leaveGroup(group.groupId, memberPeerId = peerB, nowMs = nowMs))

        val updatedGroup = manager.getGroup(group.groupId)!!
        assertEquals("Epoch must rotate to 2 after member leave", 2, updatedGroup.activeEpoch)
        assertFalse("PeerB must no longer be in group", updatedGroup.memberPeerIds.contains(peerB))
    }

    // --- 5. Mandatory Critical Security Sequence (Item 18 Requirement) ---

    @Test
    fun test12_MANDATORY_CRITICAL_SECURITY_SEQUENCE() {
        // --- STEP 1: Epoch 1 (Members A, B) ---
        val g1 = manager.createGroup("Critical Group", peerA, pubKeyA)!!
        val groupId = g1.groupId
        assertEquals(1, g1.activeEpoch)

        // Inject peerB into Epoch 1
        val groupWithB = g1.copy(
            memberPeerIds = setOf(peerA, peerB),
            memberSigningKeys = mapOf(peerA to pubKeyA, peerB to pubKeyB)
        )
        manager.loadGroupForTesting(groupWithB)
        val k1 = keyStore.getGroupKey(groupId, 1)!!
        assertNotNull("K1 must exist for Epoch 1", k1)

        // --- STEP 2: A invites C -> C accepts -> Epoch 2 ---
        val inviteC = manager.createInvitation(groupId, targetPeerId = peerC, inviterPeerId = peerA, nowMs = nowMs)!!
        assertEquals(1, inviteC.invitationEpoch)

        val acceptC = manager.acceptInvitation(inviteC, acceptorPeerId = peerC, acceptorSigningPubKey = pubKeyC, nowMs = nowMs + 1000L)!!
        val (groupEpoch2, keyDistEpoch2) = manager.processIncomingAccept(acceptC, adminPeerId = peerA, nowMs = nowMs + 2000L)!!

        assertEquals("Epoch must rotate to 2 upon adding C", 2, groupEpoch2.activeEpoch)
        assertTrue("C is now a member of Epoch 2 group", groupEpoch2.memberPeerIds.contains(peerC))

        val k2 = keyStore.getGroupKey(groupId, 2)!!
        assertNotNull("K2 must exist for Epoch 2", k2)
        assertFalse("K2 must be a distinct fresh key from K1", k1.encoded.contentEquals(k2.encoded))

        // C simulates receiving K2 key distribution
        val keyStoreC = SecureGroupKeyStore.createTestInstance(MapSharedPreferences())
        val managerC = GroupManager.createTestInstance(keyStoreC)
        managerC.loadGroupForTesting(groupEpoch2)

        assertTrue("C can process K2 distribution", managerC.processKeyDistribution(keyDistEpoch2, receiverPeerId = peerC))
        assertTrue("C has K2 stored", keyStoreC.hasGroupKey(groupId, 2))
        assertFalse("C DOES NOT have K1 stored (C cannot decrypt Epoch 1)", keyStoreC.hasGroupKey(groupId, 1))

        // --- STEP 3: B removed -> Epoch 3 ---
        assertTrue("Admin A removes B", manager.removeMember(groupId, targetPeerId = peerB, adminPeerId = peerA, nowMs = nowMs + 5000L))

        val groupEpoch3 = manager.getGroup(groupId)!!
        assertEquals("Epoch must rotate to 3 upon removing B", 3, groupEpoch3.activeEpoch)
        assertFalse("B is removed from member list", groupEpoch3.memberPeerIds.contains(peerB))

        val k3 = keyStore.getGroupKey(groupId, 3)!!
        assertNotNull("K3 must exist for Epoch 3", k3)
        assertFalse("K3 must be distinct from K2", k2.encoded.contentEquals(k3.encoded))

        // Distribute K3 to remaining members (A and C)
        val k3Dist = GroupControlPayload.KeyDistribution(groupId, 3, java.util.Base64.getEncoder().encodeToString(k3.encoded), nowMs + 5000L)

        // Member C receives K3
        assertTrue("C receives K3", managerC.processKeyDistribution(k3Dist, receiverPeerId = peerC))
        assertTrue("C has K3", keyStoreC.hasGroupKey(groupId, 3))

        // Simulate B attempting to process K3 distribution
        val keyStoreB = SecureGroupKeyStore.createTestInstance(MapSharedPreferences())
        val managerB = GroupManager.createTestInstance(keyStoreB)
        managerB.loadGroupForTesting(groupEpoch3) // groupEpoch3 does NOT contain B

        assertFalse("B CANNOT process K3 distribution (not a member)", managerB.processKeyDistribution(k3Dist, receiverPeerId = peerB))
        assertFalse("B DOES NOT have K3 (B cannot decrypt Epoch 3)", keyStoreB.hasGroupKey(groupId, 3))
    }
}

/**
 * Minimal in-memory SharedPreferences implementation for JUnit tests.
 */
internal class MapSharedPreferences : SharedPreferences {
    private val map = HashMap<String, String>()

    override fun getAll(): Map<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?): String? = map[key] ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = defValue
    override fun getLong(key: String, defValue: Long): Long = defValue
    override fun getFloat(key: String, defValue: Float): Float = defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = EditorImpl()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class EditorImpl : SharedPreferences.Editor {
        private val tempMap = HashMap<String, String?>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            tempMap[key] = value
            return this
        }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String): SharedPreferences.Editor {
            tempMap[key] = null
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            map.clear()
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            for ((k, v) in tempMap) {
                if (v == null) map.remove(k) else map[k] = v
            }
        }
    }
}
