/** Display/adapter declarations, reconstructed from contracts/backend-contract.json after cleanup.
 * Decimal inputs stay base-10 strings; formatted UI values never feed business calculations.
 * Runtime and business calculations remain native Kotlin. */
export type DecimalString = string;
export type IsoDate = string;
export type IsoInstant = string;
export type TransactionStatus = 'WAITING_NAV' | 'READY_TO_SYNC' | 'SYNCING' | 'SYNCED' | 'SYNC_FAILED' | 'EXTERNAL_CHANGED' | 'CANCELLED';
export type HoldingsStatus = 'NOT_CONNECTED' | 'INITIAL_LOADING' | 'REFRESHING' | 'READY' | 'EMPTY' | 'OFFLINE_CACHE' | 'ERROR' | 'AUTH_EXPIRED';

export interface HoldingUiModel {
  id: string;
  name: string;
  meta: string;
  amount: string;
  profit: string;
  availableSharesText: string;
  autoInvest: boolean;
  sellPresets: Readonly<Record<string, DecimalString>>;
  fundId: string;
  fundCode: string;
  isPlaceholder: boolean;
}

export interface FundDetailUiModel {
  id: string;
  amount: string;
  cost: string;
  shares: string;
  profit: string;
  profitRate: string;
  nav: string;
  navDelta: string;
  navDate: string;
  navStatus: string;
  avgNav: string;
  autoInvest: string;
  annualized: string;
  cumulativeProfit: string;
  realizedProfit: string;
  officialNav: string;
  officialNavTime: string;
  estimatedNav: string;
  estimatedNavTime: string;
  cumulativeRate: string;
  holdingStartDate: string | null;
  holdingDays: number;
  statisticsMessage: string;
}

export interface TransactionUiModel {
  id: string;
  fundId: string;
  fundName: string;
  dateKey: string;
  type: string;
  note: string;
  direction: 'BUY' | 'SELL';
  transactionStatus: TransactionStatus;
  amountInput: string;
  timePeriod: 'before' | 'after';
  feeRate: string;
  feeInput: string;
  expectedNavDate: string | null;
  confirmedShares: string | null;
  shareDelta: string | null;
  confirmedAmount: string | null;
  syncError: string;
  syncedAt: string | null;
  createdShares: string;
  createdCost: string;
  currentShares: string | null;
  currentCost: string | null;
  confirmedNavDate: string | null;
  confirmedNav: string | null;
  dateLabel: string;
  weekday: string;
  time: string;
  status: string;
  primaryText: string;
  secondaryText: string;
}

export interface DcaPlanUiModel {
  id: string;
  fundId: string;
  amountInput: string;
  frequency: 'daily' | 'weekly' | 'biweekly' | 'monthly';
  weekday: number;
  monthDay: number;
  firstDate: string;
  enabled: boolean;
  note: string;
  nextDateText: string;
  nextDateDisplay: string;
  nextStatusText: string;
}

export interface Nd100UiModel {
  indexText: string;
  changeText: string;
  changePercentText: string;
  marketStatus: string;
  updatedText: string;
  fxText: string;
  fxChangeText: string;
  performance: readonly Readonly<Record<string, string>>[];
  drawdownText: string;
  stageName: string;
  markerPercent: number;
  planLabel: string;
  planText: string;
  stages: ReadonlyArray<{
    label: string;
    range: string;
    color: string;
    active: boolean;
  }>;
}

export interface Nd100StrategyUiModel {
  tiers: ReadonlyArray<{
    drawdownThreshold: string;
    plannedAmount: string;
  }>;
}

export interface YjbAccountUiModel {
  displayName: string;
  status: string;
  maskedId: string;
  errorMessage: string;
  lastSyncAt: string | null;
}

export interface HoldingsStateUiModel {
  status: string;
  isCached: boolean;
  errorMessage: string;
  lastSyncAt: string | null;
}

export interface FrontendUiState {
  status: string;
  statusText: string;
  holdings: readonly HoldingUiModel[];
  details: readonly FundDetailUiModel[];
  overview: {
    amount: string;
    metrics: readonly string[];
  };
  transactions: readonly TransactionUiModel[];
  plans: readonly DcaPlanUiModel[];
  nd100: Nd100UiModel;
  yjbAccount: YjbAccountUiModel;
  yjbSyncState: {
    status: string;
    lastSyncAt: string | null;
  };
  holdingsState: HoldingsStateUiModel;
  dataSourceState: {
    indexStatus: string;
    fxStatus: string;
    lastUpdatedAt: string;
  };
  nd100Strategy: Nd100StrategyUiModel;
  theme: 'system' | 'light' | 'dark';
  transactionDetail: string | null;
  fundCatalog: readonly HoldingUiModel[];
  localStatistics: Readonly<Record<string, unknown>> | null;
  initialPositions: readonly Readonly<Record<string, unknown>>[];
}

export interface TradeDraftUiModel {
  fundId: string;
  type: string;
  amountInput: string;
  date: string;
  feeInput: string;
  note: string;
  timePeriod: 'before' | 'after';
  buyRateInput: string;
  sellRateInput: string;
}

export interface PendingTransactionEditContract {
  amountInput: string;
  dateKey: string;
  timePeriod: 'before' | 'after';
  feeRate: string;
  feeInput: string;
  note: string;
}

export interface OfficialNavConfirmationContract {
  source: 'OFFICIAL_HISTORY';
  fundId: string;
  navDate: string;
  nav: string;
  sourceRef: string;
  confirmedAt: string;
}
