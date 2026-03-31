import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    id("dev.gobley.cargo")  version "0.3.7"
    id("dev.gobley.uniffi") version "0.3.7"
    id("org.jetbrains.kotlin.plugin.atomicfu") version "2.3.20"
    id("app.cash.sqldelight") version "2.3.2"
}

cargo {
    packageDirectory = layout.projectDirectory.dir("../mls-rust")
}

uniffi {
    generateFromLibrary {
        namespace = "mls_core"
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation("app.cash.sqldelight:android-driver:2.3.2")
            implementation("net.zetetic:sqlcipher-android:4.14.0")
            implementation("androidx.security:security-crypto-ktx:1.1.0")
        }

        commonMain.dependencies {
            implementation(libs.runtime)
            implementation(libs.foundation)
            implementation(libs.material3)
            implementation(libs.ui)
            implementation(libs.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.material.icons.extended)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.navigation.compose)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.multiplatform.settings.noarg)
            implementation(libs.kotlinx.datetime)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.filekit.compose)
            implementation(libs.multiplatform.crypto.libsodium)
            implementation("app.cash.sqldelight:coroutines-extensions:2.3.2")
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation("app.cash.sqldelight:native-driver:2.3.2")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
    sourceSets.commonMain.dependencies {
        implementation(kotlin("test"))
    }
}

android {
    namespace = "com.example.memegram"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.example.memegram"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.example.memegram.database")
            generateAsync.set(false)
        }
    }
}