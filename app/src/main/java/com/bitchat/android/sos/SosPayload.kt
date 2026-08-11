package com.bitchat.android.sos

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
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
    fun encode(): ByteArray {
        val json = gson.toJson(this)
        return json.toByteArray(StandardCharsets.UTF_8)
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
