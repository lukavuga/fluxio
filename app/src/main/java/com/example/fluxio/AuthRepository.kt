package com.example.fluxio

import android.content.Context
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.gotrue.user.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class AuthRepository(
    private val context: Context,
    private val supabaseClient: SupabaseClient
) {
    private val prefs = context.getSharedPreferences("fluxio_prefs", Context.MODE_PRIVATE)

    suspend fun hasActualInternet(): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress("8.8.8.8", 53), 1500)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun signUp(email: String, password: String): Result<Unit> {
        return try {
            if (!hasActualInternet()) return Result.failure(Exception("Registration requires internet connection"))
            supabaseClient.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signIn(email: String, password: String): Result<Unit> {
        return try {
            if (hasActualInternet()) {
                // Online: Perform standard signInWith(Email).
                supabaseClient.auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }
                // When a user logs in successfully (Online), set a boolean is_logged_in to true in SharedPreferences.
                setLoggedInFlag(true)
                Result.success(Unit)
            } else {
                // Offline mode: allow entry if flag is true and session exists.
                if (isLoggedInLocally() && hasPersistedSession()) {
                    Log.d("AuthRepo", "Offline mode: Session valid, entry allowed.")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Offline login failed: No valid session found."))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun setLoggedInFlag(value: Boolean) {
        prefs.edit().putBoolean("is_logged_in", value).apply()
    }

    private fun isLoggedInLocally(): Boolean {
        return prefs.getBoolean("is_logged_in", false)
    }

    private fun hasPersistedSession(): Boolean {
        return supabaseClient.auth.currentSessionOrNull() != null
    }

    fun isUserLoggedIn(): Boolean {
        // App Startup Check
        return if (supabaseClient.auth.currentSessionOrNull() != null) {
            setLoggedInFlag(true)
            true
        } else {
            isLoggedInLocally()
        }
    }

    suspend fun signOut() {
        try {
            if (hasActualInternet()) {
                supabaseClient.auth.signOut()
            }
        } catch (e: Exception) {}
        setLoggedInFlag(false)
    }
    
    fun currentUserEmail(): String? {
        val user = supabaseClient.auth.currentUserOrNull()
        return user?.email ?: if (isLoggedInLocally()) "Offline User" else null
    }

    fun getCurrentUser(): UserInfo? {
        return supabaseClient.auth.currentUserOrNull()
    }
}
