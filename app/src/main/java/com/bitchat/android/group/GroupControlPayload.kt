package com.bitchat.android.group

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets

enum class GroupControlType(val value: String) {
    INVITE("INVITE"),
    GROUP_ACCEPT("GROUP_ACCEPT"),
    REMOVE("REMOVE"),
    LEAVE("LEAVE"),
    KEY_DISTRIBUTION("KEY_DISTRIBUTION");

    companion object {
        fun fromValue(value: String?): GroupControlType? {
            return values().find { it.value.equals(value, ignoreCase = true) }
        }
    }
}

sealed class GroupControlPayload {
    abstract val controlType: GroupControlType
    abstract val timestampMs: Long

    /**
     * Canonical deterministic serialization using JsonWriter with hardcoded key ordering.
     */
    abstract fun encode(): ByteArray

    data class Invite(
        val invitationId: String,
        val groupId: String,
        val groupName: String,
        val creatorPeerId: String,
        val inviterPeerId: String,
        val invitationEpoch: Int,
        val encryptedGroupKey: String,
        override val timestampMs: Long,
        val expiresAtMs: Long
    ) : GroupControlPayload() {
        override val controlType: GroupControlType = GroupControlType.INVITE

        override fun encode(): ByteArray {
            val sw = StringWriter()
            val jw = JsonWriter(sw)
            jw.beginObject()
            jw.name("controlType").value(controlType.value)
            jw.name("invitationId").value(invitationId)
            jw.name("groupId").value(groupId)
            jw.name("groupName").value(groupName)
            jw.name("creatorPeerId").value(creatorPeerId)
            jw.name("inviterPeerId").value(inviterPeerId)
            jw.name("invitationEpoch").value(invitationEpoch)
            jw.name("encryptedGroupKey").value(encryptedGroupKey)
            jw.name("timestampMs").value(timestampMs)
            jw.name("expiresAtMs").value(expiresAtMs)
            jw.endObject()
            jw.close()
            return sw.toString().toByteArray(StandardCharsets.UTF_8)
        }
    }

    data class GroupAccept(
        val invitationId: String,
        val groupId: String,
        val acceptorPeerId: String,
        val acceptorSigningPubKeyB64: String,
        override val timestampMs: Long
    ) : GroupControlPayload() {
        override val controlType: GroupControlType = GroupControlType.GROUP_ACCEPT

        override fun encode(): ByteArray {
            val sw = StringWriter()
            val jw = JsonWriter(sw)
            jw.beginObject()
            jw.name("controlType").value(controlType.value)
            jw.name("invitationId").value(invitationId)
            jw.name("groupId").value(groupId)
            jw.name("acceptorPeerId").value(acceptorPeerId)
            jw.name("acceptorSigningPubKeyB64").value(acceptorSigningPubKeyB64)
            jw.name("timestampMs").value(timestampMs)
            jw.endObject()
            jw.close()
            return sw.toString().toByteArray(StandardCharsets.UTF_8)
        }
    }

    data class Remove(
        val groupId: String,
        val targetPeerId: String,
        val adminPeerId: String,
        val newEpoch: Int,
        override val timestampMs: Long
    ) : GroupControlPayload() {
        override val controlType: GroupControlType = GroupControlType.REMOVE

        override fun encode(): ByteArray {
            val sw = StringWriter()
            val jw = JsonWriter(sw)
            jw.beginObject()
            jw.name("controlType").value(controlType.value)
            jw.name("groupId").value(groupId)
            jw.name("targetPeerId").value(targetPeerId)
            jw.name("adminPeerId").value(adminPeerId)
            jw.name("newEpoch").value(newEpoch)
            jw.name("timestampMs").value(timestampMs)
            jw.endObject()
            jw.close()
            return sw.toString().toByteArray(StandardCharsets.UTF_8)
        }
    }

