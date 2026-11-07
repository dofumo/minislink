package com.ptit.slinkmini

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object Pkce {
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    fun newCodeVerifier(): String {
        val b = ByteArray(32); SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, FLAGS)
    }

    fun challenge(verifier: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(d, FLAGS)
    }

    fun newState(): String {
        val b = ByteArray(16); SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, FLAGS)
    }
}
