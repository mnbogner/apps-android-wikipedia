package org.wikipedia.activity

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

class ProxyService : Service() {
    // binder given to clients
    private val binder: IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        // return this instance of ProxyService so clients can call public methods
        fun getService(): ProxyService = this@ProxyService
    }

    @SuppressLint("NewApi")
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        // this service is intended to use a proxy to link a local, unauthenticated url
        // to a remote proxy url that requires authentication or certs as parameters
        val localUrl: String = (intent.getStringExtra("LOCAL_URL") ?: "socks5://127.0.0.1:1081")  // check for port conflicts
        val proxyUrl: String = (intent.getStringExtra("PROXY_URL") ?: "obfs4://foo")

        // build a config file to avoid issues with strings that might require quotes as command line arguments
        val config = JSONObject()
        val sNodes = JSONArray()
        sNodes.put(localUrl)
        val cNodes = JSONArray()
        cNodes.put(proxyUrl)
        config.put("Debug", true)
        config.put("Retries", 0)
        config.put("ServeNodes", sNodes)
        config.put("ChainNodes", cNodes)

        // write config file
        val configFile = File(ContextCompat.getNoBackupFilesDir(this), "gost.json")
        configFile.writeText(config.toString())

        // copied from ShadowsocksService, may not be needed
        val channelId = "proxy-channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "proxy-channel"
            val channel = NotificationChannel(
                    channelId, name, NotificationManager.IMPORTANCE_LOW)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // copied from ShadowsocksService, may not be needed
        @Suppress("DEPRECATION")
        val notification: Notification = Notification.Builder(this, channelId)
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentTitle("Proxy is running")
                .setContentText("Proxy is running")
                // deprecated in API level 26, see NotificationChannel#setImportance(int)
                .setPriority(Notification.PRIORITY_LOW)
                .setTicker("Proxy is running")
                .build()
        startForeground(SystemClock.uptimeMillis().toInt(), notification)

        // TODO - currently the gost binary must be manually copied into /lib/arm64-v8a/
        //   it will be extracted during installation to a directory where it can be executed
        val nativeLibraryDir = applicationInfo.nativeLibraryDir
        val executableFile = File(nativeLibraryDir, "gost-linux-armv8.so")
        val executablePath = executableFile.absolutePath
        if (executableFile.exists()) {
            Runnable {
                try {
                    // TODO - gost will bind a port and continue running after the app is closed.
                    //   code must be added to manage the process and terminate it with the app.
                    val cmdArgs = arrayOf(executablePath, "-C", configFile.absolutePath)
                    val proc: Process = Runtime.getRuntime().exec(cmdArgs)

                    val broadcastIntent = Intent()
                    broadcastIntent.action = "PROXY_STARTED"
                    broadcastIntent.putExtra("LOCAL_URL", localUrl)
                    sendBroadcast(broadcastIntent)
                } catch (e: Exception) {
                    Log.e("ProxyService", "EXCEPTION WHEN RUNNING EXECUTABLE", e)
                }
            }.run()
        } else {
            Log.d("ProxyService", "EXECUTABLE " + executablePath + " DOES NOT EXIST")
        }

        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }
}