package com.ndxinvest.database

/** Read under one Room transaction; no historical facts are rewritten. */
public data class LedgerHistoryRows public constructor(
    public val transactions: List<LedgerTransactionEntity>,
    public val realLots: List<LotEntity>,
    public val legacyLots: List<LegacyLotEntity>,
    public val entries: List<LedgerEntryEntity>,
)

/** Fund precision/configuration and the full ledger are read in the same transaction. */
public data class LedgerFundHistoryRows public constructor(public val fund: FundEntity?, public val history: LedgerHistoryRows)
