package app.monogatari.android.library

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object DeviceAuthentication {
    fun authenticate(context: Context, title: String, finished: (Boolean) -> Unit): Boolean {
        var current: Context = context
        while (current is ContextWrapper && current !is FragmentActivity) current = current.baseContext
        val activity = current as? FragmentActivity ?: return false
        val types = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(context).canAuthenticate(types) != BiometricManager.BIOMETRIC_SUCCESS) return false
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { finished(true) }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { finished(false) }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(types).build())
        return true
    }
}
