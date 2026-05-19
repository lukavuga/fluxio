package com.example.fluxio

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.fluxio.databinding.ActivityRegisterBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authRepository = AuthRepository(this, SupabaseInstance.client)

        binding.btnRegister.setOnClickListener {
            val email = binding.emailInput.text.toString().trim()
            val password = binding.passwordInput.text.toString()
            val confirmPassword = binding.confirmPasswordInput.text.toString()

            if (email.isEmpty() || password.isEmpty()) {
                showFeedback("Please fill in all fields")
                return@setOnClickListener
            }

            if (password != confirmPassword) {
                binding.confirmPasswordLayout.error = "Passwords do not match!"
                return@setOnClickListener
            } else {
                binding.confirmPasswordLayout.error = null
            }

            register(email, password)
        }

        binding.txtLogin.setOnClickListener {
            finish()
        }
    }

    private fun register(email: String, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnRegister.isEnabled = false

        lifecycleScope.launch {
            val result = authRepository.signUp(email, password)
            
            if (result.isSuccess) {
                showFeedback("Registration successful! Please check your email for verification.")
                // User can now go back to login
            } else {
                val exception = result.exceptionOrNull()
                showFeedback("Registration failed: ${exception?.message}")

                binding.progressBar.visibility = View.GONE
                binding.btnRegister.isEnabled = true
            }
        }
    }

    private fun showFeedback(message: String) {
        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
        snackbar.view.setBackgroundColor(getColor(R.color.flux_card))
        val textView = snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(getColor(R.color.white))
        snackbar.show()
    }
}
