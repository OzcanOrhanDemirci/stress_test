import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing details, taken from a file that is never committed or, on a
 * build server, from the environment (docs/RELEASE.md).
 *
 * When neither is present the release build is signed with the debug key, so
 * that anyone can clone this repository and produce a working package. What
 * they cannot produce is a package that updates an installation of the
 * released one, which is the only thing the key protects.
 */
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun signingDetail(property: String, variable: String): String? =
    signing.getProperty(property) ?: System.getenv(variable)

val releaseStore = signingDetail("storeFile", "STRESS_KEYSTORE_FILE")

android {
    namespace = "dev.ozcan.stress"
    compileSdk = 36
    // Pinning the NDK keeps the assembler that builds the kernels the one
    // they were measured with.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.ozcan.stress"
        // Android 10: Vulkan 1.1 on every 64-bit phone, and the thermal status API.
        minSdk = 29
        targetSdk = 36
        versionCode = providers.gradleProperty("stress.versionCode").get().toInt()
        versionName = providers.gradleProperty("stress.versionName").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    signingConfigs {
        create("release") {
            if (releaseStore != null) {
                storeFile = file(releaseStore)
                storePassword = signingDetail("storePassword", "STRESS_KEYSTORE_PASSWORD")
                keyAlias = signingDetail("keyAlias", "STRESS_KEY_ALIAS")
                keyPassword = signingDetail("keyPassword", "STRESS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // A separate package, so instrumented tests never replace or wipe
            // the installed app and its run history.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseStore != null) "release" else "debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // The language can be changed inside the app, so an app bundle must keep
    // every language in the base package rather than split them by device.
    bundle {
        language {
            enableSplit = false
        }
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
