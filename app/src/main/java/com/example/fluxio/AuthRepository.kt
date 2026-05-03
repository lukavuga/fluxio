package com.example.fluxio

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email

class AuthRepository(private val supabaseClient: SupabaseClient) {

    suspend fun signUp(email: String, password: String) {
        supabaseClient.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signIn(email: String, password: String) {
        supabaseClient.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    fun isUserLoggedIn(): Boolean {
        // Checks if there is an existing session
        return supabaseClient.auth.currentSessionOrNull() != null
    }

    suspend fun signOut() {
        supabaseClient.auth.signOut()
    }
    
    fun currentUserEmail(): String? {
        return supabaseClient.auth.currentUserOrNull()?.email
    }
}
