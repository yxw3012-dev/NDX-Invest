plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":ledger:domain"))
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    ksp(libs.androidx.room3.compiler)

    testImplementation(kotlin("test"))
    testImplementation(libs.androidx.room3.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

tasks.test {
    systemProperty("ndx.roomSchemaDir", file("schemas").absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
    }
}

val checkLedgerStorageBoundaries by tasks.registering {
    group = "verification"
    description = "Rejects binary floating-point ledger storage and SQLite REAL affinity."
    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    val schemas = fileTree("schemas") { include("**/*.json") }
    inputs.files(sources, schemas)
    doLast {
        val forbiddenNumber = Regex("\\b(Float|Double)\\b")
        val realAffinity = Regex("\\\"affinity\\\"\\s*:\\s*\\\"REAL\\\"")
        val violations = buildList {
            sources.files.forEach { source ->
                if (forbiddenNumber.containsMatchIn(source.readText())) add("${source.path}: Float/Double")
            }
            schemas.files.forEach { schema ->
                if (realAffinity.containsMatchIn(schema.readText())) add("${schema.path}: SQLite REAL affinity")
            }
        }
        check(violations.isEmpty()) { violations.joinToString(separator = "\n") }
    }
}

tasks.named("check") {
    dependsOn(checkLedgerStorageBoundaries)
}
