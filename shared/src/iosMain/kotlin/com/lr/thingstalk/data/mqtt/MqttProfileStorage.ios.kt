@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package com.lr.thingstalk.data.mqtt

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*
import platform.posix.memcpy

actual fun createMqttProfileStorage(): MqttProfileStorage = object : MqttProfileStorage {
    override val passwordsPersisted = true
    private fun query(): CFMutableDictionaryRef {
        val dict = checkNotNull(CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr))
        CFDictionarySetValue(dict, kSecClass, kSecClassGenericPassword)
        setObject(dict, kSecAttrService, "com.lr.thingstalk.mqtt.profiles")
        setObject(dict, kSecAttrAccount, "archive-v1")
        return dict
    }
    private fun setObject(dict: CFMutableDictionaryRef, key: CFStringRef?, value: Any) {
        val ref = CFBridgingRetain(value)
        try { CFDictionarySetValue(dict, key, ref) } finally { if (ref != null) CFRelease(ref) }
    }
    override suspend fun load(): String? {
        val query = query()
        try {
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            return memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                if (status == errSecItemNotFound) return@memScoped null
                check(status == errSecSuccess) { "Keychain read failed" }
                val data = CFBridgingRelease(result.value) as? NSData ?: error("Invalid Keychain data")
                check(data.length <= 1_048_576uL)
                val bytes = ByteArray(data.length.toInt())
                if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
                bytes.decodeToString()
            }
        } finally { CFRelease(query) }
    }
    override suspend fun save(source: String) {
        val query = query()
        val attrs = checkNotNull(CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr))
        try {
            val bytes = source.encodeToByteArray()
            val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
            setObject(attrs, kSecValueData, data)
            CFDictionarySetValue(attrs, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            var status = SecItemUpdate(query, attrs)
            if (status == errSecItemNotFound) {
                setObject(query, kSecValueData, data)
                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                status = SecItemAdd(query, null)
            }
            check(status == errSecSuccess) { "Keychain save failed" }
        } finally { CFRelease(attrs); CFRelease(query) }
    }
}
