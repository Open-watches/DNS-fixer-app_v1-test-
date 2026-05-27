package com.community.dnsfix

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException

class BypassVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        const val ACTION_CONNECT = "com.community.dnsfix.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.community.dnsfix.ACTION_DISCONNECT"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startTunnel()
            ACTION_DISCONNECT -> stopTunnel()
            else -> stopTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        if (vpnInterface != null) return
        
        try {
            val builder = Builder()
                .setSession("VpnTunnelMode")
                .setMtu(1500)
                // Assign local isolated routing pool endpoints
                .addAddress("10.10.0.1", 24)
                // Direct custom secure fallback upstream definitions
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                // Intercept global layout routing layers
                .addRoute("0.0.0.0", 0)

            vpnInterface = builder.establish()
            Log.i("VpnServiceEngine", "System Layer-3 Tunnel Interface bound and established.")
        } catch (e: Exception) {
            Log.e("VpnServiceEngine", "Failed to compile interface descriptor mapping", e)
            stopTunnel()
        }
    }

    private fun stopTunnel() {
        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            Log.e("VpnServiceEngine", "Exception raised when closing tunnel instance", e)
        } finally {
            vpnInterface = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}
