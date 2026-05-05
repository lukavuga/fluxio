package com.example.fluxio

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

object SupabaseInstance {
    private const val SUPABASE_URL = "https://vbpmfulxbpcuboirjokv.supabase.co"
    private const val SUPABASE_KEY = "sb_publishable_RtG4GlKSSxRk3fCLYfXwjA_a-d50Yvp"

    val client = createSupabaseClient(SUPABASE_URL, SUPABASE_KEY) {
        install(Postgrest)
        install(Auth)
        install(Realtime)
    }
}
