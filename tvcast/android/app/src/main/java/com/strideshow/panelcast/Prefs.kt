package com.strideshow.panelcast

import android.content.Context
import android.content.SharedPreferences

/**
 * Runtime settings.
 *
 * The signaling URL is overridable so one APK works against dev, staging, or a
 * self-hosted server. Reinstalling on a wall-mounted panel is painful, so
 * anything environment-specific must be changeable on-device.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("panelcast", Context.MODE_PRIVATE)

    var signalingUrl: String
        get() = sp.getString(KEY_URL, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_SIGNALING_URL
        set(value) = sp.edit().putString(KEY_URL, value.trim()).apply()

    /** Show the connection diagnostics overlay on the lobby. */
    var showDiagnostics: Boolean
        get() = sp.getBoolean(KEY_DIAG, false)
        set(value) = sp.edit().putBoolean(KEY_DIAG, value).apply()

    fun resetUrl() = sp.edit().remove(KEY_URL).apply()

    /**
     * Validate a user-entered signaling URL.
     * @return null if valid, else a human-readable reason.
     */
    fun validateUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return "Address cannot be empty"
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            return "Must start with wss:// or ws://"
        }
        val rest = url.removePrefix("ws://").removePrefix("wss://")
        if (rest.isEmpty() || rest.startsWith("/")) return "Missing server name"
        return null
    }

    companion object {
        private const val KEY_URL = "signaling_url"
        private const val KEY_DIAG = "show_diagnostics"
    }
}
