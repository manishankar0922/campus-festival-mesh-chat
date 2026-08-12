package com.bitchat.android

import android.app.Application
import com.bitchat.android.ui.theme.ThemePreferenceManager

/**
 * Main application class for Campus Festival Mesh Chat.
 *
 * PHASE 3: BLE-only festival mode — Nostr, Tor, and geohash internet
 * infrastructure are NOT initialized at startup.  The production
 * communication path is:
 *   UI → MessageRouter → UnifiedMeshService → BluetoothMeshService → BLE
 */
class BitchatApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Organizer Identity Manager
        com.bitchat.android.organizer.OrganizerIdentityManager.init(this)

        // Start the single process-wide power policy before transport components are constructed.
        com.bitchat.android.mesh.PowerManager.getInstance(this).start()

        // PHASE 3: Disabled — BLE-only festival mode (no Tor network at startup)
        // try {
        //     val torProvider = com.bitchat.android.net.ArtiTorManager.getInstance()
        //     torProvider.init(this)
        // } catch (_: Exception){}

        // PHASE 3: Disabled — BLE-only festival mode (no Nostr relay directory at startup)
        // com.bitchat.android.nostr.RelayDirectory.initialize(this)

        // PHASE 3: Disabled — BLE-only festival mode (no Nostr location notes at startup)
        // try { com.bitchat.android.nostr.LocationNotesInitializer.initialize(this) } catch (_: Exception) { }

        // Initialize favorites persistence early so MessageRouter can use it on startup
        try {
            com.bitchat.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Restore private conversations before background transports can deliver new messages.
        // AppStateStore merges any in-flight arrivals by message ID, so startup cannot replace
        // newer transport state with an older database snapshot.
        try {
            com.bitchat.android.services.AppStateStore.initializeConversationPersistence(this)
        } catch (_: Exception) { }

        // PHASE 3: Disabled — BLE-only festival mode (no Nostr identity warm-up at startup)
        // try {
        //     com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        // } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize chat UI mode (matrix transcript vs bubbles)
        com.bitchat.android.ui.theme.ChatUiModeManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { com.bitchat.android.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // PHASE 3: Disabled — BLE-only festival mode (no Geohash registries at startup)
        // try {
        //     com.bitchat.android.nostr.GeohashAliasRegistry.initialize(this)
        //     com.bitchat.android.nostr.GeohashConversationRegistry.initialize(this)
        // } catch (_: Exception) { }

        // PHASE 3: Disabled — BLE-only festival mode (no Nostr relay connections at startup)
        // try { com.bitchat.android.nostr.NostrBackgroundRuntime.initialize(this) } catch (_: Exception) { }

        // Initialize mesh service preferences
        try { com.bitchat.android.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        try { com.bitchat.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }
    }
}
