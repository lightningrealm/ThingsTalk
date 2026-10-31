package com.lr.thingstalk.data.mqtt

import com.lr.thingstalk.domain.mqtt.MqttConnectionConfig
import com.lr.thingstalk.domain.mqtt.MqttException
import io.ktor.http.URLProtocol
import io.ktor.http.Url

internal fun MqttConnectionConfig.validated(): MqttConnectionConfig {
    val address = url.trim()
    val parsed = try {
        Url(address)
    } catch (_: Exception) {
        throw MqttException("请输入有效的 WebSocket 地址。")
    }
    mqttRequire(parsed.protocol == URLProtocol.WS || parsed.protocol == URLProtocol.WSS) {
        "MQTT 地址需以 ws:// 或 wss:// 开头。"
    }
    mqttRequire(parsed.host.isNotBlank() && "://" in address) { "MQTT 地址缺少服务器主机名。" }
    mqttRequire(parsed.user == null && parsed.password == null) { "请在用户名和密码字段填写凭证，不要放入地址。" }
    // ktor-mqtt 1.2.0's WebSocket engine forwards the path, but not query parameters.
    mqttRequire('?' !in address && '#' !in address) { "当前连接地址支持路径，暂不支持查询参数或片段。" }
    mqttRequire(address.none { it.isWhitespace() || it.code < 0x20 }) { "MQTT 地址不能包含空格或控制字符。" }
    // Ktor uses 0 as its default-port sentinel, so Url.port silently turns an explicit :0 into 80/443.
    val authority = address.substringAfter("://").substringBefore('/')
    val hasExplicitPort = if (authority.startsWith('[')) {
        authority.substringAfter(']', missingDelimiterValue = "").startsWith(':')
    } else ':' in authority
    if (hasExplicitPort) {
        val explicitPort = authority.substringAfterLast(':').toIntOrNull()
        mqttRequire(explicitPort != null && explicitPort in 1..65535) { "服务器端口必须在 1 至 65535 之间。" }
    }
    mqttRequire(parsed.port in 1..65535) { "服务器端口必须在 1 至 65535 之间。" }
    validateMqttText(clientId, "客户端 ID", allowEmpty = false)
    username?.let { validateMqttText(it, "用户名", allowEmpty = true) }
    password?.let {
        mqttRequire(it.encodeToByteArray().size <= 65535) { "密码的 UTF-8 长度不能超过 65535 字节。" }
    }
    return copy(url = parsed.toString())
}

internal fun validateTopic(topic: String, filter: Boolean) {
    validateMqttText(topic, if (filter) "订阅主题" else "发布主题", allowEmpty = false)
    if (!filter) {
        mqttRequire('#' !in topic && '+' !in topic) { "发布主题不能包含 + 或 # 通配符。" }
        return
    }
    val effectiveFilter = if (topic.startsWith("\$share/")) {
        val remainder = topic.removePrefix("\$share/")
        val separator = remainder.indexOf('/')
        mqttRequire(separator > 0 && separator < remainder.lastIndex) { "共享订阅需要组名和主题，例如 \$share/group/device/#。" }
        val group = remainder.substring(0, separator)
        mqttRequire('+' !in group && '#' !in group) { "共享订阅组名不能包含通配符。" }
        remainder.substring(separator + 1)
    } else topic
    val levels = effectiveFilter.split('/')
    levels.forEachIndexed { index, level ->
        mqttRequire('+' !in level || level == "+") { "+ 通配符必须独占一个主题层级。" }
        mqttRequire('#' !in level || (level == "#" && index == levels.lastIndex)) {
            "# 通配符必须独占最后一个主题层级。"
        }
    }
}

private fun validateMqttText(value: String, label: String, allowEmpty: Boolean) {
    mqttRequire(allowEmpty || value.isNotBlank()) { "${label}不能为空。" }
    mqttRequire(value.encodeToByteArray().size <= 65535) { "${label}的 UTF-8 长度不能超过 65535 字节。" }
    var index = 0
    while (index < value.length) {
        val character = value[index]
        mqttRequire(character != '\u0000') { "${label}不能包含空字符。" }
        when {
            character.isHighSurrogate() -> {
                mqttRequire(index + 1 < value.length && value[index + 1].isLowSurrogate()) { "${label}包含无效的 Unicode 字符。" }
                index++
            }
            character.isLowSurrogate() -> throw MqttException("${label}包含无效的 Unicode 字符。")
        }
        index++
    }
}

internal inline fun mqttRequire(condition: Boolean, message: () -> String) {
    if (!condition) throw MqttException(message())
}
