package com.gatekey.client.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import android.util.Log
import com.gatekey.client.data.model.ConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.StatusListener
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.core.keepVPNAlive
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

    // Service connection for calling stopVPN via AIDL
    private var vpnService: IOpenVPNServiceInternal? = null
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            vpnService = IOpenVPNServiceInternal.Stub.asInterface(service)
            Log.d(TAG, "OpenVPNService bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            serviceBound = false
            Log.d(TAG, "OpenVPNService unbound")
        }
    }

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

    private val _localIp = MutableStateFlow<String?>(null)
    val localIp: StateFlow<String?> = _localIp.asStateFlow()

    private val _remoteIp = MutableStateFlow<String?>(null)
    val remoteIp: StateFlow<String?> = _remoteIp.asStateFlow()

    private val _remotePort = MutableStateFlow<String?>(null)
    val remotePort: StateFlow<String?> = _remotePort.asStateFlow()

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
     * Bind - for embedded library, ensure status listeners are registered
     * and bind to the OpenVPNService for proper disconnect handling
     */
    fun bind() {
        registerListeners()
        bindToService()
    }

    private fun bindToService() {
        if (!serviceBound) {
            try {
                val intent = Intent(context, OpenVPNService::class.java)
                intent.action = OpenVPNService.START_SERVICE
                context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                serviceBound = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind to OpenVPNService", e)
            }
        }
    }

    /**
     * Unbind - cleanup listeners and service binding
     */
    fun unbind() {
        if (isListenerRegistered) {
            VpnStatus.removeStateListener(this)
            VpnStatus.removeByteCountListener(this)
            isListenerRegistered = false
        }
        if (serviceBound) {
            try {
                context.unbindService(serviceConnection)
                serviceBound = false
                vpnService = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unbind from OpenVPNService", e)
            }
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

            // Stop any existing VPN first to ensure clean state using proper stopVPN method
            // This prevents race conditions where an old process cleanup
            // interferes with the new process startup
            Log.d(TAG, "Ensuring clean VPN state before starting new connection")
            try {
                vpnService?.stopVPN(true) // true = replacing with new connection
                // Give existing process enough time to fully clean up
                Thread.sleep(500)
            } catch (e: Exception) {
                Log.d(TAG, "No existing VPN to stop: ${e.message}")
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

            // Ensure we're bound to the service for later disconnect
            bindToService()

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
            Log.d(TAG, "Disconnecting VPN")
            _connectionState.value = ConnectionState.DISCONNECTING

            // Mark the profile as disconnected in ProfileManager
            // This prevents auto-restart from trying to reconnect
            ProfileManager.setConntectedVpnProfileDisconnected(context)

            // Unschedule the keepVPNAlive job to prevent automatic reconnection
            keepVPNAlive.unscheduleKeepVPNAliveJobService(context)

            // Use the proper stopVPN method via AIDL if bound
            val service = vpnService
            if (service != null) {
                try {
                    Log.d(TAG, "Calling stopVPN(false) via AIDL")
                    service.stopVPN(false) // false = not replacing with new connection
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to call stopVPN via AIDL, falling back to stopService", e)
                    // Fall back to stopping the service
                    val stopIntent = Intent(context, OpenVPNService::class.java)
                    context.stopService(stopIntent)
                }
            } else {
                // No service bound, try to stop the service directly
                Log.d(TAG, "No service bound, using stopService")
                val stopIntent = Intent(context, OpenVPNService::class.java)
                context.stopService(stopIntent)
            }

            // Clear our local reference
            currentProfile = null

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
            false
        }
    }

    /**
     * Forcefully disconnect VPN - used when normal disconnect doesn't work
     */
    fun forceDisconnect(): Boolean {
        return try {
            Log.w(TAG, "Attempting forceful VPN disconnect")

            // Mark the profile as disconnected
            ProfileManager.setConntectedVpnProfileDisconnected(context)

            // Unschedule the keepVPNAlive job to prevent automatic reconnection
            keepVPNAlive.unscheduleKeepVPNAliveJobService(context)

            // Try to stop via AIDL first
            try {
                vpnService?.stopVPN(false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call stopVPN via AIDL", e)
            }

            // Also stop the service entirely
            val stopIntent = Intent(context, OpenVPNService::class.java)
            context.stopService(stopIntent)

            // Update our state
            _connectionState.value = ConnectionState.DISCONNECTED
            currentProfile = null

            // Clear IP info
            _localIp.value = null
            _remoteIp.value = null
            _remotePort.value = null
            _bytesIn.value = 0L
            _bytesOut.value = 0L

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forcefully disconnect", e)
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

        // Parse IP addresses from message (format: "status,localIP,remoteIP,port,...")
        if (level == ConnectionStatus.LEVEL_CONNECTED && !logmessage.isNullOrEmpty()) {
            val parts = logmessage.split(",")
            if (parts.size >= 4) {
                _localIp.value = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                _remoteIp.value = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
                _remotePort.value = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
            }
        } else if (newState == ConnectionState.DISCONNECTED) {
            // Clear IP info on disconnect
            _localIp.value = null
            _remoteIp.value = null
            _remotePort.value = null
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
