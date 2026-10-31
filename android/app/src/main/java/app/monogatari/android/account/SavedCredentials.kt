package app.monogatari.android.account

import android.content.Context
import app.monogatari.android.data.SecretStore
import org.json.JSONObject

/** The email, and the password when the user chose to remember it, kept in the Keystore-backed store. */
class SavedCredentials(context: Context) {
    private val store = SecretStore(context)

    data class Saved(val email: String, val password: String?)

    fun read(): Saved? = runCatching {
        val json = JSONObject(store.get(KEY) ?: return null)
        val email = json.optString("email").takeIf(String::isNotEmpty) ?: return null
        Saved(email, json.text("password"))
    }.getOrNull()

    fun save(email: String, password: String?) = store.set(KEY, JSONObject().put("email", email).apply { password?.let { put("password", it) } }.toString())

    /** Forgets the password at once, keeping the email for the next sign-in. */
    fun forgetPassword() { read()?.let { save(it.email, null) } }

    private companion object { const val KEY = "account.remembered" }
}
