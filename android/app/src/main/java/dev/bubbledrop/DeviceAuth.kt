package dev.bubbledrop

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Asks the phone's owner to confirm with a fingerprint (or the screen-lock PIN, pattern or
 * password) before something sensitive: pairing, accepting files, or sharing the screen. A phone
 * with no screen lock has nothing to check against, so it goes ahead.
 */
object DeviceAuth {
    /** Returns a signal that closes the prompt, e.g. when what it asks about is withdrawn. */
    fun confirm(
        context: Context, title: String, subtitle: String, onConfirmed: () -> Unit, onCancelled: () -> Unit = {},
    ): CancellationSignal {
        val signal = CancellationSignal()
        if (!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            onConfirmed()
            return signal
        }
        val builder = BiometricPrompt.Builder(context).setTitle(title).setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(signal, context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onConfirmed()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onCancelled()
        })
        return signal
    }
}
