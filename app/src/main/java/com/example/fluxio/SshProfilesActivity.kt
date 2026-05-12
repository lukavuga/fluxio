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
    private lateinit var navView: NavigationView
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
        navView = findViewById<NavigationView>(R.id.nav_view_ssh)

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
            onDelete = { profile -> handleProfileAction(profile) }
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

    override fun onResume() {
        super.onResume()
        navView.setCheckedItem(R.id.nav_ssh_profiles)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.nav_ssh_profiles) {
            drawerLayout.closeDrawer(GravityCompat.START)
            return true
        }

        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) {
                val intent = when (item.itemId) {
                    R.id.nav_current -> Intent(this@SshProfilesActivity, MainActivity::class.java).apply {
                        putExtra("SHOW_LAYOUT", "CURRENT")
                    }
                    R.id.nav_saved -> Intent(this@SshProfilesActivity, MainActivity::class.java).apply {
                        putExtra("SHOW_LAYOUT", "SAVED")
                    }
                    R.id.nav_setup -> Intent(this@SshProfilesActivity, MainActivity::class.java).apply {
                        putExtra("SHOW_LAYOUT", "SETUP")
                    }
                    R.id.nav_logout -> {
                        lifecycleScope.launch {
                            authRepository.signOut()
                            val logoutIntent = Intent(this@SshProfilesActivity, LoginActivity::class.java)
                            logoutIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(logoutIntent)
                            finish()
                        }
                        null
                    }
                    else -> null
                }

                intent?.let {
                    it.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    startActivity(it)
                    finish()
                }
                drawerLayout.removeDrawerListener(this)
            }
        })

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
            editPass.setText(SecurityUtils.decrypt(it.sshPassword) ?: it.sshPassword)
            
            if (it.label == "Fluxio Default") {
                editLabel.isEnabled = false
                editUser.isEnabled = false
            }
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
                        sshPassword = encryptedPassword,
                        isEnabled = profile?.isEnabled ?: true
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

    private fun handleProfileAction(profile: SshCredential) {
        if (profile.label == "Fluxio Default") {
            // Immediate toggle logic for default profile as per requirement
            val updated = profile.copy(isEnabled = !profile.isEnabled)
            lifecycleScope.launch {
                try {
                    supabaseRepository.upsertSshCredential(updated)
                    loadProfiles()
                    val state = if (updated.isEnabled) "enabled" else "disabled"
                    showFeedback("Default profile $state")
                } catch (e: Exception) {
                    showFeedback("Toggle failed: ${e.message}")
                }
            }
            return
        }

        // Regular delete for other profiles
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
