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
    implementation(project(":market:domain"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
    }
}

val checkProviderBoundaries = tasks.register("checkProviderBoundaries") {
    group = "verification"
    description = "Rejects binary floating-point values, platform APIs, and embedded credential literals in Providers."
    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val forbiddenNumber = Regex("\\b(Float|Double)\\b")
        val forbiddenImport = Regex(
            "^import (android\\.|androidx\\.|retrofit2\\.|okhttp3\\.)",
            RegexOption.MULTILINE,
        )
        val credentialLiteral = Regex(
            "(?i)(api[_-]?key|access[_-]?token|secret)\\s*=\\s*\"(?!\\[REDACTED])[^\"]+\"",
        )
        val retiredProduct = Regex("(?i)\\b(lixinger|NdxValuation|NDX_VALUATION|PeValuation)\\b")
        val violations = sources.files.flatMap { source ->
            val text = source.readText()
            buildList {
                if (forbiddenNumber.containsMatchIn(text)) add("${source.path}: Float/Double")
                if (forbiddenImport.containsMatchIn(text)) add("${source.path}: forbidden platform import")
                if (credentialLiteral.containsMatchIn(text)) add("${source.path}: possible embedded credential")
                if (retiredProduct.containsMatchIn(text)) add("${source.path}: retired PE/Lixinger product scope")
            }
        }
        check(violations.isEmpty()) { violations.joinToString(separator = "\n") }
    }
}

tasks.named("check") {
    dependsOn(checkProviderBoundaries)
}
