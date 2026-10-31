package com.lr.thingstalk.presentation.activity
import android.content.Context
private var buildLabel = "版本信息不可用"
fun initializeAppBuild(context: Context) {
    buildLabel = runCatching { val info = context.packageManager.getPackageInfo(context.packageName, 0); "${info.versionName} (${info.versionCode})" }.getOrDefault("版本信息不可用")
}
actual fun appBuildLabel() = buildLabel
