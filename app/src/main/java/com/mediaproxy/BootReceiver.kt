package com.mediaproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("MediaProxy", "Boot completed, starting proxy service")

            val prefs = context.getSharedPreferences("media_proxy", Context.MODE_PRIVATE)
            val data = prefs.getString("sources", null)
            if (data.isNullOrBlank()) return

            val names = mutableListOf<String>()
            val urls = mutableListOf<String>()
            for (line in data.split("\n")) {
                val parts = line.split("\t", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    names.add(parts[0])
                    urls.add(parts[1])
                }
            }

            if (names.isEmpty()) return

            val serviceIntent = Intent(context, ProxyService::class.java).apply {
                putExtra(ProxyService.EXTRA_SOURCES_NAMES, names.toTypedArray())
                putExtra(ProxyService.EXTRA_SOURCES_URLS, urls.toTypedArray())
                putExtra(ProxyService.EXTRA_PORT, 8088)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
