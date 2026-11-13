package com.ndxinvest.ledger.repository

import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.FundRuleVersion
import com.ndxinvest.ledger.model.RuleVersionId

public interface FundRuleVersionStore {
    public suspend fun saveRuleVersion(ruleVersion: FundRuleVersion)

    public suspend fun findRuleVersion(ruleVersionId: RuleVersionId): FundRuleVersion?

    public suspend fun loadRuleVersions(fundId: FundId): List<FundRuleVersion>
}
