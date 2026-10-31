package com.lr.thingstalk.data.mqtt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private var profileContext: Context? = null
fun initializeMqttProfileStorage(context: Context) { profileContext = context.applicationContext }
actual fun createMqttProfileStorage(): MqttProfileStorage = object : MqttProfileStorage {
    override val passwordsPersisted = true
    private val alias = "thingstalk.mqtt.profiles.v1"
    private fun file() = AtomicFile(File(checkNotNull(profileContext).noBackupFilesDir, "mqtt-profiles.enc"))
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "MQTT encryption key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        val bytes = try { file().openRead().use { check(it.available() <= 1_048_576); it.readBytes() } }
            catch (_: java.io.FileNotFoundException) { return@withContext null }
        require(bytes.size >= 29 && bytes[0] == 1.toByte())
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            doFinal(bytes.copyOfRange(13, bytes.size)).decodeToString()
        }
    }
    override suspend fun save(source: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(source.encodeToByteArray())
        val target = file(); val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) }
        catch (failure: Exception) { target.failWrite(output); throw failure }
    }
}
