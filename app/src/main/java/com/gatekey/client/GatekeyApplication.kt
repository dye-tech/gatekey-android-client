package com.gatekey.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import de.blinkt.openvpn.core.GlobalPreferences
import de.blinkt.openvpn.core.OpenVPNService

@HiltAndroidApp
class GatekeyApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // Initialize OpenVPN global preferences early (before Hilt init)
        // This must happen in all processes including :openvpn
        GlobalPreferences.setInstance(false, false, false)
    }

    override fun onCreate() {
        super.onCreate()
        // Create notification channels in all processes - OpenVPN service
        // runs in :openvpn process and needs these channels available
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // GateKey VPN channel
            val vpnChannel = NotificationChannel(
                VPN_NOTIFICATION_CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status notifications"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(vpnChannel)

            // OpenVPN background channel (for persistent connection notification)
            val bgChannel = NotificationChannel(
                OpenVPNService.NOTIFICATION_CHANNEL_BG_ID,
                "VPN Background",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background VPN connection status"
                enableLights(false)
                lightColor = Color.DKGRAY
            }
            notificationManager.createNotificationChannel(bgChannel)

            // OpenVPN status change channel
            val statusChannel = NotificationChannel(
                OpenVPNService.NOTIFICATION_CHANNEL_NEWSTATUS_ID,
                "VPN Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status changes"
                enableLights(true)
                lightColor = Color.BLUE
            }
            notificationManager.createNotificationChannel(statusChannel)

            // OpenVPN user request channel (for 2FA, etc.)
            val userReqChannel = NotificationChannel(
                OpenVPNService.NOTIFICATION_CHANNEL_USERREQ_ID,
                "VPN User Requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "VPN authentication requests"
                enableVibration(true)
                lightColor = Color.CYAN
            }
            notificationManager.createNotificationChannel(userReqChannel)
        }
    }

    companion object {
        const val VPN_NOTIFICATION_CHANNEL_ID = "gatekey_vpn_channel"
        const val VPN_NOTIFICATION_ID = 1001
    }
}
