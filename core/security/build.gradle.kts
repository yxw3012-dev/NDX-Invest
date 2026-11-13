plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.ndxinvest.security"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.testLogging {
                events("passed", "skipped", "failed")
            }
        }
    }
}

dependencies {
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

val checkAndroidSecurityBoundaries = tasks.register("checkAndroidSecurityBoundaries") {
    group = "verification"
    description = "Rejects plaintext credential persistence and weakened Android Keystore parameters."
    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val allSource = sources.files.joinToString(separator = "\n") { it.readText() }
        val violations = buildList {
            if (Regex("SharedPreferences|DataStore|RoomDatabase").containsMatchIn(allSource)) {
                add("Credential persistence must remain isolated from preferences, DataStore, and Room")
            }
            if (Regex("ECB|CBC|PKCS5Padding|PKCS7Padding").containsMatchIn(allSource)) {
                add("Credential encryption must remain AES-GCM without legacy block modes or padding")
            }
            if (!allSource.contains("setKeySize(KEY_SIZE_BITS)")) add("Keystore key size must be explicit")
            if (!allSource.contains("setRandomizedEncryptionRequired(true)")) {
                add("Keystore must require randomized encryption")
            }
            if (!allSource.contains("noBackupFilesDir")) add("Ciphertext records must remain under noBackupFilesDir")
        }
        check(violations.isEmpty()) { violations.joinToString(separator = "\n") }
    }
}

tasks.named("check") {
    dependsOn(checkAndroidSecurityBoundaries)
}