    data class Leave(
        val groupId: String,
        val leavingPeerId: String,
        override val timestampMs: Long
    ) : GroupControlPayload() {
        override val controlType: GroupControlType = GroupControlType.LEAVE

        override fun encode(): ByteArray {
            val sw = StringWriter()
            val jw = JsonWriter(sw)
            jw.beginObject()
            jw.name("controlType").value(controlType.value)
            jw.name("groupId").value(groupId)
            jw.name("leavingPeerId").value(leavingPeerId)
            jw.name("timestampMs").value(timestampMs)
            jw.endObject()
            jw.close()
            return sw.toString().toByteArray(StandardCharsets.UTF_8)
        }
    }

    data class KeyDistribution(
        val groupId: String,
        val epoch: Int,
        val encryptedGroupKey: String,
        override val timestampMs: Long
    ) : GroupControlPayload() {
        override val controlType: GroupControlType = GroupControlType.KEY_DISTRIBUTION

        override fun encode(): ByteArray {
            val sw = StringWriter()
            val jw = JsonWriter(sw)
            jw.beginObject()
            jw.name("controlType").value(controlType.value)
            jw.name("groupId").value(groupId)
            jw.name("epoch").value(epoch)
            jw.name("encryptedGroupKey").value(encryptedGroupKey)
            jw.name("timestampMs").value(timestampMs)
            jw.endObject()
            jw.close()
            return sw.toString().toByteArray(StandardCharsets.UTF_8)
        }
    }

    companion object {
        const val MAX_CONTROL_PAYLOAD_BYTES = 4096

        fun decode(bytes: ByteArray): GroupControlPayload? {
            if (bytes.isEmpty() || bytes.size > MAX_CONTROL_PAYLOAD_BYTES) return null
            return try {
                val jsonStr = String(bytes, StandardCharsets.UTF_8)
                val json = JsonParser.parseString(jsonStr).asJsonObject
                val controlTypeStr = json.get("controlType")?.asString ?: return null
                val type = GroupControlType.fromValue(controlTypeStr) ?: return null

                when (type) {
                    GroupControlType.INVITE -> {
                        Invite(
                            invitationId = json.get("invitationId").asString,
                            groupId = json.get("groupId").asString,
                            groupName = json.get("groupName").asString,
                            creatorPeerId = json.get("creatorPeerId").asString,
                            inviterPeerId = json.get("inviterPeerId").asString,
                            invitationEpoch = json.get("invitationEpoch").asInt,
                            encryptedGroupKey = json.get("encryptedGroupKey").asString,
                            timestampMs = json.get("timestampMs").asLong,
                            expiresAtMs = json.get("expiresAtMs").asLong
                        )
                    }
                    GroupControlType.GROUP_ACCEPT -> {
                        GroupAccept(
                            invitationId = json.get("invitationId").asString,
                            groupId = json.get("groupId").asString,
                            acceptorPeerId = json.get("acceptorPeerId").asString,
                            acceptorSigningPubKeyB64 = json.get("acceptorSigningPubKeyB64").asString,
                            timestampMs = json.get("timestampMs").asLong
                        )
                    }
                    GroupControlType.REMOVE -> {
                        Remove(
                            groupId = json.get("groupId").asString,
                            targetPeerId = json.get("targetPeerId").asString,
                            adminPeerId = json.get("adminPeerId").asString,
                            newEpoch = json.get("newEpoch").asInt,
                            timestampMs = json.get("timestampMs").asLong
                        )
                    }
                    GroupControlType.LEAVE -> {
                        Leave(
                            groupId = json.get("groupId").asString,
                            leavingPeerId = json.get("leavingPeerId").asString,
                            timestampMs = json.get("timestampMs").asLong
                        )
                    }
                    GroupControlType.KEY_DISTRIBUTION -> {
                        KeyDistribution(
                            groupId = json.get("groupId").asString,
                            epoch = json.get("epoch").asInt,
                            encryptedGroupKey = json.get("encryptedGroupKey").asString,
                            timestampMs = json.get("timestampMs").asLong
                        )
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
