package com.example.fluxio

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.fluxio.databinding.ActivityRegisterBinding
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private val authRepository = AuthRepository(SupabaseInstance.client)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRegister.setOnClickListener {
            val email = binding.emailInput.text.toString().trim()
            val password = binding.passwordInput.text.toString()
            val confirmPassword = binding.confirmPasswordInput.text.toString()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this@RegisterActivity, "Registration successful! Please check your email for verification.", Toast.LENGTH_LONG).show()
                finish()
            } else {
                val exception = result.exceptionOrNull()
                Toast.makeText(this@RegisterActivity, "Registration failed: ${exception?.message}", Toast.LENGTH_LONG).show()

                binding.progressBar.visibility = View.GONE
                binding.btnRegister.isEnabled = true
            }
        }
    }
}
