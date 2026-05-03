package com.example.fluxio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class SetupGuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_guide)

        val toolbar = findViewById<Toolbar>(R.id.setupToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        setupScriptActions()

        findViewById<Button>(R.id.btnFinishSetup).setOnClickListener {
            val prefs = getSharedPreferences("fluxio_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("first_run", false).apply()
            finish()
        }
    }

    private fun setupScriptActions() {
        val winUrl = "https://raw.githubusercontent.com/lukavuga/fluxio/main/setup_windows.ps1"
        val linuxUrl = "https://raw.githubusercontent.com/lukavuga/fluxio/main/setup_linux.sh"
        val macUrl = "https://raw.githubusercontent.com/lukavuga/fluxio/main/setup_mac.sh"

        // Windows
        findViewById<Button>(R.id.btnDownloadWin).setOnClickListener { openUrl(winUrl) }
        findViewById<ImageButton>(R.id.btnShareWin).setOnClickListener { shareUrl(winUrl, "Windows") }

        // Linux
        findViewById<Button>(R.id.btnDownloadLinux).setOnClickListener { openUrl(linuxUrl) }
        findViewById<ImageButton>(R.id.btnShareLinux).setOnClickListener { shareUrl(linuxUrl, "Linux") }

        // macOS
        findViewById<Button>(R.id.btnDownloadMac).setOnClickListener { openUrl(macUrl) }
        findViewById<ImageButton>(R.id.btnShareMac).setOnClickListener { shareUrl(macUrl, "macOS") }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun shareUrl(url: String, os: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Fluxio $os Setup Script")
            putExtra(Intent.EXTRA_TEXT, "Here is the Fluxio setup script for $os: $url")
        }
        startActivity(Intent.createChooser(intent, "Share Script Link"))
    }
}
