package com.monimage.launcher

import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** Garde le mot de passe sous forme d'empreinte salée (jamais en clair). */
class PasswordStore(private val prefs: SharedPreferences) {

    val isSet: Boolean get() = prefs.contains(KEY_HASH)

    fun set(password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, encode(salt))
            .putString(KEY_HASH, encode(hash(salt, password)))
            .apply()
    }

    fun check(password: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null)?.let(::decode) ?: return false
        val expected = prefs.getString(KEY_HASH, null)?.let(::decode) ?: return false
        return MessageDigest.isEqual(expected, hash(salt, password))
    }

    private fun hash(salt: ByteArray, password: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        var out = salt + password.toByteArray(Charsets.UTF_8)
        repeat(10_000) { out = digest.digest(out) }
        return out
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    companion object {
        private const val KEY_SALT = "pwd_salt"
        private const val KEY_HASH = "pwd_hash"
    }
}
