package com.studynotes.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** End-to-end encryption: EC P-256 identity key per device, ECDH per chat, AES-GCM-256 per message. */
class Crypto(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("keys", Context.MODE_PRIVATE)
    private val cache = HashMap<String, SecretKey>()
    lateinit var publicKeyB64: String; private set
    private lateinit var privateKey: PrivateKey

    init { loadOrCreate() }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("wrap", null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder("wrap", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return kg.generateKey()
    }

    private fun loadOrCreate() {
        val pub = prefs.getString("pub", null)
        val wrapped = prefs.getString("priv", null)
        if (pub != null && wrapped != null) {
            try {
                val raw = Base64.decode(wrapped, Base64.NO_WRAP)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, raw, 0, 12))
                val pk = c.doFinal(raw, 12, raw.size - 12)
                privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pk))
                publicKeyB64 = pub
                return
            } catch (_: Exception) { }
        }
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        val out = c.iv + c.doFinal(kp.private.encoded)
        privateKey = kp.private
        publicKeyB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        prefs.edit().putString("pub", publicKeyB64).putString("priv", Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    private fun chatKey(chatId: String, peerPub: String): SecretKey = cache.getOrPut("$chatId|$peerPub") {
        val pub: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(peerPub, Base64.NO_WRAP)))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey); ka.doPhase(pub, true)
        val secret = ka.generateSecret()
        val k = MessageDigest.getInstance("SHA-256").digest(secret + "studynotes-v1|$chatId".toByteArray())
        SecretKeySpec(k, "AES")
    }

    fun encrypt(chatId: String, peerPub: String, text: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, chatKey(chatId, peerPub), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(chatId: String, peerPub: String, data: String): String = try {
        val raw = Base64.decode(data, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chatKey(chatId, peerPub), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) { "🔒 Can't decrypt (sent to an older key)" }
}
