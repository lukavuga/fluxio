package com.example.fluxio

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email

class AuthRepository(private val supabaseClient: SupabaseClient) {

    suspend fun signUp(email: String, password: String): Result<Unit> {
        return try {
            supabaseClient.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signIn(email: String, password: String) {
        supabaseClient.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    fun isUserLoggedIn(): Boolean {
        return supabaseClient.auth.currentSessionOrNull() != null
    }

    suspend fun signOut() {
        supabaseClient.auth.signOut()
    }
    
    fun currentUserEmail(): String? {
        return supabaseClient.auth.currentUserOrNull()?.email
    }
}
