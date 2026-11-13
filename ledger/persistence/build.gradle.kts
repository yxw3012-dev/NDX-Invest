plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":ledger:domain"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.androidx.room3.testing)
    testImplementation(libs.androidx.sqlite.bundled)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
    }
}

val checkLedgerPersistenceBoundaries = tasks.register("checkLedgerPersistenceBoundaries") {
    group = "verification"
    description = "Rejects binary floating-point values in ledger persistence mappings."
    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val forbiddenNumber = Regex("\\b(Float|Double)\\b")
        val violations = sources.files.filter { forbiddenNumber.containsMatchIn(it.readText()) }
        check(violations.isEmpty()) {
            violations.joinToString(separator = "\n") { "${it.path}: Float/Double" }
        }
    }
}

tasks.named("check") {
    dependsOn(checkLedgerPersistenceBoundaries)
}
