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

val checkMarketBoundaries = tasks.register("checkMarketBoundaries") {
    group = "verification"
    description = "Rejects binary floating-point values and platform/provider imports in market calculations."
    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val forbiddenNumber = Regex("\\b(Float|Double)\\b")
        val retiredValuation = Regex("\\b(NdxValuation|PeValuationBand|PeValuationClassifier)\\b")
        val forbiddenImport = Regex(
            "^import (android\\.|androidx\\.|retrofit2\\.|okhttp3\\.|kotlinx\\.coroutines\\.)",
            RegexOption.MULTILINE,
        )
        val violations = sources.files.flatMap { source ->
            val text = source.readText()
            buildList {
                if (forbiddenNumber.containsMatchIn(text)) add("${source.path}: Float/Double")
                if (forbiddenImport.containsMatchIn(text)) add("${source.path}: forbidden import")
                if (retiredValuation.containsMatchIn(text)) add("${source.path}: retired PE valuation scope")
            }
        }
        check(violations.isEmpty()) { violations.joinToString(separator = "\n") }
    }
}

tasks.named("check") {
    dependsOn(checkMarketBoundaries)
}
