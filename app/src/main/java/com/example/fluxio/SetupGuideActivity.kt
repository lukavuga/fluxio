package com.example.fluxio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.snackbar.Snackbar

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
        // v2.1 PowerShell Command (Idempotent/Upsert logic)
        val winCommand = "powershell -Command \"Set-ExecutionPolicy Bypass -Scope Process -Force; [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12; iex ((New-Object System.Net.WebClient).DownloadString('https://raw.githubusercontent.com/lukavuga/fluxio/refs/heads/main/setup_fluxio.ps1'))\""
        val linuxUrl = "https://raw.githubusercontent.com/lukavuga/fluxio/main/setup_linux.sh"
        val macUrl = "https://raw.githubusercontent.com/lukavuga/fluxio/main/setup_mac.sh"

        // Windows
        findViewById<Button>(R.id.btnCopyWindowsScript).setOnClickListener {
            copyToClipboard(winCommand)
            showFeedback("Setup command copied! Run this in PowerShell on your PC.")
        }
        findViewById<ImageButton>(R.id.btnShareWin).setOnClickListener { shareText(winCommand, "Windows") }

        // Linux
        findViewById<Button>(R.id.btnCopyLinuxScript).setOnClickListener { openUrl(linuxUrl) }
        findViewById<ImageButton>(R.id.btnShareLinux).setOnClickListener { shareText(linuxUrl, "Linux") }

        // macOS
        findViewById<Button>(R.id.btnCopyMacScript).setOnClickListener { openUrl(macUrl) }
        findViewById<ImageButton>(R.id.btnShareMac).setOnClickListener { shareText(macUrl, "macOS") }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Fluxio Setup", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun shareText(text: String, os: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Fluxio $os Setup")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share Setup Script"))
    }

    private fun showFeedback(message: String) {
        val root = findViewById<View>(android.R.id.content)
        val snackbar = Snackbar.make(root, message, Snackbar.LENGTH_LONG)
        snackbar.view.setBackgroundColor(getColor(R.color.flux_card))
        val textView = snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(getColor(R.color.white))
        snackbar.show()
    }
}
