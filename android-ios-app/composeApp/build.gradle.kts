import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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
            implementation(libs.cryptography.provider.apple)
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

tasks.register("pushModelsToDevice") {
    description = "Push NLLB-200 translation model to connected devices via adb"
    group = "memegram"

    val modelsRootPath = file("${rootProject.projectDir}/../exported_models").absolutePath
    val localPropsPath = rootProject.file("local.properties").absolutePath
    val appId = "com.example.memegram"
    val internalBase = "files/translation_models"
    val modelDirName = "nllb-200-distilled-600M"

    doLast {
        val modelDir = File(modelsRootPath, modelDirName)
        if (!modelDir.isDirectory) {
            logger.lifecycle("pushModels: exported_models/$modelDirName/ not found -- skip")
            return@doLast
        }

        val localProps = File(localPropsPath)
        val sdkDir: String? = if (localProps.exists()) {
            Properties().apply { load(localProps.inputStream()) }
                .getProperty("sdk.dir")
        } else {
            System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        }
        if (sdkDir == null) {
            logger.warn("pushModels: cannot locate Android SDK")
            return@doLast
        }

        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val adb = File(sdkDir, "platform-tools${File.separator}adb${if (isWin) ".exe" else ""}")
        if (!adb.isFile) {
            logger.warn("pushModels: adb not found at ${adb.absolutePath}")
            return@doLast
        }

        fun runAdb(vararg args: String): Pair<String, Int> {
            val proc = ProcessBuilder(adb.absolutePath, *args)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            return output to exitCode
        }

        val (devicesOutput, _) = runAdb("devices")
        val devices = devicesOutput.lines()
            .filter { it.endsWith("\tdevice") }
            .map { it.split("\t").first() }

        if (devices.isEmpty()) {
            logger.lifecycle("pushModels: no connected devices -- skip")
            return@doLast
        }

        val remoteDirRel = "$internalBase/$modelDirName"
        val files = modelDir.listFiles() ?: return@doLast

        for (device in devices) {
            runAdb("-s", device, "shell", "run-as", appId, "mkdir", internalBase.split("/").first())
            runAdb("-s", device, "shell", "run-as", appId, "mkdir", internalBase)
            runAdb("-s", device, "shell", "run-as", appId, "mkdir", remoteDirRel)

            for (f in files) {
                if (!f.isFile) continue
                val tmpPath = "/data/local/tmp/${f.name}"
                logger.lifecycle("  pushing $modelDirName/${f.name} (${f.length() / 1024 / 1024}MB) -> $device")
                val (pushOut, pushExit) = runAdb("-s", device, "push", f.absolutePath, tmpPath)
                if (pushExit != 0) {
                    logger.warn("  WARN: adb push failed: ${pushOut.trim()}")
                    continue
                }
                val (cpOut, cpExit) = runAdb(
                    "-s", device, "shell",
                    "run-as", appId, "cp", tmpPath, "$remoteDirRel/${f.name}"
                )
                if (cpExit != 0) {
                    val (catOut, catExit) = runAdb(
                        "-s", device, "shell",
                        "run-as", appId, "sh", "-c",
                        "cat $tmpPath > $remoteDirRel/${f.name}"
                    )
                    if (catExit != 0) {
                        logger.warn("  WARN: copy failed: ${catOut.trim()}")
                    }
                }
                runAdb("-s", device, "shell", "rm", "-f", tmpPath)
            }
            logger.lifecycle("  $device: done")
        }
        logger.lifecycle("pushModels: done")
    }
}

afterEvaluate {
    tasks.named("installDebug") {
        finalizedBy("pushModelsToDevice")
    }
}