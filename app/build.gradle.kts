import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
}

// Optional side-by-side package: retains Production behavior and uses separate app-private storage.
val parallelInstall = providers.gradleProperty("ndxParallelInstall").orNull == "true"

android {
    buildFeatures { buildConfig = true }
    namespace = "com.ndxinvest.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.ndxinvest.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "dataMode"
    productFlavors {
        create("prototype") {
            dimension = "dataMode"
            applicationIdSuffix = ".prototype"
            versionNameSuffix = "-prototype"
        }
        create("production") {
            dimension = "dataMode"
            if (parallelInstall) {
                applicationIdSuffix = ".parallel"
            }
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.testLogging { events("passed", "skipped", "failed") } }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(project(":yjb:read"))
    implementation(project(":core:security"))
    implementation(project(":core:network"))
    implementation(project(":data:providers"))
    implementation(project(":market:domain"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    androidTestImplementation(project(":core:database"))
    androidTestImplementation(libs.androidx.room3.runtime)
    androidTestImplementation(libs.androidx.sqlite.bundled)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Preserve the existing resolved WorkManager dependencies after removing
    // the unused legacy Room3 runtime. Constraints do not introduce new libraries.
    constraints {
        implementation("androidx.collection:collection-jvm:1.5.0")
        implementation("androidx.sqlite:sqlite-android:2.7.0")
        implementation("androidx.sqlite:sqlite-framework-android:2.7.0")
    }

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp)
}

abstract class StageFrontendV2Task : DefaultTask() {
    @get:InputFile abstract val entry: RegularFileProperty
    @get:InputFile abstract val backAdapter: RegularFileProperty
    @get:InputFile abstract val readAdapter: RegularFileProperty
    @get:InputFile abstract val marketAdapter: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun stage() {
        val bytes = entry.get().asFile.readBytes()
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == "398b29d45cac2681c60c0acaaae987b61232e9e84bfee51ae6088ca48d964613") {
            "Frontend V2 differs from the accepted frozen baseline. Re-accept UI changes before packaging."
        }
        val target = outputDirectory.dir("frontend-v2").get().asFile
        target.mkdirs()
        target.resolve("index.html").writeBytes(bytes)
        target.resolve("android-back.js").writeBytes(backAdapter.get().asFile.readBytes())
        target.resolve("production-read.js").writeBytes(readAdapter.get().asFile.readBytes())
        target.resolve("production-market.js").writeBytes(marketAdapter.get().asFile.readBytes())
    }
}
val stageFrontendV2 = tasks.register<StageFrontendV2Task>("stageFrontendV2") {
    entry.set(rootProject.layout.projectDirectory.file("frontend-v2/index.html"))
    backAdapter.set(rootProject.layout.projectDirectory.file("frontend-v2/android-back.js"))
    readAdapter.set(rootProject.layout.projectDirectory.file("frontend-v2/production-read.js"))
    marketAdapter.set(rootProject.layout.projectDirectory.file("frontend-v2/production-market.js"))
    outputDirectory.set(layout.buildDirectory.dir("generated/frontendV2Assets"))
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(stageFrontendV2, StageFrontendV2Task::outputDirectory)
}
