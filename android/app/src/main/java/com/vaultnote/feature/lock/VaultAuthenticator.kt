package com.vaultnote.feature.lock

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.vaultnote.R

internal interface VaultAuthenticator {
    fun isAvailable(): Boolean
    fun authenticate()
}

internal class AndroidVaultAuthenticator(
    private val fragment: Fragment,
    private val onSuccess: () -> Unit,
    private val onError: (cancelledByUser: Boolean) -> Unit,
) : VaultAuthenticator {
    private val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    private val prompt = BiometricPrompt(
        fragment,
        ContextCompat.getMainExecutor(fragment.requireContext()),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED,
                )
            }
        },
    )

    @Suppress("DEPRECATION")
    override fun isAvailable(): Boolean {
        val context = fragment.requireContext()
        val biometricAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.from(context).canAuthenticate(authenticators) ==
                BiometricManager.BIOMETRIC_SUCCESS
        } else {
            BiometricManager.from(context).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        }
        val credentialAvailable =
            context.getSystemService(Context.KEYGUARD_SERVICE)
                .let { it as? KeyguardManager }
                ?.isDeviceSecure == true
        return biometricAvailable || credentialAvailable
    }

    @Suppress("DEPRECATION")
    override fun authenticate() {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(fragment.getString(R.string.unlock_vault))
            .setSubtitle(fragment.getString(R.string.vault_locked_message))
            .setConfirmationRequired(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(authenticators)
        } else {
            builder.setDeviceCredentialAllowed(true)
        }
        prompt.authenticate(builder.build())
    }
}
