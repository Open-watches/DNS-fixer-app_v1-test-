package com.community.dnsfix

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.community.dnsfix.ui.MainScreen
import com.community.dnsfix.ui.ProtectionMode
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val isConnected = mutableStateOf(false)
    private val selectedMode = mutableStateOf(ProtectionMode.BPS)
    
    // Live ping state: starts at null (displayed as "--" in your UI)
    private val livePingMs = mutableStateOf<Int?>(null)
    private var diagnosticJob: Job? = null

    // Reusable, optimized HTTP client with short timeouts
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme {
                val connected by remember { isConnected }
                val mode by remember { selectedMode }
                val ping by remember { livePingMs }
                
                MainScreen(
                    isConnected = connected,
                    currentMode = mode,
                    // Pass the real live ping value, or fallback to 0 if null
                    pingMs = ping ?: 0, 
                    onModeChange = { newMode ->
                        if (!connected) selectedMode.value = newMode
                    },
                    onToggleConnection = { targetState ->
                        handleConnectionToggle(targetState)
                    }
                )
            }
        }
    }

    private fun handleConnectionToggle(start: Boolean) {
        if (start) {
            if (selectedMode.value == ProtectionMode.VPN) {
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) {
                    startActivityForResult(vpnIntent, 1001)
                } else {
                    executeVpnService()
                }
            } else {
                executeBpsMode()
            }
        } else {
            terminateConnections()
        }
    }

    private fun executeBpsMode() {
        isConnected.value = true
        startLiveNetworkCheck()
    }

    private fun executeVpnService() {
        val intent = Intent(this, BypassVpnService::class.java).apply {
            action = BypassVpnService.ACTION_CONNECT
        }
        startService(intent)
        isConnected.value = true
        startLiveNetworkCheck()
    }

    private fun terminateConnections() {
        val intent = Intent(this, BypassVpnService::class.java).apply {
            action = BypassVpnService.ACTION_DISCONNECT
        }
        startService(intent)
        isConnected.value = false
        stopLiveNetworkCheck()
    }

    // Starts the background loop to poll a real server
    private fun startLiveNetworkCheck() {
        stopLiveNetworkCheck() // Clear any existing loops first
        
        diagnosticJob = lifecycleScope.launch(Dispatchers.IO) {
            // We use a real hostname. If your DNS-fixer works, this will resolve.
            // generate_204 returns a completely empty body, making it incredibly fast.
            val request = Request.Builder()
                .url("https://www.google.com/generate_204")
                .header("User-Agent", "DNS-Fixer-Diagnostic")
                .build()

            while (isActive && isConnected.value) {
                val startTime = System.currentTimeMillis()
                try {
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val duration = (System.currentTimeMillis() - startTime).toInt()
                            // Update the state on the main thread safely
                            withContext(Dispatchers.Main) {
                                livePingMs.value = duration
                            }
                        } else {
                            resetPingUi()
                        }
                    }
                } catch (e: IOException) {
                    // Network dropped, DNS failed to resolve, or VPN blocked traffic
                    resetPingUi()
                }
                // Wait 3 seconds before checking again
                delay(3000)
            }
        }
    }

    private fun stopLiveNetworkCheck() {
        diagnosticJob?.cancel()
        livePingMs.value = null
    }

    private suspend fun resetPingUi() {
        withContext(Dispatchers.Main) {
            livePingMs.value = null // This forces your UI to show "--"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            executeVpnService()
        }
    }

    override fun onDestroy() {
        stopLiveNetworkCheck()
        super.onDestroy()
    }
}
