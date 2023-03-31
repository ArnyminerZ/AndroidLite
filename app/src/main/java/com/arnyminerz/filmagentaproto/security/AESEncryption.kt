package com.arnyminerz.filmagentaproto.security

import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.arnyminerz.filmagentaproto.BuildConfig
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object AESEncryption {

    private val salt = Base64.encodeToString(BuildConfig.AES_SALT.toByteArray(), Base64.NO_WRAP)
    private val iv = Base64.encodeToString(BuildConfig.AES_IV.toByteArray(), Base64.NO_WRAP)

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun getSecretKey(): String = Base64.encodeToString(BuildConfig.AES_KEY.toByteArray(), Base64.NO_WRAP)

    fun encrypt(strToEncrypt: String): String? {
        try {
            val ivParameterSpec = IvParameterSpec(Base64.decode(iv, Base64.NO_WRAP))

            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            val spec = PBEKeySpec(
                getSecretKey().toCharArray(),
                Base64.decode(salt, Base64.NO_WRAP),
                10000,
                256
            )
            val tmp = factory.generateSecret(spec)
            val secretKey = SecretKeySpec(tmp.encoded, "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivParameterSpec)
            return Base64.encodeToString(
                cipher.doFinal(strToEncrypt.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )
        } catch (e: Exception) {
            println("Error while encrypting: $e")
        }
        return null
    }

    fun decrypt(strToDecrypt: String): String? {
        try {
            val ivParameterSpec = IvParameterSpec(Base64.decode(iv, Base64.NO_WRAP))

            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            val spec = PBEKeySpec(
                getSecretKey().toCharArray(),
                Base64.decode(salt, Base64.NO_WRAP),
                10000,
                256
            )
            val tmp = factory.generateSecret(spec);
            val secretKey = SecretKeySpec(tmp.encoded, "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec);
            return String(cipher.doFinal(Base64.decode(strToDecrypt, Base64.NO_WRAP)))
        } catch (e: Exception) {
            println("Error while decrypting: $e");
        }
        return null
    }
}
