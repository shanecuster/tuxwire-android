package com.shanetalkstech.tuxwire.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What the person typed into the sync screen, as read back from storage.
 *
 * @property enabled the on/off switch. `false` unless the person turned it on.
 * @property url the server address (no login inside it).
 * @property database the CouchDB database name.
 * @property username the login name.
 * @property password the password in plain text, only ever held in memory; an
 *   empty string when none is saved or it could not be read back.
 * @property feeds the separate switch for syncing the feed list too. `false`
 *   unless the person turned it on, and it only counts while [enabled] is
 *   also on. It is its own switch because a feed list shows what you read.
 * @property readMarks the separate switch for syncing which articles you have
 *   opened. `false` unless the person turned it on, and it only counts while
 *   [enabled] is also on. Its own switch because it records what you read.
 */
data class SyncSettings(
    val enabled: Boolean = false,
    val url: String = "",
    val database: String = "",
    val username: String = "",
    val password: String = "",
    val feeds: Boolean = false,
    val readMarks: Boolean = false,
)

/**
 * Keeps the optional sync settings on this phone, and nowhere else.
 *
 * - The plain settings (switch, address, database, login name) live in the
 *   app's private preferences file, which other apps cannot read. The app also
 *   opts out of Google's cloud backups, so none of it leaves the phone.
 * - The **password** is never written in plain text. It is encrypted with
 *   AES-GCM using a key that lives in the Android Keystore: the key is created
 *   on the phone, cannot be exported, and only this app can use it. Only the
 *   encrypted bytes go into the preferences file. No extra library is needed;
 *   this uses the platform's own classes.
 * - Nothing here ever logs or shows a saved password.
 *
 * Sync is off by default: with nothing saved, [load] returns `enabled = false`.
 *
 * @param context any context; only its application is kept.
 */
class SyncStore(context: Context) {

    /** The app's private preferences file for sync settings. */
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Reads the saved settings. If the password cannot be decrypted (for
     * example the Keystore key was lost after a device reset), the password
     * comes back empty and the person simply types it again.
     */
    fun load(): SyncSettings = SyncSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        url = prefs.getString(KEY_URL, "").orEmpty(),
        database = prefs.getString(KEY_DATABASE, "").orEmpty(),
        username = prefs.getString(KEY_USERNAME, "").orEmpty(),
        password = prefs.getString(KEY_PASSWORD, null)?.let { decrypt(it) }.orEmpty(),
        // A missing value (an older install) reads as off.
        feeds = prefs.getBoolean(KEY_FEEDS, false),
        readMarks = prefs.getBoolean(KEY_READ_MARKS, false),
    )

    /**
     * Saves the settings. An empty password removes the saved one. The write
     * uses `commit()` (wait until it is on disk) so a setting is not lost if
     * the app is closed right after the person taps save.
     */
    fun save(settings: SyncSettings) {
        val editor = prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_URL, settings.url.trim())
            .putString(KEY_DATABASE, settings.database.trim())
            .putString(KEY_USERNAME, settings.username.trim())
            .putBoolean(KEY_FEEDS, settings.feeds)
            .putBoolean(KEY_READ_MARKS, settings.readMarks)
        if (settings.password.isEmpty()) {
            editor.remove(KEY_PASSWORD)
        } else {
            editor.putString(KEY_PASSWORD, encrypt(settings.password))
        }
        editor.commit()
    }

    /**
     * Forgets everything: the settings, the encrypted password and the
     * Keystore key itself. Afterwards sync is off and nothing is saved.
     */
    fun clear() {
        prefs.edit().clear().commit()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    /** Opens the Android Keystore. */
    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /**
     * Returns the app's encryption key, creating it the first time. The key
     * is marked for encrypting and decrypting only, with AES-GCM and no
     * padding, and never leaves the Keystore.
     */
    private fun key(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /**
     * Encrypts [plain] and returns text safe to store: the random starting
     * value (IV), then a colon, then the encrypted bytes, both as base64.
     * A fresh IV is made for every encryption, as AES-GCM requires.
     */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * The reverse of [encrypt]. Returns `null` for anything that cannot be
     * read (damaged text, a lost key); it never throws.
     */
    private fun decrypt(stored: String): String? = runCatching {
        val parts = stored.split(":")
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val bytes = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(bytes), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        /** Name of the private preferences file. */
        const val PREFS_NAME = "tuxwire_sync"
        const val KEY_ENABLED = "enabled"
        const val KEY_URL = "url"
        const val KEY_DATABASE = "database"
        const val KEY_USERNAME = "username"
        /** The separate "also sync the feed list" switch. */
        const val KEY_FEEDS = "feeds"
        /** The separate "also sync which articles I opened" switch. */
        const val KEY_READ_MARKS = "read_marks"
        const val KEY_PASSWORD = "password_encrypted"

        /** The Keystore provider name and this app's key name inside it. */
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "tuxwire_sync_password"

        /** AES in GCM mode with no padding. */
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
