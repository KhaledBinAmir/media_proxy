package com.mediaproxy

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var toggleButton: Button
    private lateinit var sourcesContainer: LinearLayout
    private lateinit var addButton: Button

    private val sources = mutableListOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        loadSources()
        refreshSourceList()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    // ── Focus-aware background for TV remote navigation ──

    private fun makeFocusBackground(normalColor: Int, focusedColor: Int): StateListDrawable {
        val focused = GradientDrawable().apply {
            setColor(focusedColor)
            cornerRadius = dp(4).toFloat()
        }
        val normal = GradientDrawable().apply {
            setColor(normalColor)
            cornerRadius = dp(4).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }

        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Title
        content.addView(TextView(this).apply {
            text = "Media Proxy"
            textSize = 32f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        content.addView(TextView(this).apply {
            text = "WebDAV bridge for HTTP media indexes"
            textSize = 14f
            setTextColor(0xFFAAAAAA.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(24))
        })

        // Status
        statusText = TextView(this).apply {
            text = "Stopped"
            textSize = 20f
            setTextColor(0xFFFF5252.toInt())
            gravity = Gravity.CENTER
        }
        content.addView(statusText)

        addressText = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(0xFF42A5F5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
        }
        content.addView(addressText)

        // Toggle button — large, prominent, focus-highlighted
        toggleButton = Button(this).apply {
            text = "Start Proxy"
            textSize = 18f
            isFocusable = true
            isFocusableInTouchMode = true
            background = makeFocusBackground(0xFF1565C0.toInt(), 0xFF42A5F5.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(260), dp(60)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        toggleButton.setOnClickListener { toggle() }
        content.addView(toggleButton)

        // Sources section
        content.addView(View(this).apply {
            setBackgroundColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(24); bottomMargin = dp(16) }
        })

        content.addView(TextView(this).apply {
            text = "Media Sources"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })

        sourcesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(sourcesContainer)

        addButton = Button(this).apply {
            text = "+ Add Source"
            textSize = 14f
            isFocusable = true
            background = makeFocusBackground(0xFF2E2E2E.toInt(), 0xFF42A5F5.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL }
        }
        addButton.setOnClickListener { showAddSourceDialog() }
        content.addView(addButton)

        // Settings section
        content.addView(View(this).apply {
            setBackgroundColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(24); bottomMargin = dp(16) }
        })

        content.addView(TextView(this).apply {
            text = "Settings"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })

        val autoStartRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFF1E1E1E.toInt())
        }

        autoStartRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = "Start on boot"
                textSize = 16f
                setTextColor(Color.WHITE)
            })
            addView(TextView(context).apply {
                text = "Automatically start the proxy when the device boots"
                textSize = 12f
                setTextColor(0xFF888888.toInt())
            })
        })

        val autoStartToggle = Switch(this).apply {
            isChecked = getAutoStartEnabled()
            isFocusable = true
            setOnCheckedChangeListener { _, checked ->
                setAutoStartEnabled(checked)
            }
        }
        autoStartRow.addView(autoStartToggle)
        content.addView(autoStartRow)

        // Nova instructions
        content.addView(View(this).apply {
            setBackgroundColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(24); bottomMargin = dp(16) }
        })

        content.addView(TextView(this).apply {
            text = "Player Setup:\nProtocol: WebDAV  |  Server: 127.0.0.1\nPort: 8088  |  Path: /\nNo username/password"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1f)
        })

        // About section
        content.addView(View(this).apply {
            setBackgroundColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(24); bottomMargin = dp(16) }
        })

        content.addView(TextView(this).apply {
            text = "About"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })

        content.addView(TextView(this).apply {
            text = "Media Proxy v1.0.0\n\n" +
                "Bridges HTTP browsable media indexes to WebDAV, " +
                "enabling media players like Nova Video Player to browse " +
                "and stream content from HTTP directory listings.\n\n" +
                "Built for personal use. Anyone with a similar setup is welcome to use it.\n\n" +
                "GitHub: github.com/KhaledBinAmir/media_proxy\n" +
                "Telegram: t.me/KhaledBinAmir"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(12), 0, dp(12), dp(16))
        })

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)

        // Request focus on the toggle button by default
        toggleButton.requestFocus()
    }

    private fun refreshSourceList() {
        sourcesContainer.removeAllViews()
        for ((i, source) in sources.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(if (i % 2 == 0) 0xFF1E1E1E.toInt() else 0xFF252525.toInt())
            }

            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = source.first
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = source.second
                    textSize = 12f
                    setTextColor(0xFF888888.toInt())
                    setSingleLine(true)
                })
            })

            // Edit button
            val editBtn = Button(this).apply {
                text = "Edit"
                textSize = 12f
                isFocusable = true
                background = makeFocusBackground(0xFF2E2E2E.toInt(), 0xFF1565C0.toInt())
                setTextColor(0xFF90CAF9.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(70), dp(40)).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener { showEditSourceDialog(i) }
            }
            row.addView(editBtn)

            // Delete button with confirmation dialog
            val deleteBtn = Button(this).apply {
                text = "Delete"
                textSize = 12f
                isFocusable = true
                background = makeFocusBackground(0xFF3E2020.toInt(), 0xFFD32F2F.toInt())
                setTextColor(0xFFFF8A80.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(80), dp(40))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Remove Source")
                        .setMessage("Remove \"${source.first}\"?")
                        .setPositiveButton("Remove") { _, _ ->
                            sources.removeAt(i)
                            saveSources()
                            refreshSourceList()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            row.addView(deleteBtn)
            sourcesContainer.addView(row)
        }
    }

    private fun showAddSourceDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val nameInput = EditText(this).apply {
            hint = "Name (e.g. Movies, Anime)"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF666666.toInt())
        }
        layout.addView(nameInput)

        val urlInput = EditText(this).apply {
            hint = "URL (e.g. http://172.16.172.166:8087)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF666666.toInt())
        }
        layout.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("Add Media Source")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    sources.add(Pair(name, url))
                    saveSources()
                    refreshSourceList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditSourceDialog(index: Int) {
        val current = sources[index]
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val nameInput = EditText(this).apply {
            setText(current.first)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF666666.toInt())
        }
        layout.addView(nameInput)

        val urlInput = EditText(this).apply {
            setText(current.second)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF666666.toInt())
        }
        layout.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("Edit Source")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    sources[index] = Pair(name, url)
                    saveSources()
                    refreshSourceList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggle() {
        if (ProxyService.isRunning) {
            stopService(Intent(this, ProxyService::class.java))
        } else {
            if (sources.isEmpty()) {
                Toast.makeText(this, "Add at least one source first!", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(this, ProxyService::class.java).apply {
                putExtra(ProxyService.EXTRA_SOURCES_NAMES, sources.map { it.first }.toTypedArray())
                putExtra(ProxyService.EXTRA_SOURCES_URLS, sources.map { it.second }.toTypedArray())
                putExtra(ProxyService.EXTRA_PORT, 8088)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        toggleButton.postDelayed({ updateUI() }, 500)
    }

    private fun updateUI() {
        if (ProxyService.isRunning) {
            statusText.text = "Running"
            statusText.setTextColor(0xFF4CAF50.toInt())
            toggleButton.text = "Stop Proxy"
            toggleButton.background = makeFocusBackground(0xFF2E7D32.toInt(), 0xFF66BB6A.toInt())
            val ip = getLocalIpAddress()
            addressText.text = "WebDAV: $ip:8088 | 127.0.0.1:8088"
        } else {
            statusText.text = "Stopped"
            statusText.setTextColor(0xFFFF5252.toInt())
            toggleButton.text = "Start Proxy"
            toggleButton.background = makeFocusBackground(0xFF1565C0.toInt(), 0xFF42A5F5.toInt())
            addressText.text = ""
        }
    }

    // ── Persistence ──

    private fun getAutoStartEnabled(): Boolean {
        return getSharedPreferences("media_proxy", Context.MODE_PRIVATE)
            .getBoolean("auto_start", true)
    }

    private fun setAutoStartEnabled(enabled: Boolean) {
        getSharedPreferences("media_proxy", Context.MODE_PRIVATE)
            .edit().putBoolean("auto_start", enabled).apply()
    }

    private fun saveSources() {
        val prefs = getSharedPreferences("media_proxy", Context.MODE_PRIVATE)
        val data = sources.joinToString("\n") { "${it.first}\t${it.second}" }
        prefs.edit().putString("sources", data).apply()
    }

    private fun loadSources() {
        val prefs = getSharedPreferences("media_proxy", Context.MODE_PRIVATE)
        val data = prefs.getString("sources", null)
        sources.clear()
        if (data.isNullOrBlank()) {
            // No default sources — let users add their own
        } else {
            for (line in data.split("\n")) {
                val parts = line.split("\t", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    sources.add(Pair(parts[0], parts[1]))
                }
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
