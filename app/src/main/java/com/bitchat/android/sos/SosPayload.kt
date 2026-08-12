package com.bitchat.android.sos

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.StringWriter
import java.nio.charset.StandardCharsets

data class SosPayload(
    @SerializedName("id") val id: String,
    @SerializedName("sender") val sender: String,
    @SerializedName("channel") val channel: String,
    @SerializedName("locationNote") val locationNote: String,
    @SerializedName("isCancel") val isCancel: Boolean = false,
    @SerializedName("originalSosId") val originalSosId: String? = null,
    @SerializedName("timestamp") val timestamp: Long
) {
    /**
     * Canonical encoding with deterministic field ordering.
     *
     * Fields are written in a fixed, explicit order using JsonWriter so that
     * the same logical payload always produces identical bytes.  This is
     * critical because the payload bytes become part of the Ed25519-signed
     * BitchatPacket — sender and receiver must agree on the exact bytes.
     *
     * Field order: id, sender, channel, locationNote, isCancel,
     * originalSosId, timestamp.
     */
    fun encode(): ByteArray {
        val sw = StringWriter()
        val jw = com.google.gson.stream.JsonWriter(sw)
        jw.beginObject()
        jw.name("id").value(id)
        jw.name("sender").value(sender)
        jw.name("channel").value(channel)
        jw.name("locationNote").value(locationNote)
        jw.name("isCancel").value(isCancel)
        jw.name("originalSosId").value(originalSosId)
        jw.name("timestamp").value(timestamp)
        jw.endObject()
        jw.close()
        return sw.toString().toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        private val gson = Gson()
        const val MAX_LOCATION_NOTE_CHARS = 60

        fun sanitizeLocationNote(input: String?): String {
            if (input.isNullOrBlank()) return "No location details provided"
            val cleaned = input.replace(Regex("[\\r\\n\\t\\u0000-\\u001F\\u007F-\\u009F]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (cleaned.isEmpty()) return "No location details provided"
            return cleaned.take(MAX_LOCATION_NOTE_CHARS)
        }

        fun decode(bytes: ByteArray): SosPayload? {
            return try {
                val json = String(bytes, StandardCharsets.UTF_8)
                val payload = gson.fromJson(json, SosPayload::class.java)
                if (payload == null || payload.id.isNullOrBlank() || payload.sender.isNullOrBlank() || payload.channel.isNullOrBlank()) {
                    null
                } else {
                    payload.copy(locationNote = sanitizeLocationNote(payload.locationNote))
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

