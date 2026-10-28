package com.oooshk.witnesscam

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Fingerprint/face as an alternative to the app PIN (the PIN always remains as a fallback). */
object BiometricUnlock {
    // WEAK includes STRONG, so phones whose face unlock is class 2 can use it too.
    private const val AUTHENTICATORS = BIOMETRIC_WEAK

    /** True if the phone has a fingerprint or face enrolled that apps can use. */
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** True if the user turned it on and it can be used right now. */
    fun enabled(context: Context): Boolean {
        val prefs = Prefs(context)
        return prefs.hasPin && prefs.biometricUnlock && available(context)
    }

    fun prompt(activity: FragmentActivity, title: CharSequence, onSuccess: () -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                // Errors and "Use PIN" just leave the PIN screen showing.
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(activity.getString(R.string.biometric_use_pin))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }
}
