package com.bitchat.android.sos

import java.util.concurrent.ConcurrentHashMap

data class ActiveSosEntry(
    val sosId: String,
    val senderPeerId: String,
    val senderNickname: String,
    val channel: String,
    val locationNote: String,
    val timestampMs: Long
)

object ActiveSosManager {
    const val ACTIVE_LIFETIME_MS = 15 * 60 * 1000L // 15 minutes
    const val OUTGOING_RATE_LIMIT_MS = 60 * 1000L // 60 seconds

    @Volatile
    private var lastOutgoingSosMs = 0L

    // Map senderPeerId -> ActiveSosEntry
    private val activeEntries = ConcurrentHashMap<String, ActiveSosEntry>()

    fun canSendOutgoingSos(nowMs: Long = System.currentTimeMillis()): Boolean {
        return (nowMs - lastOutgoingSosMs) >= OUTGOING_RATE_LIMIT_MS
    }

    fun recordOutgoingSos(nowMs: Long = System.currentTimeMillis()) {
        lastOutgoingSosMs = nowMs
    }

    fun resetRateLimitForTesting() {
        lastOutgoingSosMs = 0L
    }

    fun getActiveSos(senderPeerId: String, nowMs: Long = System.currentTimeMillis()): ActiveSosEntry? {
        val entry = activeEntries[senderPeerId] ?: return null
        if (nowMs - entry.timestampMs > ACTIVE_LIFETIME_MS) {
            activeEntries.remove(senderPeerId)
            return null
        }
        return entry
    }

    fun getAllActiveSos(nowMs: Long = System.currentTimeMillis()): List<ActiveSosEntry> {
        val result = mutableListOf<ActiveSosEntry>()
        val iterator = activeEntries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (nowMs - entry.timestampMs > ACTIVE_LIFETIME_MS) {
                iterator.remove()
            } else {
                result.add(entry)
            }
        }
        return result
    }

    fun processIncomingSos(entry: ActiveSosEntry, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (nowMs - entry.timestampMs > ACTIVE_LIFETIME_MS) return false
        activeEntries[entry.senderPeerId] = entry
        return true
    }

    fun processIncomingCancel(senderPeerId: String, originalSosId: String?): ActiveSosEntry? {
        val existing = activeEntries[senderPeerId] ?: return null
        if (originalSosId == null || existing.sosId == originalSosId) {
            activeEntries.remove(senderPeerId)
            return existing
        }
        return null
    }

    fun clearAllForTesting() {
        activeEntries.clear()
        lastOutgoingSosMs = 0L
    }
}
