package com.bitchat.android.group

import android.content.Context
import android.util.Base64
import android.util.Log
import com.bitchat.android.model.GroupId
import com.bitchat.android.model.GroupName
import com.bitchat.android.model.PrivateGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

/**
 * Core manager for Private Friend Groups state management, invitation lifecycle,
 * and epoch secret key rotation.
 *
 * CRITICAL SECURITY ARCHITECTURE:
 * - Group secret keys are managed strictly by SecureGroupKeyStore (EncryptedSharedPreferences).
 * - PrivateGroup metadata contains ZERO raw key material.
 * - Key rotation occurs on member add, member remove, and member leave.
 * - Epoch-based membership confidentiality:
 *   * New members receive only current epoch key K_current (never past keys).
 *   * Removed members do not receive new epoch key K_new (never future keys).
 * - Grace period semantics: Pre-rotation packets may be decrypted with previous epoch key
 *   for up to 2 minutes after rotation. Post-rotation packets MUST use K_new.
 */
class GroupManager private constructor(
    private val keyStore: SecureGroupKeyStore
) {

    companion object {
        private const val TAG = "GroupManager"
        const val INVITATION_EXPIRATION_MS = 15 * 60 * 1000L // 15 minutes
        const val KEY_GRACE_WINDOW_MS = 2 * 60 * 1000L // 2 minutes

        @Volatile
        private var INSTANCE: GroupManager? = null

        fun getInstance(context: Context): GroupManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GroupManager(SecureGroupKeyStore.getInstance(context)).also { INSTANCE = it }
            }
        }

        internal fun createTestInstance(testKeyStore: SecureGroupKeyStore): GroupManager {
            return GroupManager(testKeyStore)
        }
    }

    // Active groups: groupId -> PrivateGroup metadata
    private val activeGroups = ConcurrentHashMap<String, PrivateGroup>()

    // Pending invitations received by local user: invitationId -> Invite
    private val pendingInvitations = ConcurrentHashMap<String, GroupControlPayload.Invite>()

    // Reactive state flows for UI binding
    private val _groupsFlow = MutableStateFlow<List<PrivateGroup>>(emptyList())
    val groupsFlow: StateFlow<List<PrivateGroup>> = _groupsFlow.asStateFlow()

    private val _pendingInvitationsFlow = MutableStateFlow<List<GroupControlPayload.Invite>>(emptyList())
    val pendingInvitationsFlow: StateFlow<List<GroupControlPayload.Invite>> = _pendingInvitationsFlow.asStateFlow()

    // Key rotation timestamps: groupId -> Map(epoch -> rotationTimestampMs)
    private val rotationTimestamps = ConcurrentHashMap<String, ConcurrentHashMap<Int, Long>>()

    // Processed invitation IDs to prevent replay
    private val processedInvitations = ConcurrentHashMap.newKeySet<String>()

    private fun notifyGroupsUpdated() {
        _groupsFlow.value = activeGroups.values.toList()
    }

    private fun notifyInvitationsUpdated() {
        _pendingInvitationsFlow.value = pendingInvitations.values.toList()
    }

    fun onInvitationReceived(invite: GroupControlPayload.Invite) {
        if (System.currentTimeMillis() <= invite.expiresAtMs && !processedInvitations.contains(invite.invitationId)) {
            pendingInvitations[invite.invitationId] = invite
            notifyInvitationsUpdated()
        }
    }

    fun declineInvitation(invitationId: String) {
        pendingInvitations.remove(invitationId)
        processedInvitations.add(invitationId)
        notifyInvitationsUpdated()
    }

    fun clearAllForTesting() {
        activeGroups.clear()
        pendingInvitations.clear()
        rotationTimestamps.clear()
        processedInvitations.clear()
        keyStore.clearCacheForTesting()
        notifyGroupsUpdated()
        notifyInvitationsUpdated()
    }

    // --- 1. Group Creation ---

    /**
     * Creates a new Private Friend Group.
     * Generates a 16-byte random GroupId, 256-bit AES key for Epoch 1,
     * sets creator as sole admin & member, and persists key in SecureGroupKeyStore.
     */
    fun createGroup(
        groupName: String,
        creatorPeerId: String,
        creatorSigningPubKey: ByteArray
    ): PrivateGroup? {
        if (!GroupName.isValid(groupName)) {
            Log.w(TAG, "Cannot create group: invalid group name '$groupName'")
            return null
        }
        if (creatorPeerId.isBlank() || creatorSigningPubKey.size != 32) {
            Log.w(TAG, "Cannot create group: invalid creator identity")
            return null
        }

        val sanitizedName = GroupName.sanitize(groupName)
        val groupId = GroupId.generate()

        // Generate 256-bit AES group key for Epoch 1
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        // Persist Epoch 1 key securely
        if (!keyStore.saveGroupKey(groupId, epoch = 1, secretKey = secretKey)) {
            Log.e(TAG, "Failed to persist initial group key for $groupId")
            return null
        }

        val group = PrivateGroup(
            groupId = groupId,
            groupName = sanitizedName,
            creatorPeerId = creatorPeerId,
            creatorSigningPubKey = creatorSigningPubKey,
            adminPeerIds = setOf(creatorPeerId),
            memberPeerIds = setOf(creatorPeerId),
            memberSigningKeys = mapOf(creatorPeerId to creatorSigningPubKey),
            activeEpoch = 1,
            createdAtMs = System.currentTimeMillis()
        )

        activeGroups[groupId] = group
        notifyGroupsUpdated()
        Log.i(TAG, "Successfully created group '$sanitizedName' ($groupId) at epoch 1")
        return group
    }

    fun getGroup(groupId: String): PrivateGroup? = activeGroups[groupId]

    fun getAllGroups(): List<PrivateGroup> = activeGroups.values.toList()

    // --- 2. Invitations ---

    /**
     * Creates an invitation payload for a target peer.
     * Admin authorization required. Target must not already be a member.
     * Encrypts the current epoch group key using simple B64 wrapper (in production delivered over pairwise Noise).
     */
    fun createInvitation(
        groupId: String,
        targetPeerId: String,
        inviterPeerId: String,
        nowMs: Long = System.currentTimeMillis()
    ): GroupControlPayload.Invite? {
        val group = activeGroups[groupId] ?: run {
            Log.w(TAG, "Cannot create invite: group $groupId not found")
            return null
        }

        if (!group.adminPeerIds.contains(inviterPeerId)) {
            Log.w(TAG, "Cannot create invite: inviter $inviterPeerId is not an admin of group $groupId")
            return null
        }

        if (group.memberPeerIds.contains(targetPeerId)) {
            Log.w(TAG, "Cannot create invite: target $targetPeerId is already a member of group $groupId")
            return null
        }

        val currentKey = keyStore.getGroupKey(groupId, group.activeEpoch) ?: run {
            Log.e(TAG, "Cannot create invite: missing active epoch key for group $groupId")
            return null
        }

        val randomBytes = ByteArray(6)
        SecureRandom().nextBytes(randomBytes)
        val invitationId = "inv_" + randomBytes.joinToString("") { "%02x".format(it) }

        val encryptedKeyB64 = java.util.Base64.getEncoder().encodeToString(currentKey.encoded)

        return GroupControlPayload.Invite(
            invitationId = invitationId,
            groupId = groupId,
            groupName = group.groupName,
            creatorPeerId = group.creatorPeerId,
            inviterPeerId = inviterPeerId,
            invitationEpoch = group.activeEpoch,
            encryptedGroupKey = encryptedKeyB64,
            timestampMs = nowMs,
            expiresAtMs = nowMs + INVITATION_EXPIRATION_MS
        )
    }

    /**
     * Validates and accepts an invitation on the receiver side.
     */
    fun acceptInvitation(
        invite: GroupControlPayload.Invite,
        acceptorPeerId: String,
        acceptorSigningPubKey: ByteArray,
        nowMs: Long = System.currentTimeMillis()
    ): GroupControlPayload.GroupAccept? {
        if (nowMs > invite.expiresAtMs) {
            Log.w(TAG, "Cannot accept invitation: expired at ${invite.expiresAtMs}, current $nowMs")
            return null
        }
        if (!GroupId.isValid(invite.groupId)) {
            Log.w(TAG, "Cannot accept invitation: invalid groupId ${invite.groupId}")
            return null
        }
        if (processedInvitations.contains(invite.invitationId)) {
            Log.w(TAG, "Cannot accept invitation: already processed invitation ${invite.invitationId}")
            return null
        }

        processedInvitations.add(invite.invitationId)
        pendingInvitations.remove(invite.invitationId)
        notifyInvitationsUpdated()

        // Store received epoch key locally for acceptor
        val keyBytes = java.util.Base64.getDecoder().decode(invite.encryptedGroupKey)
        if (keyBytes.size == 32) {
            val secretKey = SecretKeySpec(keyBytes, "AES")
            keyStore.saveGroupKey(invite.groupId, invite.invitationEpoch, secretKey)
        }

        val pubKeyB64 = java.util.Base64.getEncoder().encodeToString(acceptorSigningPubKey)
        return GroupControlPayload.GroupAccept(
            invitationId = invite.invitationId,
            groupId = invite.groupId,
            acceptorPeerId = acceptorPeerId,
            acceptorSigningPubKeyB64 = pubKeyB64,
            timestampMs = nowMs
        )
    }

    /**
     * Processes incoming GROUP_ACCEPT on the admin side.
     * Adds acceptor as new member and triggers key rotation to epoch E+1.
     * Returns updated PrivateGroup and a KeyDistribution payload for active members.
     */
    fun processIncomingAccept(
        accept: GroupControlPayload.GroupAccept,
        adminPeerId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<PrivateGroup, GroupControlPayload.KeyDistribution>? {
        val group = activeGroups[accept.groupId] ?: return null
        if (!group.adminPeerIds.contains(adminPeerId)) {
            Log.w(TAG, "Cannot process accept: $adminPeerId is not admin of group ${accept.groupId}")
            return null
        }

        val pubKeyBytes = java.util.Base64.getDecoder().decode(accept.acceptorSigningPubKeyB64)
        if (pubKeyBytes.size != 32) {
            Log.w(TAG, "Invalid signing pubkey size in accept payload")
            return null
        }

        val updatedMembers = group.memberPeerIds + accept.acceptorPeerId
        val updatedSigningKeys = group.memberSigningKeys + (accept.acceptorPeerId to pubKeyBytes)

        val updatedGroup = group.copy(
            memberPeerIds = updatedMembers,
            memberSigningKeys = updatedSigningKeys
        )
        activeGroups[group.groupId] = updatedGroup
        notifyGroupsUpdated()

        // Rotate key for new membership
        val newEpoch = rotateGroupKey(group.groupId, reason = "MEMBER_ADDED", nowMs = nowMs)
        val newGroup = activeGroups[group.groupId] ?: updatedGroup

        val newKey = keyStore.getGroupKey(group.groupId, newEpoch) ?: return null
        val encryptedKeyB64 = java.util.Base64.getEncoder().encodeToString(newKey.encoded)

        val keyDist = GroupControlPayload.KeyDistribution(
            groupId = group.groupId,
            epoch = newEpoch,
            encryptedGroupKey = encryptedKeyB64,
            timestampMs = nowMs
        )

        return Pair(newGroup, keyDist)
    }

    /**
     * Stores a received KeyDistribution payload locally for a group member.
     */
    fun processKeyDistribution(
        keyDist: GroupControlPayload.KeyDistribution,
        receiverPeerId: String
    ): Boolean {
        val group = activeGroups[keyDist.groupId] ?: return false
        if (!group.memberPeerIds.contains(receiverPeerId)) {
            Log.w(TAG, "Rejecting key distribution: $receiverPeerId is not a member of ${keyDist.groupId}")
            return false
        }

        val keyBytes = java.util.Base64.getDecoder().decode(keyDist.encryptedGroupKey)
        if (keyBytes.size != 32) return false

        val secretKey = SecretKeySpec(keyBytes, "AES")
        val saved = keyStore.saveGroupKey(keyDist.groupId, keyDist.epoch, secretKey)
        if (saved && keyDist.epoch > group.activeEpoch) {
            activeGroups[keyDist.groupId] = group.copy(activeEpoch = keyDist.epoch)
        }
        return saved
    }

    // --- 3. Key Rotation Engine ---

    /**
     * Rotates group key to epoch E + 1.
     * Generates a cryptographically random 256-bit AES key, persists it securely,
     * updates group's activeEpoch, and records rotation timestamp for grace period tracking.
     */
    fun rotateGroupKey(
        groupId: String,
        reason: String,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val group = activeGroups[groupId] ?: throw IllegalArgumentException("Group $groupId not found")
        val newEpoch = group.activeEpoch + 1

        val newKeyBytes = ByteArray(32)
        SecureRandom().nextBytes(newKeyBytes)
        val newKey = SecretKeySpec(newKeyBytes, "AES")

        if (!keyStore.saveGroupKey(groupId, newEpoch, newKey)) {
            throw IllegalStateException("Failed to save rotated key for $groupId epoch $newEpoch")
        }

        // Record rotation timestamp for grace period
        val epochMap = rotationTimestamps.getOrPut(groupId) { ConcurrentHashMap() }
        epochMap[newEpoch] = nowMs

        activeGroups[groupId] = group.copy(activeEpoch = newEpoch)
        Log.i(TAG, "Rotated key for group $groupId to epoch $newEpoch (reason: $reason)")
        return newEpoch
    }

    // --- 4. Member Management ---

    /**
     * Removes a member from a group (Admin authorized only).
     * Automatically triggers key rotation to epoch E + 1.
     */
    fun removeMember(
        groupId: String,
        targetPeerId: String,
        adminPeerId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val group = activeGroups[groupId] ?: return false
        if (!group.adminPeerIds.contains(adminPeerId)) {
            Log.w(TAG, "Cannot remove member: $adminPeerId is not an admin of $groupId")
            return false
        }
        if (!group.memberPeerIds.contains(targetPeerId)) {
            Log.w(TAG, "Cannot remove member: $targetPeerId is not a member of $groupId")
            return false
        }
        if (targetPeerId == group.creatorPeerId) {
            Log.w(TAG, "Cannot remove group creator $targetPeerId")
            return false
        }

        val updatedMembers = group.memberPeerIds - targetPeerId
        val updatedSigningKeys = group.memberSigningKeys - targetPeerId
        val updatedAdmins = group.adminPeerIds - targetPeerId

        activeGroups[groupId] = group.copy(
            memberPeerIds = updatedMembers,
            memberSigningKeys = updatedSigningKeys,
            adminPeerIds = updatedAdmins
        )

        rotateGroupKey(groupId, reason = "MEMBER_REMOVED", nowMs = nowMs)
        return true
    }

    /**
     * Voluntary leave by a member.
     * Automatically triggers key rotation to epoch E + 1.
     */
    fun leaveGroup(
        groupId: String,
        memberPeerId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val group = activeGroups[groupId] ?: return false
        if (!group.memberPeerIds.contains(memberPeerId)) return false

        val updatedMembers = group.memberPeerIds - memberPeerId
        val updatedSigningKeys = group.memberSigningKeys - memberPeerId
        val updatedAdmins = group.adminPeerIds - memberPeerId

        activeGroups[groupId] = group.copy(
            memberPeerIds = updatedMembers,
            memberSigningKeys = updatedSigningKeys,
            adminPeerIds = updatedAdmins
        )

        if (updatedMembers.isNotEmpty()) {
            rotateGroupKey(groupId, reason = "MEMBER_LEFT", nowMs = nowMs)
        } else {
            // Last member left
            keyStore.removeGroupKeys(groupId)
            activeGroups.remove(groupId)
            notifyGroupsUpdated()
        }
        return true
    }

    // --- 5. Key Retrieval & Grace Period Semantics ---

    /**
     * Gets current active epoch index and secret key for a group.
     */
    fun getActiveEpochKey(groupId: String): Pair<Int, SecretKeySpec>? {
        val group = activeGroups[groupId] ?: return null
        val key = keyStore.getGroupKey(groupId, group.activeEpoch) ?: return null
        return Pair(group.activeEpoch, key)
    }

    /**
     * Key retrieval with 2-minute key grace window enforcement:
     * - Current epoch key: Always returned if member has it.
     * - Previous epoch key (epoch == activeEpoch - 1): Returned ONLY IF packet was created BEFORE rotation
     *   (packetTimestampMs <= rotationTimestampMs) AND current time is within 2 minutes of rotation (nowMs - rotationTimestampMs <= 120_000L).
     * - Older epoch keys or invalid packets: Returns null.
     */
    fun getGraceEpochKey(
        groupId: String,
        epoch: Int,
        packetTimestampMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): SecretKeySpec? {
        val group = activeGroups[groupId] ?: return null

        // 1. Current active epoch
        if (epoch == group.activeEpoch) {
            return keyStore.getGroupKey(groupId, epoch)
        }

        // 2. Grace period for previous epoch (activeEpoch - 1)
        if (epoch == group.activeEpoch - 1) {
            val rotationMs = rotationTimestamps[groupId]?.get(group.activeEpoch) ?: return null

            // Packet created after rotation MUST use new epoch key
            if (packetTimestampMs > rotationMs) {
                Log.w(TAG, "Rejecting previous epoch key: packet timestamp $packetTimestampMs is post-rotation $rotationMs")
                return null
            }

            // Grace window expired (> 2 minutes)
            if (nowMs - rotationMs > KEY_GRACE_WINDOW_MS) {
                Log.w(TAG, "Rejecting previous epoch key: 2-minute grace window expired")
                return null
            }

            return keyStore.getGroupKey(groupId, epoch)
        }

        return null
    }

    /**
     * Injects a group into active memory (for tests or DB restoration).
     */
    fun loadGroupForTesting(group: PrivateGroup) {
        activeGroups[group.groupId] = group
        notifyGroupsUpdated()
    }
}
