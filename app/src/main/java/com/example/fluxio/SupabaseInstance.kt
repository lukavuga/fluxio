package com.example.fluxio

import android.content.Context
import com.russhwolf.settings.SharedPreferencesSettings
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.SettingsSessionManager
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

object SupabaseInstance {
    private const val SUPABASE_URL = "https://vbpmfulxbpcuboirjokv.supabase.co"
    private const val SUPABASE_KEY = "sb_publishable_RtG4GlKSSxRk3fCLYfXwjA_a-d50Yvp"

    lateinit var client: io.github.jan.supabase.SupabaseClient
        private set

    fun init(context: Context) {
        val sharedPrefs = context.getSharedPreferences("fluxio_prefs", Context.MODE_PRIVATE)
        val settings = SharedPreferencesSettings(sharedPrefs)

        client = createSupabaseClient(SUPABASE_URL, SUPABASE_KEY) {
            install(Postgrest)
            install(Auth) {
                sessionManager = SettingsSessionManager(settings)
                autoLoadFromStorage = true
                // KLJUČNO: Onemogoči agresivno osveževanje, ko ni povezave
                alwaysAutoRefresh = false
            }
            install(Realtime)
        }
    }
}