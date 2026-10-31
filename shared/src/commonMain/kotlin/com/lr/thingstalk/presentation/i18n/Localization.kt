package com.lr.thingstalk.presentation.i18n

import androidx.compose.runtime.*
import com.lr.thingstalk.domain.appearance.*

/** Application-owned text only. Never use this to translate payloads or user-authored labels. */
data class UiString(val id: String, val chinese: String, val english: String) {
    fun resolve(language: AppLanguage, vararg arguments: Any?): String {
        val template = if (language == AppLanguage.CHINESE) chinese else english
        // Single-pass substitution: a device name containing "%2$s" stays literal.
        return placeholder.replace(template) { match ->
            val index = match.groupValues[1].toInt() - 1
            require(index in arguments.indices) { "Missing argument for $id" }
            arguments[index]?.toString().orEmpty()
        }
    }
}

private val placeholder = Regex("%([1-9][0-9]*)\\\$s")

/** Snapshot reads in tr() invalidate only the UI scopes that consume translated text. */
internal object UiLanguage {
    var current by mutableStateOf(AppLanguage.ENGLISH)
}

fun tr(resource: UiString, vararg arguments: Any?): String = resource.resolve(UiLanguage.current, *arguments)

@Composable
internal fun LanguageEnvironment(preference: LanguagePreference, content: @Composable () -> Unit) {
    val language = preference.resolve(systemLanguageTag())
    PlatformLanguage(language)
    ProvideLanguage(language, content)
}

@Composable
internal fun ProvideLanguage(language: AppLanguage, content: @Composable () -> Unit) {
    SideEffect { UiLanguage.current = language }
    // Keep the composition identity, back stack, forms and app-owned transport sessions.
    content()
}

@Composable internal expect fun systemLanguageTag(): String
@Composable internal expect fun PlatformLanguage(language: AppLanguage)
