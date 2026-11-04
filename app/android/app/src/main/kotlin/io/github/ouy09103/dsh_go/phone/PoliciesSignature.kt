package io.github.ouy09103.dsh_go.phone

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * A signature over the phone-control rules, so a rewrite of the preferences file does not take
 * effect.
 *
 * The threat is specific: `shell` runs as uid 0, the app's `shared_prefs` are writable by that
 * uid, and the file holds the deny list, the unlocks and the 确认 mode. Anyone who can run one
 * root command can rewrite it, and the app would read the new rules on the next start — the
 * agent lifting its own leash. The command-level rules refuse to touch that directory, but that
 * is one layer; this is the second one: a signature the guest cannot forge.
 *
 * The key lives in the Android keystore, which is not a file a root command can read (it is
 * inside the TEE/StrongBox, or at worst an encrypted blob bound to the app's uid and the device).
 * Signing is HMAC-SHA256 over the canonical JSON.
 *
 * What this does **not** protect against, stated plainly: a root command that replaces the app's
 * entire data directory, or one that runs inside the app's own process. It protects the one path
 * that is realistic here — editing the XML in place.
 */
class PoliciesSignature(
    private val prefs: SharedPreferences,
    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) },
) {
    /** True when [json] matches the stored signature. */
    fun verify(json: String): Boolean {
        val stored = prefs.getString(K_SIG, null) ?: return false
        val expected = runCatching { sign(json) }.getOrNull() ?: return false
        // Constant time: a timing side channel here would leak a working signature byte by byte.
        return MessageDigest.isEqual(stored.toByteArray(), expected.toByteArray())
    }

    /** Signs [json] and stores the signature. False when the keystore refused (no key, no TEE). */
    fun store(json: String): Boolean {
        val signature = runCatching { sign(json) }.getOrNull() ?: return false
        prefs.edit().putString(K_SIG, signature).apply()
        return true
    }

    /** Whether any signature exists at all (a fresh install has none). */
    fun present(): Boolean = prefs.getString(K_SIG, null) != null

    private fun sign(json: String): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(key()) }
        return Base64.encodeToString(mac.doFinal(json.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun key(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val K_SIG = "policies_sig"

        /**
         * A separate keystore, so a failure to create the key (rare, but real on devices with a
         * broken keystore) degrades to "unsigned" instead of taking the app down.
         */
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "dsh-phone-policies"
        private const val ALGORITHM = "HmacSHA256"
    }
}
