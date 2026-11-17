package app.kaizen.commons

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda un texto reservado (hoy, la clave del servidor de sincronización) **cifrado** con una clave del Android Keystore: la clave de cifrado no sale del almacén del equipo, así
 * que el valor guardado no sirve copiado a otro sitio ni leído sin root. Formato: `v1:` + base64(iv de 12 bytes + texto cifrado AES-GCM).
 */
object SecretStore {
    private const val PREFIX = "v1:"
    private const val ALIAS = "kaizen_secret_v1"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Cifra [plain] con [key]; la parte pura, que se prueba sin Android. */
    internal fun seal(plain: String, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return PREFIX + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** Descifra lo que dejó [seal]; null si no es de este formato, está alterado o es de otra clave. */
    internal fun open(sealed: String, key: SecretKey): String? = runCatching {
        if (!sealed.startsWith(PREFIX)) return null
        val raw = Base64.getDecoder().decode(sealed.removePrefix(PREFIX))
        if (raw.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES)) }
        String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    /** La clave del Keystore de Android; se crea la primera vez. */
    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    /** Cifra con la clave del equipo; null si el Keystore falla (entonces no se guarda nada: nunca en claro). */
    fun encrypt(plain: String): String? = runCatching { seal(plain, keystoreKey()) }.onFailure(::logError).getOrNull()

    /** Descifra con la clave del equipo; null si no se puede (otra clave, valor alterado o Keystore roto). */
    fun decrypt(sealed: String): String? = runCatching { keystoreKey() }.getOrNull()?.let { open(sealed, it) }
}
