package com.ptit.slinkmini

import android.content.Context

class Session(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("slink", Context.MODE_PRIVATE)

    var accessToken: String
        get() = p.getString("access_token", "").orEmpty()
        set(v) { p.edit().putString("access_token", v).apply() }

    var refreshToken: String
        get() = p.getString("refresh_token", "").orEmpty()
        set(v) { p.edit().putString("refresh_token", v).apply() }

    var idToken: String
        get() = p.getString("id_token", "").orEmpty()
        set(v) { p.edit().putString("id_token", v).apply() }

    var expiresAt: Long
        get() = p.getLong("expires_at", 0L)
        set(v) { p.edit().putLong("expires_at", v).apply() }

    var partitionCode: String
        get() = p.getString("partition", "").orEmpty()
        set(v) { p.edit().putString("partition", v).apply() }

    var pendingVerifier: String
        get() = p.getString("pending_verifier", "").orEmpty()
        set(v) { p.edit().putString("pending_verifier", v).apply() }

    var pendingState: String
        get() = p.getString("pending_state", "").orEmpty()
        set(v) { p.edit().putString("pending_state", v).apply() }

    fun isLoggedIn(): Boolean = accessToken.isNotBlank()
    fun clear() { p.edit().clear().apply() }
}
