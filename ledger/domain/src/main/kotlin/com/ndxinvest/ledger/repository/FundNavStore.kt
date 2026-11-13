package com.ndxinvest.ledger.repository

import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.NavRecord
import java.time.LocalDate

public interface FundNavStore {
    public suspend fun saveNav(nav: NavRecord)

    public suspend fun findNav(fundId: FundId, navDate: LocalDate): NavRecord?

    public suspend fun findLatestNav(fundId: FundId): NavRecord?
}
