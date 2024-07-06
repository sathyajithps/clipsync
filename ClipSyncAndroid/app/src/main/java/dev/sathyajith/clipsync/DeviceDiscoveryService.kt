package dev.sathyajith.clipsync

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.DiscoveryListener
import android.net.nsd.NsdManager.RegistrationListener
import android.net.nsd.NsdManager.ResolveListener
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.MulticastLock
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Scanner
import java.util.concurrent.Executors


private const val PORT = 6942

class DeviceDiscoveryService : Service() {
    private lateinit var mWifiMulticastLock: MulticastLock
    private var mClipboardManager: ClipboardManager? = null
    private var mNsdManager: NsdManager? = null
    private var mNsdDiscoveryListener: DiscoveryListener? = null
    private var mNsdRegistrationListener: RegistrationListener? = null
    private var mHttpServer: HttpServer? = null
    private var mIsServiceStarted = false
    private var mPingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ServiceActions.START.name -> startService()
                ServiceActions.STOP.name -> stopService()
                else -> {}
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        val restartServiceIntent =
            Intent(applicationContext, DeviceDiscoveryService::class.java).also {
                it.setPackage(packageName)
            }
        val restartServicePendingIntent: PendingIntent = PendingIntent.getService(
            this,
            1,
            restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        applicationContext.getSystemService(Context.ALARM_SERVICE)
        val alarmService: AlarmManager =
            applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent
        )
    }


    override fun onCreate() {
        super.onCreate()

        val notification = createNotification()
        startForeground(1, notification)
    }

    private fun startService() {
        if (mIsServiceStarted) return

        if (mClipboardManager == null) {
            mClipboardManager =
                applicationContext.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        }

        startServer()

        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        mWifiMulticastLock = wifiManager.createMulticastLock("CLIP_SYNC")
        mWifiMulticastLock.setReferenceCounted(false)
        mWifiMulticastLock.acquire()


        if (mNsdManager == null) {
            mNsdManager = applicationContext.getSystemService(NSD_SERVICE) as NsdManager
        }

        val serviceInfo = NsdServiceInfo()
        serviceInfo.port = PORT
        serviceInfo.serviceType = "_clipsync._tcp."
        serviceInfo.serviceName = "android"

        if (mNsdRegistrationListener != null) {
            mNsdManager?.unregisterService(mNsdRegistrationListener)
        }

        mNsdRegistrationListener = object : RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                // TODO: Restart service
                Log.i(CST, "onRegistrationFailed")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                // TODO: Retry
                Log.v(CST, "onUnregistrationFailed")
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
            }
        }

        mNsdManager!!.registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            mNsdRegistrationListener
        )

        val nsdResolveListener = object : ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                // TODO: Log to observability
                Log.i(CST, "onResolveFailed")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                @Suppress("DEPRECATION")
                if (serviceInfo != null && !serviceInfo.host.equals(InetAddress.getLoopbackAddress())) {
                    laptopIp = "${serviceInfo.host.hostAddress}:${serviceInfo.port}"
                    pingLaptop()
                    Log.i(CST, "onServiceResolved $serviceInfo")
                }
            }
        }

        if (mNsdDiscoveryListener != null) {
            mNsdManager?.stopServiceDiscovery(mNsdDiscoveryListener)
        }

        mNsdDiscoveryListener = object : DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                // TODO: Restart service
                Log.i(CST, "onStartDiscoveryFailed")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                // TODO: Retry
                Log.i(CST, "onStopDiscoveryFailed")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
            }

            override fun onDiscoveryStopped(serviceType: String?) {
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo?.serviceName == "android") {
                    return
                }
                Log.i(CST, "onServiceFound $serviceInfo")
                @Suppress("DEPRECATION")
                mNsdManager!!.resolveService(serviceInfo, nsdResolveListener)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Log.i(CST, "onServiceLost $serviceInfo")
            }
        }

        mNsdManager?.discoverServices(
            "_clipsync._tcp",
            NsdManager.PROTOCOL_DNS_SD,
            mNsdDiscoveryListener
        )

        mIsServiceStarted = true

        setServiceState(this, ServiceState.STARTED)
    }

    private fun stopService() {
        try {
            if (mHttpServer != null) {
                mHttpServer?.stop(1)
            }

            if (mNsdDiscoveryListener != null) {
                mNsdManager?.stopServiceDiscovery(mNsdDiscoveryListener)
                mNsdDiscoveryListener = null
            }
            if (mNsdRegistrationListener != null) {
                mNsdManager?.unregisterService(mNsdRegistrationListener)
                mNsdRegistrationListener = null
            }
            mWifiMulticastLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            Log.i(CST, "error")
        }

        mIsServiceStarted = false
        setServiceState(this, ServiceState.STOPPED)
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "CLIP_SYNC_ID"

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            notificationChannelId,
            "Clip Sync Channel",
            NotificationManager.IMPORTANCE_HIGH
        ).let {
            it.description = "Channel for clip sync service"
            it.enableLights(true)
            it.lightColor = Color.RED
            it.enableVibration(true)
            it.vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
            it
        }
        notificationManager.createNotificationChannel(channel)

        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let {
                it.action = COPY_FROM_CB
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }

        val builder: Notification.Builder = Notification.Builder(
            this,
            notificationChannelId
        )

        return builder
            .setContentTitle("Clip Sync")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setTicker("Ticker text")
            .build()
    }

    // https://medium.com/hacktive-devs/creating-a-local-http-server-on-android-49831fbad9ca
    private fun startServer() {
        try {
            mHttpServer = HttpServer.create(InetSocketAddress(PORT), 0)
            mHttpServer!!.executor = Executors.newCachedThreadPool()

            mHttpServer!!.createContext("/", handler)
            mHttpServer!!.start()
        } catch (e: IOException) {
            e.printStackTrace()
        }

    }

    private val handler = HttpHandler { httpExchange ->
        run {
            if (httpExchange!!.requestMethod == "POST") {
                if (httpExchange.requestHeaders["PING"]?.first() == "PING") {
                    Log.i(CST, "Received PING!")
                    val pingedLaptopIp = httpExchange.requestHeaders["IP"]?.first().toString()
                    Log.i(CST, "Received laptop ip from PING: $pingedLaptopIp")
                    if (laptopIp == null) {
                        laptopIp = pingedLaptopIp
                        if (mPingJob?.isCancelled == true || mPingJob?.isActive == false) {
                            pingLaptop()
                        }
                    }
                    sendResponse(httpExchange, 200, "PONG")
                    return@run
                }

                val inputStream = httpExchange.requestBody
                val requestBody = streamToString(inputStream)

                try {
                    mClipboardManager!!.setPrimaryClip(
                        ClipData.newPlainText(
                            "Copied From Laptop",
                            requestBody
                        )
                    )

                    Log.i(CST, "Pasted to clipboard successfully")

                    sendResponse(httpExchange, 200, "{\"status\":\"OK\"}")

                } catch (e: Exception) {
                    Log.i(CST, "Error while setting clipboard data: $e")

                    sendResponse(
                        httpExchange,
                        500,
                        "{\"status\":\"Could not set the clipboard data\"}"
                    )
                }
            } else {
                sendResponse(httpExchange, 400, "{\"error\":\"Only POST Requests are allowed\"}")
            }
        }
    }

    private fun streamToString(inputStream: InputStream): String {
        val s = Scanner(inputStream).useDelimiter("\\A")
        return if (s.hasNext()) s.next() else ""
    }

    private fun sendResponse(httpExchange: HttpExchange, statusCode: Int, responseText: String) {
        httpExchange.sendResponseHeaders(statusCode, responseText.length.toLong())
        val os = httpExchange.responseBody
        os.write(responseText.toByteArray())
        os.close()
    }

    private fun getLocalIpPort(): String {
        var ipPort = ""
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val enumIpAddr = en.nextElement().inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        ipPort = inetAddress.getHostAddress() ?: ""
                    }
                }
            }
        } catch (e: SocketException) {
            Log.e(CST, "Error when getting local ip: $e")
        }

        return "$ipPort:$PORT"
    }

    private fun pingLaptop() {
        mPingJob = CoroutineScope(Dispatchers.IO).launch {
            while (laptopIp != null) {
                val headers = HashMap<String, String>()
                headers["PING"] = "PING"
                headers["IP"] = getLocalIpPort()
                try {
                    val response = khttp.post("http://$laptopIp/", headers = headers)
                    Log.i(CST, "Sending PING!")
                    if (response.statusCode != 200 && response.text != "PONG") {
                        laptopIp = null
                        break
                    }
                    Log.i(CST, "Received PONG!")
                } catch (e: Exception) {
                    if (e is ConnectException) {
                        laptopIp = null
                        break
                    }
                    Log.e(CST, "Error while sending PING")
                }

                delay(2000)
            }
        }
    }
}