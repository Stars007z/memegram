import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.googleServices)
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
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.security.crypto.ktx)
            implementation(libs.mlkit.language.id)
            implementation(libs.onnxruntime.android)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.barcode.scanning)
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.messaging)
            implementation("androidx.lifecycle:lifecycle-process:2.10.0")
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
            implementation(libs.coroutines.extensions1)
            implementation(libs.qrose)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.native.driver)
            implementation(libs.cryptography.core)
            // CryptoKit provider supports AES-GCM on Apple platforms
            // (Apple/CommonCrypto provider only supports CBC/CTR/ECB).
            implementation(libs.cryptography.provider.cryptokit)
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

    sourceSets["main"].jniLibs.srcDirs(
        "build/intermediates/rust/aarch64-linux-android/debug",
        "build/intermediates/rust/armv7-linux-androideabi/debug",
        "build/intermediates/rust/i686-linux-android/debug",
        "build/intermediates/rust/x86_64-linux-android/debug",
    )

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
            dialect(libs.sqldelight.dialect.sqlite324)
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

// NLLB-200 translation model is downloaded at runtime from Cloudflare R2
// (https://models.memegram.win) — no local push to device required.
