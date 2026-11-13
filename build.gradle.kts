plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room3) apply false
}

tasks.register("ledgerGate") {
    group = "verification"
    description = "Runs the complete ledger domain and persistence gate."
    dependsOn(":ledger:domain:check", ":core:database:check", ":ledger:persistence:check")
}

tasks.register("marketDomainGate") {
    group = "verification"
    description = "Runs the offline market calculation gate before Provider work."
    dependsOn(":market:domain:check")
}

tasks.register("providerContractGate") {
    group = "verification"
    description = "Runs the v1.1 data gate plus Android security compilation and checks."
    dependsOn("v11DataGate", "androidSecurityGate")
}

tasks.register("v11DataGate") {
    group = "verification"
    description = "Runs the complete v1.1 Gate D ledger, market, network, Provider, and scope regression."
    dependsOn("ledgerGate", "marketDomainGate", "v11ScopeGate", ":core:network:check", ":data:providers:check")
}

tasks.register("v11ScopeGate") {
    group = "verification"
    description = "Rejects retired PE/Lixinger product code and executable tools."
    val productSources = fileTree(projectDir) {
        include("**/src/main/**/*.kt")
        include("scripts/**")
        exclude("**/build/**")
        exclude("**/__pycache__/**")
        exclude("**/*.pyc")
    }
    inputs.files(productSources)
    doLast {
        val retiredScope = Regex(
            "(?i)\\b(lixinger|NdxValuation|NDX_VALUATION|PeValuation|PeValuationWorker)\\b",
        )
        val violations = productSources.files.filter { retiredScope.containsMatchIn(it.readText()) }
        check(violations.isEmpty()) {
            violations.joinToString(separator = "\n") { "${it.path}: retired PE/Lixinger scope" }
        }
    }
}

tasks.register("androidSecurityGate") {
    group = "verification"
    description = "Compiles Android Keystore integration and runs deterministic security adapter checks."
    dependsOn(":core:security:check", ":core:security:assembleDebugAndroidTest")
}
