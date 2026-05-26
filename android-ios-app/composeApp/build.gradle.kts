import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import gobley.gradle.cargo.dsl.*
import gobley.gradle.cargo.tasks.CargoBuildTask
import gobley.gradle.rust.targets.RustAppleMobileTarget
import java.util.Properties

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

    builds.android {
        dynamicLibraries.add("c++_shared")
    }
}

val whisperNdkVersion = "30.0.14904198"
val whisperIsWindows: Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val whisperHostTriplet: String = when {
    whisperIsWindows -> "windows-x86_64"
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ->
        "darwin-x86_64"
    else -> "linux-x86_64"
}
val whisperNdkRoot: String? = run {
    val localProps = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { stream -> localProps.load(stream) }
    }
    val pinnedNdk: String? = localProps.getProperty("ndk.dir")
    if (pinnedNdk != null) return@run pinnedNdk
    val sdkDir: String? = localProps.getProperty("sdk.dir")
    if (sdkDir != null) return@run "$sdkDir/ndk/$whisperNdkVersion"
    System.getenv("ANDROID_NDK_ROOT") ?: System.getenv("ANDROID_NDK_HOME")
}
val whisperNdkToolchainRoot: String? = whisperNdkRoot?.let {
    "$it/toolchains/llvm/prebuilt/$whisperHostTriplet"
}
val whisperNdkToolchainBin: String? = whisperNdkToolchainRoot?.let { "$it/bin" }
val whisperNdkLibclangPath: String? = whisperNdkToolchainRoot?.let { "$it/lib" }
val whisperAndroidBuildEnv: Map<String, String> = if (whisperNdkToolchainBin == null || whisperNdkLibclangPath == null) emptyMap() else {
    val ext = if (whisperIsWindows) ".cmd" else ""
    val arExt = if (whisperIsWindows) ".exe" else ""
    val apiLevel = "24"
    buildMap {
        put("LIBCLANG_PATH", whisperNdkLibclangPath)
        listOf(
            "aarch64-linux-android"   to "aarch64-linux-android",
            "armv7-linux-androideabi" to "armv7a-linux-androideabi",
            "x86_64-linux-android"    to "x86_64-linux-android",
            "i686-linux-android"      to "i686-linux-android",
        ).forEach { (rustTriplet, ndkPrefix) ->
            val envSafe = rustTriplet.replace('-', '_')
            put("CC_$envSafe",  "$whisperNdkToolchainBin/${ndkPrefix}${apiLevel}-clang$ext")
            put("CXX_$envSafe", "$whisperNdkToolchainBin/${ndkPrefix}${apiLevel}-clang++$ext")
            put("AR_$envSafe",  "$whisperNdkToolchainBin/llvm-ar$arExt")
        }
    }
}
val whisperXcodeToolchainRoot = "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr"
val whisperIphoneOsSdk = "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"
val whisperIphoneSimulatorSdk = "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"
val whisperAppleBuildEnv: Map<String, String> = buildMap {
    put("LIBCLANG_PATH", "$whisperXcodeToolchainRoot/lib")
    listOf(
        "aarch64-apple-ios" to "--target=arm64-apple-ios -isysroot $whisperIphoneOsSdk",
        "aarch64-apple-ios-sim" to "--target=arm64-apple-ios-simulator -isysroot $whisperIphoneSimulatorSdk",
        "x86_64-apple-ios" to "--target=x86_64-apple-ios-simulator -isysroot $whisperIphoneSimulatorSdk",
    ).forEach { (rustTriplet, clangArgs) ->
        val envSafe = rustTriplet.replace('-', '_')
        put("CC_$envSafe", "/usr/bin/clang")
        put("CXX_$envSafe", "/usr/bin/clang++")
        put("AR_$envSafe", "/usr/bin/ar")
        put("BINDGEN_EXTRA_CLANG_ARGS_$envSafe", clangArgs)
    }
}
val whisperBuildEnvPath: List<File> = buildList {
    add(layout.projectDirectory.dir("../mls-rust/.tools").asFile)
    add(file("/Applications/CLion.app/Contents/bin/cmake/mac/aarch64/bin"))
    add(file("/Applications/CLion.app/Contents/bin/cmake/mac/x64/bin"))
    add(file("/Applications/CLion.app/Contents/bin/ninja/mac/aarch64"))
    add(file("/Applications/CLion.app/Contents/bin/ninja/mac/x64"))
}
val whisperNinja: String? = listOf(
    "/Applications/CLion.app/Contents/bin/ninja/mac/aarch64/ninja",
    "/Applications/CLion.app/Contents/bin/ninja/mac/x64/ninja",
).firstOrNull { file(it).exists() }

tasks.withType<CargoBuildTask>().configureEach {
    if (name.contains("Android")) {
        additionalEnvironment.put("CMAKE_GENERATOR", "Ninja")
        whisperNinja?.let { additionalEnvironment.put("CMAKE_MAKE_PROGRAM", it) }
        whisperAndroidBuildEnv.forEach { (k, v) -> additionalEnvironment.put(k, v) }
    }
    if (name.contains("Ios")) {
        additionalEnvironment.put("CMAKE_GENERATOR", if (whisperIsWindows) "Ninja" else "Unix Makefiles")
        whisperAppleBuildEnv.forEach { (k, v) -> additionalEnvironment.put(k, v) }
    }
    whisperBuildEnvPath.forEach { additionalEnvironmentPath.add(it) }
}

val xcodeRustTarget = when {
    System.getenv("SDK_NAME")?.startsWith("iphoneos") == true -> RustAppleMobileTarget.IosArm64
    System.getenv("SDK_NAME")?.startsWith("iphonesimulator") == true &&
            System.getenv("ARCHS")?.split(' ')?.contains("arm64") == true -> RustAppleMobileTarget.IosSimulatorArm64
    System.getenv("SDK_NAME")?.startsWith("iphonesimulator") == true -> RustAppleMobileTarget.IosX64
    else -> null
}

uniffi {
    generateFromLibrary {
        namespace = "mls_core"
        xcodeRustTarget?.let { build.set(it) }
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
