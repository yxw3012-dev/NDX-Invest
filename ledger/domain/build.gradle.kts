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
    testImplementation(kotlin("test"))
}

tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
    }
}

val checkLedgerBoundaries by tasks.registering {
    group = "verification"
    description = "Rejects forbidden numeric and platform dependencies in the ledger domain."
    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val forbiddenNumber = Regex("\\b(Float|Double)\\b")
        val forbiddenImport = Regex(
            "^import (android\\.|androidx\\.|retrofit2\\.|okhttp3\\.|kotlinx\\.coroutines\\.)",
            RegexOption.MULTILINE,
        )
        val violations = sources.files.flatMap { source ->
            val text = source.readText()
            buildList {
                if (forbiddenNumber.containsMatchIn(text)) add("${source.path}: Float/Double")
                if (forbiddenImport.containsMatchIn(text)) add("${source.path}: forbidden import")
            }
        }
        check(violations.isEmpty()) { violations.joinToString(separator = "\n") }
    }
}

tasks.named("check") {
    dependsOn(checkLedgerBoundaries)
}
