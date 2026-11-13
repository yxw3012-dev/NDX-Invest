package com.ndxinvest.yjb.buy

/** Credential-free release metadata from the real POST/GET observation, not guessed server maxima. */
object BundledYjbWriteProtocol {
    const val REVISION = "yjb-existing-position-2026-10-03-v1"
    const val SOURCE = "docs/yjb-phase2/PRECISION_ONLY_STATUS.md"
    val precision = VerifiedWritePrecision(
        "$REVISION;USER_CONFIRMED_REAL_REPLACEMENT;REAL_POST_GET_2026_10_03;OBSERVED_OPERATING_SCALES;SOURCE:$SOURCE",
        shareScale = 2,
        unitCostScale = 8,
    )
}
