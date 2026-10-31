package com.lr.thingstalk.domain.appearance

import kotlinx.serialization.Serializable

@Serializable
enum class LanguagePreference { SYSTEM, SIMPLIFIED_CHINESE, ENGLISH }

enum class AppLanguage(val tag: String) { CHINESE("zh-Hans"), ENGLISH("en") }

/** Chinese locales use Simplified Chinese; all other languages fall back to English. */
fun LanguagePreference.resolve(systemTag: String): AppLanguage = when (this) {
    LanguagePreference.SIMPLIFIED_CHINESE -> AppLanguage.CHINESE
    LanguagePreference.ENGLISH -> AppLanguage.ENGLISH
    LanguagePreference.SYSTEM -> if (systemTag.replace('_', '-').substringBefore('-').equals("zh", true))
        AppLanguage.CHINESE else AppLanguage.ENGLISH
}
