package com.example.fluxio

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecurityUtils {
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"
    // Hardcoded key for now as requested. In a real app, use Android Keystore.
    private const val KEY = "FluxioSecureKey12" // 16 bytes for AES-128
    private const val IV = "FluxioInitialVec"  // 16 bytes IV

    fun encrypt(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val keySpec = SecretKeySpec(KEY.toByteArray(), "AES")
            val ivSpec = IvParameterSpec(IV.toByteArray())
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encrypted = cipher.doFinal(value.toByteArray())
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun decrypt(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val keySpec = SecretKeySpec(KEY.toByteArray(), "AES")
            val ivSpec = IvParameterSpec(IV.toByteArray())
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decoded = Base64.decode(value, Base64.NO_WRAP)
            String(cipher.doFinal(decoded))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
