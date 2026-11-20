package com.hunsu.note

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

/**
 * 앱 전역에서 SharedPreferences 에 접근하기 위한 Application.
 */
class HunsuApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: HunsuApp
            private set

        fun prefs(): SharedPreferences =
            instance.getSharedPreferences("hunsu_note_prefs", Context.MODE_PRIVATE)
    }
}
