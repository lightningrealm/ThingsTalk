package com.lr.thingstalk.presentation.i18n

import com.lr.thingstalk.domain.appearance.AppLanguage

/**
 * Compatibility adapter for existing repository error strings and persisted event summaries.
 * Match complete, known application messages only. Unknown OS/library errors are preserved.
 * Never call this on device names, imported labels, topics or received payloads.
 */
internal fun diagnosticText(source: String): String = DiagnosticTranslations.resolve(source, UiLanguage.current)

internal object DiagnosticTranslations {
    private val placeholder = Regex("%([1-9][0-9]*)\\\$s")
    private val catalog by lazy { AppStrings.catalog() }
    private val exact by lazy {
        buildMap<String, UiString> {
            catalog.filter { !placeholder.containsMatchIn(it.chinese) }.forEach {
                if (it.chinese !in this) put(it.chinese, it)
                if (it.english !in this) put(it.english, it)
            }
        }
    }
    private data class Template(val resource: UiString, val pattern: Regex, val arguments: List<Int>)
    private val templates by lazy {
        catalog.flatMap { resource ->
            listOf(resource.chinese, resource.english).mapNotNull { text ->
                val matches = placeholder.findAll(text).toList()
                if (matches.isEmpty()) return@mapNotNull null
                var cursor = 0
                val pattern = buildString {
                    matches.forEach { match ->
                        append(Regex.escape(text.substring(cursor, match.range.first)))
                        append("(.*?)")
                        cursor = match.range.last + 1
                    }
                    append(Regex.escape(text.substring(cursor)))
                }
                Template(resource, Regex(pattern, RegexOption.DOT_MATCHES_ALL), matches.map { it.groupValues[1].toInt() - 1 })
            }
        }.sortedByDescending { it.pattern.pattern.length }
    }

    // Only these slots contain another application diagnostic. Other arguments may be
    // user-authored field names or topics, even when they happen to match a catalog entry.
    private val nestedCause = mapOf(
        "读取失败：%1\$s" to 0,
        "通知订阅失败：%1\$s" to 0,
        "导入失败：%1\$s" to 0,
        "导出失败：%1\$s" to 0,
        "文件导入失败：%1\$s" to 0,
        "文件导出失败：%1\$s" to 0,
        "字段 %1\$s：%2\$s" to 1,
        "%1\$s 的默认值无效：%2\$s" to 1,
    )

    fun resolve(source: String, language: AppLanguage): String = resolve(source, language, 0)

    private fun resolve(source: String, language: AppLanguage, depth: Int): String {
        exact[source]?.let { return it.resolve(language) }
        // Diagnostics are bounded; no payload parsing or unbounded cache of user data.
        if (source.length > 8192) return source
        for (template in templates) {
            val match = template.pattern.matchEntire(source) ?: continue
            val values = arrayOfNulls<String>(template.arguments.max() + 1)
            template.arguments.forEachIndexed { index, argument -> values[argument] = match.groupValues[index + 1] }
            if (depth < 4) nestedCause[template.resource.chinese]?.let { index ->
                values[index] = resolve(values[index].orEmpty(), language, depth + 1)
            }
            return template.resource.resolve(language, *values)
        }
        return source
    }
}
