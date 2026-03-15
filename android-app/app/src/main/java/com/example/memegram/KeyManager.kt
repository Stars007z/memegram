package com.example.memegram

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import androidx.core.content.edit

object KeyManager {

    private const val PREFS_NAME  = "keys_plain"
    private const val KEY_PRIVATE = "identity_private_key"
    private const val KEY_PUBLIC  = "identity_public_key"

    fun getOrCreateKeyPair(context: Context): Pair<ByteArray, ByteArray> {
        val prefs        = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingPriv = prefs.getString(KEY_PRIVATE, null)
        val existingPub  = prefs.getString(KEY_PUBLIC, null)

        if (existingPriv != null && existingPub != null) {
            return Pair(
                Base64.decode(existingPriv, Base64.NO_WRAP),
                Base64.decode(existingPub,  Base64.NO_WRAP)
            )
        }

        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()

        val pubRaw  = (keyPair.public  as Ed25519PublicKeyParameters).encoded
        val privRaw = (keyPair.private as Ed25519PrivateKeyParameters).encoded

        prefs.edit {
            putString(KEY_PRIVATE, Base64.encodeToString(privRaw, Base64.NO_WRAP))
                .putString(KEY_PUBLIC, Base64.encodeToString(pubRaw, Base64.NO_WRAP))
        }

        return Pair(privRaw, pubRaw)
    }

    fun signChallenge(context: Context, challengeBase64: String): ByteArray {
        val prefs      = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val privRaw    = Base64.decode(prefs.getString(KEY_PRIVATE, null)!!, Base64.NO_WRAP)
        val privateKey = Ed25519PrivateKeyParameters(privRaw, 0)

        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        val msg = Base64.decode(challengeBase64, Base64.NO_WRAP)
        signer.update(msg, 0, msg.size)
        return signer.generateSignature()
    }

    fun getPublicKeyBase64(context: Context): String {
        val (_, pub) = getOrCreateKeyPair(context)
        return Base64.encodeToString(pub, Base64.NO_WRAP)
    }

    fun hasKeyPair(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PRIVATE, null) != null
}
