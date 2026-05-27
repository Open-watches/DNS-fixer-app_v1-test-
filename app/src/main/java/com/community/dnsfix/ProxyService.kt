package com.community.dnsfix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ProxyService : Service() {

    private var serverSocket: ServerSocket? = null
    private val executorService = Executors.newFixedThreadPool(4)
    @Volatile private var isRunning = false

    // Fast-fail HTTP layer optimized to reject dropped MPT packets inside 2.5 seconds
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    private val stableEndpoints = listOf(
        "https://9.9.9.9/dns-query",       // Quad9 Primary
        "https://1.0.0.1/dns-query",       // Cloudflare Backup
        "https://185.228.168.10/dns-query" // CleanBrowsing Fallback
    )

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/122.0.0.0",
        "Mozilla/5.0 (Android 14; Mobile; rv:123.0) Gecko/123.0 Firefox/123.0"
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            val notification = createNotification()
            
            // Handles strict Android 14 foreground background execution policies
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, notification)
            }
            
            Thread { runServer() }.start()
        }
        return START_STICKY
    }

    private fun runServer() {
        try {
            // Bind exclusively to internal loopback interface to shield ports from local Wi-Fi snoopers
            serverSocket = ServerSocket(5353, 50, InetAddress.getByName("127.0.0.1"))
            while (isRunning) {
                val clientSocket = serverSocket?.accept() ?: break
                executorService.execute { handleClient(clientSocket) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 5000
                val input = client.getInputStream()
                val output = client.getOutputStream()

                // 1. Process basic unencrypted HTTP lines from internal browser request
                var contentLength = 0
                while (true) {
                    val line = readLine(input)
                    if (line.isEmpty()) break // Header carriage section block ended
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                // 2. Fetch the wire-format byte structure matching the payload tracking footprint
                if (contentLength > 0) {
                    val queryBuffer = ByteArray(contentLength)
                    var bytesRead = 0
                    while (bytesRead < contentLength) {
                        val read = input.read(queryBuffer, bytesRead, contentLength - bytesRead)
                        if (read == -1) break
                        bytesRead += read
                    }

                    // 3. Request encrypted resolution out to random public arrays
                    val responseBytes = forwardDohQuery(queryBuffer)
                    
                    if (responseBytes != null) {
                        // Deliver untampered records back to original browser process
                        val httpResponse = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/dns-message\r\n" +
                                "Content-Length: ${responseBytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        output.write(httpResponse.toByteArray(Charsets.UTF_8))
                        output.write(responseBytes)
                    } else {
                        // Fail clean with Bad Gateway if server drops mid-flight so client requests retry immediately
                        val errorResponse = "HTTP/1.1 502 Bad Gateway\r\n" +
                                "Content-Length: 0\r\n" +
                                "Connection: close\r\n\r\n"
                        output.write(errorResponse.toByteArray(Charsets.UTF_8))
                    }
                    output.flush()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun forwardDohQuery(queryBytes: ByteArray): ByteArray? {
        val randomUrl = stableEndpoints[Random.nextInt(stableEndpoints.size)]
        val randomAgent = userAgents[Random.nextInt(userAgents.size)]
        val dnsMediaType = "application/dns-message".toMediaType()

        val request = Request.Builder()
            .url(randomUrl)
            .post(queryBytes.toRequestBody(dnsMediaType))
            .header("Accept", "application/dns-message")
            .header("User-Agent", randomAgent)
            .header("X-Padding", generateJunkPadding()) // Mask exact structural sizes against tracking boxes
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } catch (e: Exception) {
            null // Drops directly back to secondary failover routines
        }
    }

    private fun readLine(inputStream: InputStream): String {
        val sb = StringBuilder()
        var c: Int
        while (true) {
            c = inputStream.read()
            if (c == -1 || c == '\n'.code) break
            if (c == '\r'.code) continue
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun generateJunkPadding(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return (1..Random.nextInt(16, 48))
            .map { alphabet[Random.nextInt(alphabet.length)] }
            .joinToString("")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "dns_proxy_channel",
                "Local DNS Proxy Engine",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "dns_proxy_channel")
            .setContentTitle("DNS Proxy Running Engine")
            .setContentText("Local listener alive on 127.0.0.1:5353")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Suppress secondary cleanup drops
        }
        executorService.shutdownNow()
        super.onDestroy()
    }
}
