package com.lr.thingstalk.presentation.i18n

internal fun choiceLabel(id: String): String = when (id) {
    "choice:all_778fc8" -> tr(AppStrings.all_778fc8)
    "choice:system_errors_68b07d" -> tr(AppStrings.system_errors_68b07d)
    "choice:device_events_814c23" -> tr(AppStrings.device_events_814c23)
    "choice:traffic_7c4ff3" -> tr(AppStrings.traffic_7c4ff3)
    "choice:unread_1e230a" -> tr(AppStrings.unread_1e230a)
    "choice:needs_attention_59a9eb" -> tr(AppStrings.needs_attention_59a9eb)
    "choice:little_endian_least_significant_byte_first_b5faae" -> tr(AppStrings.little_endian_least_significant_byte_first_b5faae)
    "choice:big_endian_most_significant_byte_first_5dac30" -> tr(AppStrings.big_endian_most_significant_byte_first_5dac30)
    "choice:binary_78ff74" -> tr(AppStrings.binary_78ff74)
    "choice:json_object_6b8049" -> tr(AppStrings.json_object_6b8049)
    "choice:little_endian_796b46" -> tr(AppStrings.little_endian_796b46)
    "choice:big_endian_57167f" -> tr(AppStrings.big_endian_57167f)
    "choice:decode_payload_fdb236" -> tr(AppStrings.decode_payload_fdb236)
    "choice:encode_payload_b6dab1" -> tr(AppStrings.encode_payload_b6dab1)
    "choice:json_text_fac550" -> tr(AppStrings.json_text_fac550)
    "choice:7_days_e45a81" -> tr(AppStrings.text_7_days_e45a81)
    "choice:30_days_9401ee" -> tr(AppStrings.text_30_days_9401ee)
    "choice:90_days_3bf6f8" -> tr(AppStrings.text_90_days_3bf6f8)
    else -> id
}
