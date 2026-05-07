package com.example.fluxio

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SshProfilesActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var adapter: SshProfileAdapter
    private val supabaseRepository = SupabaseRepository()
    private val authRepository = AuthRepository(SupabaseInstance.client)
    private lateinit var txtNoProfiles: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ssh_profiles)

        val toolbar = findViewById<Toolbar>(R.id.toolbarSsh)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "SSH Profiles"

        drawerLayout = findViewById(R.id.drawer_layout_ssh)
        val navView = findViewById<NavigationView>(R.id.nav_view_ssh)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navView.setNavigationItemSelectedListener(this)
        navView.setCheckedItem(R.id.nav_ssh_profiles)

        val headerView = navView.getHeaderView(0)
        headerView.findViewById<TextView>(R.id.textViewUserEmail)?.text = authRepository.currentUserEmail()

        txtNoProfiles = findViewById(R.id.txtNoProfiles)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerSshProfiles)
        val fabAdd = findViewById<FloatingActionButton>(R.id.fabAddProfile)

        adapter = SshProfileAdapter(mutableListOf(), 
            onEdit = { profile -> showAddEditDialog(profile) },
            onDelete = { profile -> showDeleteConfirmDialog(profile) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        fabAdd.setOnClickListener { showAddEditDialog(null) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        loadProfiles()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_current -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                intent.putExtra("SHOW_LAYOUT", "CURRENT")
                startActivity(intent)
                finish()
            }
            R.id.nav_saved -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                intent.putExtra("SHOW_LAYOUT", "SAVED")
                startActivity(intent)
                finish()
            }
            R.id.nav_ssh_profiles -> {
                // Already here
            }
            R.id.nav_setup -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                intent.putExtra("SHOW_LAYOUT", "SETUP")
                startActivity(intent)
                finish()
            }
            R.id.nav_logout -> {
                lifecycleScope.launch {
                    authRepository.signOut()
                    val intent = Intent(this@SshProfilesActivity, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun loadProfiles() {
        lifecycleScope.launch {
            try {
                val profiles = supabaseRepository.getSshCredentials()
                withContext(Dispatchers.Main) {
                    adapter.updateList(profiles)
                    txtNoProfiles.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                showFeedback("Error loading profiles: ${e.message}")
            }
        }
    }

    private fun showAddEditDialog(profile: SshCredential?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_ssh_profile, null)
        val editLabel = dialogView.findViewById<EditText>(R.id.editProfileLabel)
        val editUser = dialogView.findViewById<EditText>(R.id.editProfileUsername)
        val editPass = dialogView.findViewById<EditText>(R.id.editProfilePassword)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveProfile)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelProfile)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)

        txtTitle.text = if (profile == null) "Add SSH Profile" else "Edit SSH Profile"

        profile?.let {
            editLabel.setText(it.label)
            editUser.setText(it.sshUsername)
            editPass.setText(SecurityUtils.decrypt(it.sshPassword) ?: "")
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val label = editLabel.text.toString().trim()
            val user = editUser.text.toString().trim()
            val pass = editPass.text.toString().trim()

            if (label.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                showFeedback("Please fill all fields")
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    val userId = authRepository.getCurrentUser()?.id ?: return@launch
                    val encryptedPassword = SecurityUtils.encrypt(pass) ?: pass
                    val newProfile = SshCredential(
                        id = profile?.id,
                        userId = userId,
                        label = label,
                        sshUsername = user,
                        sshPassword = encryptedPassword
                    )
                    supabaseRepository.upsertSshCredential(newProfile)
                    loadProfiles()
                    dialog.dismiss()
                    showFeedback("Profile saved successfully")
                } catch (e: Exception) {
                    showFeedback("Error saving: ${e.message}")
                }
            }
        }

        dialog.show()
    }

    private fun showDeleteConfirmDialog(profile: SshCredential) {
        AlertDialog.Builder(this)
            .setTitle("Delete Profile")
            .setMessage("Are you sure you want to delete '${profile.label}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        profile.id?.let { supabaseRepository.deleteSshCredential(it) }
                        loadProfiles()
                        showFeedback("Profile deleted")
                    } catch (e: Exception) {
                        showFeedback("Error deleting: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
