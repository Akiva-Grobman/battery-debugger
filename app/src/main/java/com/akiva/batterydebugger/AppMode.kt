package com.akiva.batterydebugger

import android.content.Context

/** Recording Mode or Investigation Mode, chosen once when the app is first opened. */
enum class AppMode {
    RECORDING, INVESTIGATION;

    companion object {
        private const val PREFS = "app"
        private const val KEY_MODE = "mode"

        fun get(context: Context): AppMode? {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
            if (stored != null) return valueOf(stored)
            // Installs from before modes existed were already recording.
            val recordedBefore = context.getSharedPreferences("recorder", Context.MODE_PRIVATE).contains("recording")
            return if (recordedBefore) RECORDING else null
        }

        fun choose(context: Context, mode: AppMode) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.name).apply()
        }
    }
}
