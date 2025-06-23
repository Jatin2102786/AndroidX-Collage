package com.o7solutions.task.others

import android.app.Application
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage


class MyApplication: Application() {


    companion object {
        lateinit var supabase: SupabaseClient
    }



    override fun onCreate() {
        super.onCreate()

        supabase = createSupabaseClient(
            supabaseUrl = "https://dtixdjlbjdizqsgnuget.supabase.co",
            supabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImR0aXhkamxiamRpenFzZ251Z2V0Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3MzYxNTMxNzYsImV4cCI6MjA1MTcyOTE3Nn0.4x-jCuZr8_u3n3g9AK0hL3qr6yQ9ObNS3H_iJy6dS2U"
        ) {
            install(Storage)
        }
    }
}