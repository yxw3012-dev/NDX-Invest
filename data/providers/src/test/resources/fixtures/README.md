# Provider fixture provenance

## `tencent_ndx_quote.txt`

- Sources: Tencent Finance official NDX page <https://gu.qq.com/usNDX>, its published quote client, and <https://qt.gtimg.cn/?q=marketStat,usNDX>.
- Retrieved: 2026-09-13; the quote itself is timestamped 2026-09-11 in New York.
- SHA-256: `855a23ab36edd01afedb0078f7e1344b119d75d7ae1802d2cc156de08a424d02`.
- Scope: the decoded `v_marketStat` and `v_usNDX` assignments used to verify symbol, exact decimal fields, US market state, and the New York wall-clock timestamp.
- The transport owns GB18030/GBK decoding from the response `Content-Type`; this fixture is stored as UTF-8 after that boundary.

## `tencent_usd_cny_quote.txt`

- Sources: Tencent Finance official USD/CNY page <https://gu.qq.com/whUSDCNY>, its published `modules/hqpanel/config` client, and <https://qt.gtimg.cn/?q=whUSDCNY>.
- Retrieved: 2026-09-13; the payload is timestamped 2026-09-12 in the source page's China-local display convention.
- SHA-256: `ba97ecbcb80cff3298cc6bd4892baa696f3731d5a914d96bc2b26fe423923246`.
- Scope: the decoded `v_whUSDCNY` assignment used to verify the exact rate, prior-close/change consistency, fractional change-rate conversion, and explicit `Asia/Shanghai` timestamp interpretation.

## `eastmoney_usd_cny_quote.json`

- Sources: EastMoney's official USD/CNY mixed-rate page <https://quote.eastmoney.com/q/121.usdcnyi.html>, its published quote client <https://quote.eastmoney.com/newstatic/build/forex2.js>, and the first-party quote endpoint <https://push2.eastmoney.com/api/qt/stock/get?secid=121.USDCNYI&fields=f57,f58,f43,f59,f60,f169,f170,f86>.
- Retrieved and re-verified: 2026-09-15. A repeated credential-free request returned the same `USDCNYI` identity, four-decimal precision, and coherent rate/prior-close/change/percentage relationships; the live quote timestamp advanced as expected.
- SHA-256: `a5af1716aa1040b29580e0559be46634a89c0b4c1a60f93d293aa187a305f2b2`.
- Scope: successful envelope, exact mixed-rate identity, explicit price precision, integer-to-decimal scaling, exact change identity, rounded fractional change-rate consistency, and epoch-second quote time.
- EastMoney's `120.USDCNYC` is the daily central parity and `133.USDCNH` is offshore RMB. Both are semantically different and are rejected rather than substituted for the product's comparable market USD/CNY quote.

## `sina_ndx_quote.txt`

- Sources: Sina Finance official `.NDX` page <https://stock.finance.sina.com.cn/usstock/quotes/.NDX.html>, its published `hqParser.us` client, and <https://hq.sinajs.cn/list=gb_$ndx> with the official Finance `Referer`.
- Retrieved: 2026-09-13; the quote is timestamped 2026-09-11 EDT.
- SHA-256: `bee7e3f030962819a4218a14ef48324884ad854d77b0e3c70dfd472dd0f07705`.
- Scope: the decoded `hq_str_gb_$ndx` assignment used to verify exact decimal fields and the explicit EDT/EST timestamp contract.
- Sina returns HTTP 403 without the public Finance `Referer`; the binding therefore uses a non-secret static header. The transport owns GB18030 decoding.

## `nasdaq_ndx_history_page_0.json` and `nasdaq_ndx_history_page_2.json`

- Source: Nasdaq's official NDX historical endpoint, linked to the official historical-data surface: <https://api.nasdaq.com/api/quote/NDX/historical?assetclass=index&fromdate=2025-09-08&todate=2025-09-11&limit=2&offset=0> and the same request with `offset=2`.
- Retrieved and re-verified: 2026-09-13. The two pages contain official NDX daily rows dated 2025-09-08 through 2025-09-11.
- SHA-256: page 0 `b4a0cbcfe72ec5b9a5ae4555c111fcd96c0ad061cb93d3dd5028d78e1c90c9c5`; page 2 `a7177991eeda1eb0f92376e58c549b47381b66dab76b2fdb26f7e127509ab98e`.
- Scope: exact `NDX` identity, successful envelope, declared total, ISO request range, offset pagination, U.S.-formatted dates, grouped decimal OHLC fields, and newest-first upstream order.
- Stooq's official `^NDX` historical page was also verified, but its CSV download currently requires interactive human verification. It remains a replaceable candidate and is not misrepresented as an unattended Android contract.

## `nasdaq_ndx_history_auxiliary_anomalies.json`

- Source: a minimal excerpt from Nasdaq's official NDX historical endpoint, re-verified on 2026-09-28 with the frozen `1985-01-31` through `2026-09-28` request range.
- SHA-256: `87728cfa5ac3005ffe8b87078aa8d9e7b2895a96f89f93ea9f11b16aa2856312`.
- Scope: two exact official rows where `close` remains positive while auxiliary OHLC fields are zero or internally inconsistent. The Adapter preserves every published value and date; it does not guess, rewrite, or drop either row. Current v1.1 drawdown and long-term-performance calculations use only the verified positive daily close.

