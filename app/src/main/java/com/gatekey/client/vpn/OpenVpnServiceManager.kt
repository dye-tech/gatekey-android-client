package com.gatekey.client.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.gatekey.client.data.model.ConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.StatusListener
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the embedded OpenVPN library for VPN connections.
 * Uses OpenVPN3 core library directly without requiring external app.
 */
@Singleton
class OpenVpnServiceManager @Inject constructor(
    @ApplicationContext private val context: Context
) : VpnStatus.StateListener, VpnStatus.ByteCountListener {

    companion object {
        private const val TAG = "OpenVpnServiceManager"
        const val VPN_PERMISSION_REQUEST_CODE = 7002

        // Action constants from OpenVPNService (they are private there)
        private const val PAUSE_VPN = "de.blinkt.openvpn.PAUSE_VPN"
        private const val RESUME_VPN = "de.blinkt.openvpn.RESUME_VPN"
    }

    private var currentProfile: VpnProfile? = null
    private var isListenerRegistered = false

    // StatusListener for IPC with the :openvpn process
    private val statusListener = StatusListener()

    private val _isAvailable = MutableStateFlow(true) // Always available since embedded
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _needsPermission = MutableStateFlow<Intent?>(null)
    val needsPermission: StateFlow<Intent?> = _needsPermission.asStateFlow()

    private val _bytesIn = MutableStateFlow(0L)
    val bytesIn: StateFlow<Long> = _bytesIn.asStateFlow()

    private val _bytesOut = MutableStateFlow(0L)
    val bytesOut: StateFlow<Long> = _bytesOut.asStateFlow()

    init {
        // Initialize StatusListener for IPC with the :openvpn process
        // This binds to OpenVPNStatusService which receives state updates via AIDL
        statusListener.init(context)

        // Register for VPN status updates
        registerListeners()
    }

    private fun registerListeners() {
        if (!isListenerRegistered) {
            VpnStatus.addStateListener(this)
            VpnStatus.addByteCountListener(this)
            isListenerRegistered = true
        }
    }

    /**
     * Bind - for embedded library, just ensure status listeners are registered
     */
    fun bind() {
        registerListeners()
    }

    /**
     * Unbind - cleanup listeners
     */
    fun unbind() {
        if (isListenerRegistered) {
            VpnStatus.removeStateListener(this)
            VpnStatus.removeByteCountListener(this)
            isListenerRegistered = false
        }
    }

    /**
     * Called when permission has been granted
     */
    fun onPermissionGranted() {
        _needsPermission.value = null
    }

    /**
     * OpenVPN is always "installed" since it's embedded
     */
    fun isOpenVpnInstalled(): Boolean = true

    /**
     * No install needed for embedded library
     */
    fun getInstallIntent(): Intent {
        // This shouldn't be called for embedded library
        return Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("https://gatekey.io")
        }
    }

    /**
     * Prepare VPN service - returns Intent if permission needed, null otherwise
     */
    fun prepareVpnService(): Intent? {
        return VpnService.prepare(context)
    }

    /**
     * Start VPN with inline config
     */
    fun startVpn(config: String): Boolean {
        return try {
            _connectionState.value = ConnectionState.CONNECTING

            // Always stop any existing VPN first to ensure clean state
            // This prevents race conditions where an old process cleanup
            // interferes with the new process startup
            Log.d(TAG, "Ensuring clean VPN state before starting new connection")
            val stopIntent = Intent(context, OpenVPNService::class.java)
            stopIntent.action = OpenVPNService.DISCONNECT_VPN
            try {
                context.startService(stopIntent)
                // Give existing process enough time to fully clean up
                // 500ms is needed for the native OpenVPN process to terminate
                Thread.sleep(500)
            } catch (e: Exception) {
                Log.d(TAG, "No existing VPN service to stop: ${e.message}")
            }

            // Parse the OpenVPN config
            val configParser = ConfigParser()
            configParser.parseConfig(StringReader(config))

            val profile = configParser.convertProfile()
            profile.mName = "GateKey VPN"
            profile.mProfileCreator = context.packageName

            // Save the profile
            ProfileManager.setTemporaryProfile(context, profile)
            currentProfile = profile

            // Start the VPN with all required parameters
            VPNLaunchHelper.startOpenVpn(profile, context, "GateKey connection", true)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            _connectionState.value = ConnectionState.ERROR
            _statusMessage.value = e.message ?: "Failed to parse config"
            false
        }
    }

    /**
     * Disconnect VPN
     */
    fun disconnect(): Boolean {
        return try {
            _connectionState.value = ConnectionState.DISCONNECTING

            val intent = Intent(context, OpenVPNService::class.java)
            intent.action = OpenVPNService.DISCONNECT_VPN
            context.startService(intent)

            // Don't call setTemporaryProfile with null - it doesn't handle null
            // Just clear our local reference
            currentProfile = null

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
            false
        }
    }

    /**
     * Pause VPN
     */
    fun pause(): Boolean {
        return try {
            val intent = Intent(context, OpenVPNService::class.java)
            intent.action = PAUSE_VPN
            context.startService(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause", e)
            false
        }
    }

    /**
     * Resume VPN
     */
    fun resume(): Boolean {
        return try {
            val intent = Intent(context, OpenVPNService::class.java)
            intent.action = RESUME_VPN
            context.startService(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume", e)
            false
        }
    }

    // VpnStatus.StateListener implementation
    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus?,
        intent: Intent?
    ) {
        Log.d(TAG, "VPN state update: state=$state, level=$level, message=$logmessage")

        val newState = when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> ConnectionState.CONNECTED
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            ConnectionStatus.LEVEL_START,
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> ConnectionState.CONNECTING
            ConnectionStatus.LEVEL_NOTCONNECTED -> ConnectionState.DISCONNECTED
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_AUTH_FAILED,
            ConnectionStatus.LEVEL_VPNPAUSED -> ConnectionState.DISCONNECTED
            else -> _connectionState.value
        }

        _connectionState.value = newState
        _statusMessage.value = logmessage ?: ""
    }

    override fun setConnectedVPN(uuid: String?) {
        Log.d(TAG, "Connected VPN UUID: $uuid")
    }

    // VpnStatus.ByteCountListener implementation
    override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
        _bytesIn.value = inBytes
        _bytesOut.value = outBytes
    }
}
