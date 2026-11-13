/** Proposed adapter ports only: no API implementation, credential storage or scheduler. */
import type { YjbAccountUiModel, HoldingsStatus, TransactionStatus, DcaPlanUiModel, Nd100StrategyUiModel, OfficialNavConfirmationContract, PendingTransactionEditContract } from '../src/models/ui-models';
export type DecimalString = string; // base-10; backend validation; never JS floating-point money
export type IsoDate = string;       // YYYY-MM-DD business date
export type IsoInstant = string;    // ISO-8601 with timezone
export interface YjbHoldingRecord {
  fundId: string; fundCode: string; name: string; category: string;
  shares: DecimalString; availableShares: DecimalString | null;
  cost: DecimalString; marketValue: DecimalString; holdingProfit: DecimalString;
  currency: string; profitRate: DecimalString | null;
  platformNav: DecimalString | null; platformNavDate: IsoDate | null;
  platformPriceKind: 'OFFICIAL' | 'ESTIMATE' | 'UNAVAILABLE';
  annualized: DecimalString | null; // legacy optional field; Production uses separate LocalStatistics
}
export interface HoldingSnapshotContract {
  authority: 'YJB'; revision: string; fetchedAt: IsoInstant;
  status: HoldingsStatus; isCached: boolean; lastSuccessAt: IsoInstant | null;
  holdings: YjbHoldingRecord[]; errorMessage: string | null;
}
export interface TransactionReadContract {
  id: string; fundId: string; direction: 'BUY' | 'SELL';
  originPlanId: string | null; revision: string; status: TransactionStatus;
  amount: DecimalString | null; sellShares: DecimalString | null;
  transactionDate: IsoDate; timePeriod: 'before' | 'after'; timezone: 'Asia/Shanghai';
  feeRatePercent: DecimalString; feeAmount: DecimalString; note: string;
  expectedNavDate: IsoDate | null; // estimate, including calendar/provider uncertainty
  officialNav: OfficialNavConfirmationContract | null; // required for READY/SYNC states
  confirmedShares: DecimalString | null; shareDelta: DecimalString | null;
  confirmedSellAmount: DecimalString | null; syncedAt: IsoInstant | null;
  syncError: string | null;
  createdHoldingRevision: string; createdShares: DecimalString; createdCost: DecimalString;
  currentHoldingRevision: string | null; currentShares: DecimalString | null; currentCost: DecimalString | null;
}
export interface CreateTransactionCommand {
  idempotencyKey: string; fundId: string; direction: 'BUY' | 'SELL';
  amount: DecimalString | null; sellShares: DecimalString | null;
  transactionDate: IsoDate; timePeriod: 'before' | 'after'; timezone: 'Asia/Shanghai';
  feeRatePercent: DecimalString; note: string; // no NAV or confirmed results
}
export interface EditPendingCommand { transactionId: string; revision: string; draft: PendingTransactionEditContract; }
export interface SyncTransactionCommand { transactionId: string; revision: string; holdingRevision: string; idempotencyKey: string; }
export interface PlanReadContract {
  plan: DcaPlanUiModel; revision: string; nextScheduledDate: IsoDate | null;
  timezone: 'Asia/Shanghai'; // nextDateText is a UI projection, not persisted scheduling truth
}
export interface PlanCommand {
  operation: 'CREATE' | 'UPDATE' | 'PAUSE' | 'RESUME' | 'DELETE';
  planId: string | null; fundId: string; revision: string | null;
  draft?: Pick<DcaPlanUiModel,'amountInput'|'frequency'|'weekday'|'monthDay'|'firstDate'|'note'>;
}
export interface MarketSnapshotContract {
  index: { symbol: 'NDX100'; price: DecimalString; change: DecimalString; changePercent: DecimalString; observedAt: IsoInstant; sourceRef: string } | null;
  fx: { pair: string; rate: DecimalString; change: DecimalString; observedAt: IsoInstant; sourceRef: string } | null;
  indexHistory: { businessDate: IsoDate; close: DecimalString }[];
  indexStatus: 'READY' | 'LOADING' | 'ERROR' | 'OFFLINE_CACHE';
  fxStatus: 'READY' | 'LOADING' | 'ERROR' | 'OFFLINE_CACHE'; errorMessage: string | null;
}
export interface FrontendBackendPorts {
  requestConnection(): Promise<YjbAccountUiModel>; // opaque auth handoff; never account passwords in this contract
  disconnectAccount(): Promise<YjbAccountUiModel>;
  readAccount(): Promise<YjbAccountUiModel>; // redacted response; credentials never in UI state
  readHoldings(): Promise<HoldingSnapshotContract>;
  refreshHoldings(): Promise<HoldingSnapshotContract>;
  readMarket(): Promise<MarketSnapshotContract>;
  readPlans(fundId: string): Promise<PlanReadContract[]>;
  readIndexStrategy(indexId: 'NDX100'): Promise<Nd100StrategyUiModel>;
  readTransactions(): Promise<TransactionReadContract[]>;
  readOfficialNav(fundId: string, candidateDate: IsoDate): Promise<OfficialNavConfirmationContract | null>;
  createTransaction(command: CreateTransactionCommand): Promise<TransactionReadContract>;
  editPending(command: EditPendingCommand): Promise<TransactionReadContract>;
  syncTransaction(command: SyncTransactionCommand): Promise<TransactionReadContract>;
  cancelTransaction(transactionId: string, revision: string): Promise<TransactionReadContract>;
  managePlan(command: PlanCommand): Promise<PlanReadContract | null>;
  saveIndexStrategy(indexId: 'NDX100', config: Nd100StrategyUiModel): Promise<Nd100StrategyUiModel>;
}
