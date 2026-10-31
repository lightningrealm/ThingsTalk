import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    wasmJs {
        outputModuleName = "thingstalk"
        browser {
            commonWebpackConfig {
                outputFileName = "thingstalk.js"
            }
        }
        binaries.executable()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    android {
       namespace = "com.lr.thingstalk.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    applyDefaultHierarchyTemplate {
        common {
            group("mobile") {
                withIos()
                // Also matches the Android target provided by the new AGP KMP plugin.
                withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    sourceSets {
        // iOS-specific BLE adapters implement contracts declared by the shared mobile layer.
        iosMain.get().dependsOn(getByName("mobileMain"))
        getByName("mobileMain").dependencies {
            implementation(libs.kable.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        commonMain.dependencies {
            implementation(libs.navigation3.ui)
            implementation(libs.ktor.mqtt.core)
            implementation(libs.ktor.mqtt.client)
            implementation(libs.ktor.mqtt.client.ws)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.websockets)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

// One bilingual catalog serves composable labels and synchronous callbacks alike.
// Generated accessors live in build/, never in the source tree. No platform locale
// mutation or internal Compose resource API is needed for an in-app language switch.
val translationSources = layout.projectDirectory.dir("src/commonMain/localization")
val translationOutput = layout.buildDirectory.dir("generated/thingstalkTranslations")
val generateTranslations by tasks.registering {
    inputs.dir(translationSources)
    outputs.dir(translationOutput)
    val sourceDirectory = translationSources.asFile
    val outputFile = translationOutput.get().file("com/lr/thingstalk/presentation/i18n/AppStrings.kt").asFile
    doLast {
        fun read(language: String): Map<String, String> {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val document = factory.newDocumentBuilder().parse(sourceDirectory.resolve("strings.$language.xml"))
            val nodes = document.getElementsByTagName("string")
            val values = linkedMapOf<String, String>()
            for (index in 0 until nodes.length) {
                val node = nodes.item(index) as org.w3c.dom.Element
                val key = node.getAttribute("name")
                check(key.matches(Regex("[a-z][a-z0-9_]*"))) { "Invalid translation key: $key" }
                check(values.put(key, node.textContent) == null) { "Duplicate translation: $key" }
            }
            return values
        }
        fun quote(value: String): String = "\"" + value.replace("\\", "\\\\")
            .replace("\"", "\\\"").replace("$", "\\$").replace("\n", "\\n")
            .replace("\r", "\\r").replace("\t", "\\t") + "\""
        val chinese = read("zh")
        val english = read("en")
        check(chinese.keys == english.keys) { "Chinese and English translation keys differ" }
        val parameter = Regex("%([1-9][0-9]*)\\\$s")
        val output = outputFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("package com.lr.thingstalk.presentation.i18n")
            appendLine("// Generated from src/commonMain/localization. Do not edit.")
            appendLine("internal object AppStrings {")
            for ((key, zh) in chinese) {
                val en = english.getValue(key)
                check(parameter.findAll(zh).map { it.value }.toList().sorted() == parameter.findAll(en).map { it.value }.toList().sorted()) {
                    "Translation placeholders differ: $key"
                }
                appendLine("    val $key get() = UiString(${quote(key)}, ${quote(zh)}, ${quote(en)})")
            }
            appendLine("    fun catalog(): List<UiString> = listOf(")
            chinese.keys.forEach { appendLine("        $it,") }
            appendLine("    )")
            appendLine("}")
        })
    }
}
kotlin.sourceSets.commonMain { kotlin.srcDir(translationOutput) }
tasks.configureEach {
    if (name.startsWith("compile")) dependsOn(generateTranslations)
}
