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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
    }
}

val checkNetworkBoundaries = tasks.register("checkNetworkBoundaries") {
    group = "verification"
    description = "Rejects cleartext endpoints, unsafe logging, and embedded credential literals in network code."
    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val forbidden = listOf(
            Regex("\\b(Float|Double)\\b") to "Float/Double",
            Regex("HttpLoggingInterceptor|println\\s*\\(") to "unsafe network logging",
            Regex("http://") to "cleartext endpoint",
            Regex("(?i)(api[_-]?key|access[_-]?token|secret)\\s*=\\s*\"(?!\\[REDACTED])[^\"]+\"") to
                "possible embedded credential",
        )
        val violations = sources.files.flatMap { source ->
            val text = source.readText()
            forbidden.mapNotNull { (pattern, label) ->
                if (pattern.containsMatchIn(text)) "${source.path}: $label" else null
            }
        }
        check(violations.isEmpty()) { violations.joinToString(separator = "\n") }
    }
}

tasks.named("check") {
    dependsOn(checkNetworkBoundaries)
}
