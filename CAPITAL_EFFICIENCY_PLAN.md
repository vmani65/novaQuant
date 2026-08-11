# Capital Efficiency Plan — Option A (Hedged Synthetic) & Option B (2× Monthly ATM Buying)

> Status: BUILD IN PROGRESS — see §6 (terminology) and §7 (implementation status + pending plan).
> Decision timeline: Option A was initially chosen (2026-08-04), then superseded on 2026-08-05:
> **Option B builds FIRST**, running as a second execution BOOK alongside the current synthetic
> in ONE application, each book independently enable/disableable. Option A (wings) is DEFERRED,
> not dead — its live-verified margin data in §2 stays valid for a later revisit.
> This document is self-contained: problem, evidence, spec, and the pending build plan.
> It is written to be handed to a coding agent. Read §6 BEFORE reading anything else —
> the terminology there ("book", "calendar") is used throughout and mislabeling these
> concepts as "strategies" caused real confusion.

---

## 1. The problem we are solving

### 1.1 Current structure

The system trades an **always-in-market synthetic future on Nifty weekly options**,
driven by AmiBroker signals (LONG/SHORT flips, ~1 trade/day):

- **LONG signal** → BUY 1× ATM CE + SELL 1× ATM PE (same weekly strike)
- **SHORT signal** → BUY 1× ATM PE + SELL 1× ATM CE
- Currently fixed at **10 lots** (lot size 65, so 650 qty per leg).
- Legs are defined in the `leg_template` table
  (columns: `direction, option_type, side, offset_pts, lots` — `offset_pts` is
  currently 0 for all four rows and unused).
- Executed legs live in `weekly_leg` (per-leg fills, charges, `margin_required`),
  positions in `position` (with `peak_margin`, `points_pnl`, `actual_pnl`).

### 1.2 The two problems

**Problem 1 — capital inefficiency.** The naked short leg is margined for a crash
scenario: **~₹1.7–1.9L margin per lot**, ~₹19L for 10 lots — ~66% of the ₹28.7L
account — to run a position whose day-to-day risk is a few thousand rupees per lot.
The bought leg costs only its premium (~₹10–12k/lot). Almost all deployed capital
is exchange margin for the short leg.

**Problem 2 — uncapped tail risk.** The short leg is naked. A large overnight gap
against the position (e.g. −2,000 pts while LONG) costs ₹650/pt × the full move at
10 lots ≈ **₹13L**, plus forced-liquidation risk when margin blows through the
account mid-crash. Stops cannot protect against gaps.

### 1.3 Evidence base (analysis of 62 clean closed trades in signals.db, 2026-05 → 2026-07)

All numbers below come from reconciling `position` against `weekly_leg` actual fills.
7 positions excluded for missing/bad leg data (ids 15–20, 65).

- Synthetic net P&L: **+₹107,677**. Signal profile: 22 winners avg **+166 pts**
  (held 1–3 days), 40 losers avg **−77 pts** (mostly cut intraday). Net +553 pts.
  **All 25 intraday (<6h) trades were losers; winners need 1–3 days.** The edge is
  linear and lives in multi-day winners.
- Pure option-buying counterfactual (keep only the bought weekly ATM leg, same qty,
  actual fills): **−₹113k**. Weekly ATM decay measured at ~8–10 pts/day (−44 pts on
  3+ day holds). Winners captured 46% of the move; losers still suffered 58%.
  Conclusion: **weekly option buying is refuted by our own data** — the sold leg's
  +₹237k was financing the bought leg's decay.
- Madan-mode counterfactual (2× the bought **weekly** leg): **−₹196k**. Also refuted
  — on weeklies. The untested variant is 2× **monthly** (see Option B).
- Reference: trader Madan Kumar (@madan_kumar) ran **plain synthetic futures at the
  26L–60L capital band** (we are at ₹28.7L — inside that band), then moved to weekly
  option buying at 60L+ purely for liquidity. Monthly options are 4–10× less liquid
  than weeklies (his words) — relevant to Option B risk.
- A streak of moderate losses (e.g. 25 × −85 pts) costs ~₹13–14L under *every*
  structure below — wings/caps protect single catastrophic moves, not streaks. The
  only streak defense is recalculating lots from current capital (out of scope here,
  but `trade_capital.possible_lots` exists for it).

### 1.4 Goals

1. Cut capital consumption per lot by ≥60% (target: ≤₹85k/lot all-in vs ₹1.85L today).
2. Hard-cap the single-event disaster loss (target: ≤15% of capital at 10 lots).
3. Preserve the proven linear payoff of the synthetic (Option A), or prove an
   alternative empirically before deploying it (Option B).
4. Never take more signals or skip signals — the signal engine is untouched. This is
   purely a **position-structure** change.

---

## 2. OPTION A — Hedged synthetic ("synthetic + wing") — DEFERRED 2026-08-05 (Option B first; the live-verified margin data below remains valid)

### 2.1 Concept

Keep the synthetic exactly as-is, and hold a **far-OTM bought option (the "wing") on
the same side as the short leg**, 1:1 in lots. The naked short becomes a defined-risk
spread:

- Loss below/above the wing strike is fully absorbed by the wing → **max loss on the
  short side = wing distance** (e.g. 600 pts ≈ ₹39k/lot) no matter how big the gap.
- NSE/SPAN margins the hedged pair on its max loss instead of a crash scenario →
  short-leg margin drops from ~₹1.7L to an estimated **₹55–70k/lot**
  (**ESTIMATE — must be verified, see 2.5**).

### 2.2 Wing mode decision: standing wing pair (Mode B) — recommended

Because the system is always-in-market and flips LONG↔SHORT ~5×/week, a per-position
wing (new wing bought and sold on every flip) would cross an illiquid far-OTM spread
~10×/week and add a leg to every open/close sequence. Instead:

**Hold BOTH wings continuously, rolled weekly:**

- At each weekly rollover (existing `WeeklySymbolService` cadence), BUY and hold to
  expiry, sized to the strategy's lot count (currently 10 lots each):
  - 1× PE at (spot − WING_OFFSET)
  - 1× CE at (spot + WING_OFFSET)
- Wings are **not** position legs. They are a strategy-level standing hedge with
  their own lifecycle (bought at rollover, held through all flips, expire or are
  rolled). Whichever direction the synthetic flips to, the matching wing is already
  in place → margin benefit is continuous, gap protection is 24/7, per-signal
  execution is unchanged.
- The off-side wing is redundant while held; that is accepted cost.

`WING_OFFSET` is **dynamic, chosen at each weekly roll**: pick the smallest offset whose
wing-pair premium ≤ `WING_BUDGET_PTS_PER_WEEK` (default **10 pts/week**, configurable).
Wing premiums scale with IV, so a fixed offset is wrong in one vol regime or another.
Wing strikes snap to liquid 100-pt strikes.

**VERIFIED LIVE 2026-08-04** via Zerodha Basket Margin API (spot 24,493.85, ATM 24500,
Aug-11 weekly, 10 lots = 650 qty, consider_positions=false, `final` margins):

| Offset | Margin LONG | Margin SHORT | Disaster cap (10 lots) | Wing pair cost/week (live) |
|--------|-------------|--------------|------------------------|----------------------------|
| naked  | ₹16.17L     | ₹18.57L      | unlimited              | —                          |
| 400    | ₹6.00L      | ₹5.50L       | ₹2.6L                  | 59.6 pts ≈ ₹38.7k          |
| 600    | ₹7.15L      | ₹6.35L      | ₹3.9L (~14% capital)   | 25.1 pts ≈ ₹16.3k          |
| 1000   | ₹9.64L      | ₹8.41L      | ₹6.5L (~23% capital)   | 9.0 pts ≈ ₹5.9k            |

Live wing premiums that day (7 DTE): 24100PE 33.2 / 23900PE 14.8 / 23500PE 5.85;
24900CE 26.4 / 25100CE 10.3 / 25500CE 3.15. ATM: CE 145.3 / PE 154.1.
At that IV the 10-pt budget selects the **1000-pt wing** (frees ~₹9–10L vs naked,
cap ₹6.5L, ~21% of monthly edge in premium). In calmer vol the same budget may select
600. The 400 wing's margin is lowest but its premium (~₹39k/wk) is uneconomical —
the budget rule prevents ever picking it.

Working capital must cover the worse side (always-in-market, flips both ways):
naked ₹18.6L → winged ₹8.5–9.7L at the 1000 offset.

### 2.3 Position structure after change (LONG example, 10 lots)

```
Leg 1 (per position):  BUY  10x ATM CE  weekly     — existing
Leg 2 (per position):  SELL 10x ATM PE  weekly     — existing
Standing (per week):   BUY  10x PE @ ATM-600       — protects leg 2 while LONG
Standing (per week):   BUY  10x CE @ ATM+600       — protects the SELL CE when SHORT
```

**Iron rule: wing lots ≥ short-leg lots at all times, 1:1.** A partially-filled or
missing wing means the uncovered short lots are margined naked and the loss cap is
broken. See interlocks (2.6).

### 2.4 Expected costs (validate in paper phase)

- Brokerage/statutory: wings add ~₹55 per weekly cycle order pair — negligible
  (~0.08 pts). Current round-trip charges are ~₹470/trade for reference.
- Real cost = wing pair premium (wings usually expire worthless — that is the
  insurance). Controlled by `WING_BUDGET_PTS_PER_WEEK` (10 pts/wk ≈ 43 pts/month
  ≈ ~21–23% of the ~184 pts/month gross edge). This buys the disaster cap and frees
  ~₹9–10L margin at the offset the budget currently selects.

### 2.5 Margin verification — DONE 2026-08-04, keep as a permanent tool

Live basket-margin dry-run was executed 2026-08-04 (results in 2.2). Productize it:

- Extend `KiteConnectGateway` (which already fetches per-order margins that populate
  `weekly_leg.margin_required`) with Zerodha's **Basket Margin API**
  (`POST /margins/basket`). Use `consider_positions=false` for structural quotes.
- The weekly roll uses this + wing LTPs to run the offset-selection rule (2.2):
  price offsets {400, 600, 1000}, pick smallest within premium budget.
- Expose via a maintenance/controller endpoint; log chosen offset, margins, and
  premiums each week for audit.

### 2.6 Execution sequencing & safety interlocks

1. **Entry order (per weekly wing purchase):** wings are bought at rollover before
   any short leg exists for the new expiry. If ever bought mid-cycle: wing FIRST,
   short leg after wing fill is CONFIRMED. Never the reverse (naked-margin spike +
   possible margin reject → orphan, as in the 2026-07-20 PARTIAL incident).
2. **Exit order:** short leg is always bought back BEFORE a wing is sold. Wings are
   normally held to expiry, so this only applies to manual unwinds/rollover edge cases.
3. **Interlock:** block any SELL-side order if
   `confirmed wing qty (same option type, further OTM, same expiry) < intended short qty`.
   This is a hard gate in the order path, same severity as the existing
   MAX_SIZE_PER_ORDER checks.
4. **Wing fill failure at rollover:** alert + block new position opens until resolved
   (existing PARTIAL machinery / MaintenanceController patterns apply).
5. **Expiry-day:** wings expire with the weekly. The rollover flow must buy next-week
   wings when it rolls the traded symbols (reuse `weekly_symbol` / rollover logic).
   During any window where shorts are rolled before wings, sequence: buy next-week
   wing → roll short → let old wing expire/close.

### 2.7 Persistence & reporting changes

- New standing-hedge records: either a new table `standing_hedge`
  (id, instrument, expiry, side=BUY, lots, qty, buy_fill_price, sell_fill_price/expiry,
  open/close charges, status, week_ref) or `weekly_leg` rows with a nullable
  `position_id` and a `leg_role = WING` discriminator. Prefer whichever integrates
  cleaner with existing fill-confirmation code; wings must NOT pollute per-position
  P&L capture metrics (`pnl_capture_pct`) — report wing cost as a separate line in
  equity/P&L reporting (weekly hedge cost).
- `position.peak_margin` will drop; nothing to change, it is informational.
- Equity curve: add wing costs so the curve still ties to broker ledger to the rupee
  (the 2026-07-30 reconciliation standard must keep passing).

### 2.8 Rollout

1. Basket margin dry-run (2.5) → record real margins for 400/600/1000.
2. Implement standing wings behind a config flag (`hedge.wings.enabled=false` default).
3. Mock-gateway test: full week cycle incl. rollover, flip storm, wing-fill-failure path.
4. Enable in prod with WING_OFFSET=600 at current 10 lots. Keep freed margin as buffer —
   **do not increase lots in this phase.**
5. After ≥2 clean weeks: revisit lot scaling via `trade_capital` calculated lots.

---

## 3. OPTION B — 2× Monthly ATM buying (Madan-style) — CHOSEN 2026-08-05: builds as the LONG_MONTHLY book (§6), live money at reduced size, backtest gate DROPPED (see §3.4 status note)

### 3.1 Concept

Replace the 2-leg synthetic with a **single bought leg on MONTHLY options at 2× lots**:

- **LONG signal** → BUY 2N lots monthly ATM CE (nothing sold)
- **SHORT signal** → BUY 2N lots monthly ATM PE
- N = current futures-equivalent lots (10) → 20 lots bought.

Why 2×: ATM delta ≈ 0.45–0.50, so 2 lots ≈ delta 1 ≈ one future at entry. Gamma then
works in our favor both ways: on favorable moves combined delta rises above 1 (winners
capture MORE than a future); on adverse moves it falls below 1 (losers hurt less), and
the absolute worst case is the premium paid. Cost: 2× theta — which is why this is
viable ONLY on monthlies (~6–8 pts/day early-cycle) and is refuted on weeklies
(~20–30 pts/day; measured −₹196k on our own 62 trades).

Modeled expectation at our holding profile (winners +166 pts over 1–3 days, losers −77
intraday): **≈96% of synthetic P&L at ~25% of the capital, with defined risk.**
This is a MODEL, not a measurement — hence the backtest gate.

### 3.2 Structure & sizing (at current scale)

- 20 lots monthly ATM ≈ 330 pts × 65 × 20 ≈ **₹4.3L premium per position**
  (~15% of capital deployed; no margin, no wing, no short leg, no orphan-able
  sell leg, no margin-call scenario).
- Max theoretical loss per trade = full premium ₹4.3L (needs ~330-pt adverse gap);
  normal stop exits cost ~₹1L at avg loser size.
- `leg_template` for this strategy becomes 2 rows:
  `LONG → BUY CE offset 0 lots 20` and `SHORT → BUY PE offset 0 lots 20`,
  pointing at the MONTHLY symbol, one leg per position.

### 3.3 Monthly-specific rules

1. **Symbol selection / roll rule:** trade the current monthly while DTE ≥ 10;
   if DTE < 10, buy the NEXT monthly (decay steepens in the last ~10 days).
   Requires a monthly analogue of the weekly symbol service.
2. **Liquidity guard:** monthly NF options are 4–10× thinner than weeklies. 1,300 qty
   must go through the existing LIMIT-walk slicer with per-slice spread checks; record
   effective spread paid. If measured slippage exceeds ~2 pts/side at this size, the
   strategy economics need re-evaluation.
3. Positions opened near month-end may hold across the roll boundary — position exit
   uses whatever contract it entered; no mid-position rolling in v1.

### 3.4 Backtest gate — DROPPED 2026-08-05 (kept for the record)

> STATUS: A 5-agent audit (wf_be7562d9-1e4) proved the strict backtest is IMPOSSIBLE from local
> data: the tick recorder captured monthly-format options ONLY during expiry weeks (the exact
> <10-DTE window the roll rule avoids) — 0 of 69 May–Jul signals priceable. A relaxed partial
> backtest (18/69 signals, expiry weeks, real ticks) remains possible later as a cross-check.
> Owner decision: go live at reduced size instead — the live run IS the test, tuition capped by
> sizing. Prep item: subscribe the nqTicker recorder to current monthly ATM strikes NOW so the
> live book has tick history from day one. Original gate text below, superseded:

Backtest Option B against the **actual 62 historical signals** (entry/exit timestamps
from `position`) using real monthly option prices:

- Data source: check `chain_ticks.db` / `nifty_ticks.db`
  (C:/novaquant/data/sqlite/) for monthly option chain coverage over 2026-05 → 2026-07.
  If coverage is missing, collect forward (paper mode) before deciding.
- For each historical trade: price 2× monthly ATM at signal open/close, apply the
  DTE≥10 roll rule, include estimated monthly spread cost, compare to actual
  synthetic P&L per trade and in aggregate.
- **Pass criteria:** aggregate ≥85% of synthetic P&L on the same signals AND no
  pathological per-trade behavior (e.g. small winners systematically turning into
  losers, which is what killed the weekly variant).

### 3.5 Rollout (updated 2026-08-05)

Run as the **LONG_MONTHLY book inside the one application** (§6 — NOT the multiStatergy
branch, which stays unmerged) at small size (owner seeded 1 lot/leg; the 2×-delta ratio
suggests 2 lots per futures-equivalent — open decision, §7) in parallel with the
SYNTH_WEEKLY book for ≥1 month. Compare realized capture, slippage, decay vs model.
Only then discuss capital migration.

---

## 4. What is explicitly OUT of scope

- No change to signal generation, entries, exits, or trade selection. Every signal is
  taken, always-in-market flip behavior unchanged. RIDETHETIDE remains the only strategy.
- No generic multi-strategy framework. The `feature/multiStatergy` branch (commit c650f7f)
  stays unmerged; the two-book design in §6/§7 is deliberately scoped to exactly two
  hardcoded books with toggles.
- No lot-size increase from freed capital in this phase (calculated lots from
  `trade_capital` is a separate future task).
- No daily-loss circuit breaker (rejected: incompatible with take-every-signal
  positional system).
- Weekly option buying in any form (1× or 2×): **refuted by our own data, do not build.**

## 5. Quick reference — the three structures side by side (10-lot scale)

| | Current naked synthetic | Option A (dynamic wing) | Option B (2× monthly ATM) |
|---|---|---|---|
| Legs per position | 2 | 2 (+2 standing wings/week) | 1 |
| Capital (verified live 2026-08-04) | ₹16.2L (L) / ₹18.6L (S) | ₹8.5–9.7L at 1000 offset; ₹6.4–7.2L if IV allows 600 | ~₹4.3L premium |
| Payoff | linear 1:1 | linear 1:1 | ~delta 1 at entry, convex |
| 2,000-pt gap against | −₹13L + liq. risk | −₹6.5L cap (1000) / −₹3.9L (600) | −₹4.3L hard cap (premium) |
| Edge tax | — | ~21–23% (10 pts/wk wing budget) | decay + monthly spread (model ~4%, UNVERIFIED) |
| Evidence | +₹107.7k live (62 trades) | same engine + insurance; margins verified live | model only — backtest gate |
| Status | live today | **build now** (2.8 order) | build after backtest passes |

*(Table statuses above predate the 2026-08-05 decision change — §7 is authoritative.)*

---

## 6. TERMINOLOGY (authoritative — use these words in code, UI, and docs)

Calling the two execution styles "strategies" caused real confusion. Corrected model:

- **STRATEGY** — the signal engine. There is exactly ONE: `RIDETHETIDE` (AmiBroker,
  LONG/SHORT/exit signals). `position.strategy_name` keeps meaning this. Unchanged.
- **BOOK** — how a signal is expressed in the market. Each book has its own contracts,
  structure, lot size, positions, P&L, and an independent enable/disable toggle. Two books:
  - **`SYNTH_WEEKLY`** — today's live book: weekly contracts, 2-leg synthetic
    (BUY ATM CE + SELL ATM PE on LONG; mirrored on SHORT), 10 lots.
  - **`LONG_MONTHLY`** — the new book: monthly contracts, 1 bought leg
    (BUY ATM CE on LONG / BUY ATM PE on SHORT), small size to start.
- **CALENDAR** — the contract series a book trades: `WEEKLY` (row id=1 of the symbol
  config, drives the existing rollover automation) or `MONTHLY` (row id=2). The phase-1
  "scope" column on the symbol table IS the calendar — keep that column name there.

Hierarchy: `RIDETHETIDE signal → fan-out → each ENABLED book opens/flips/closes its own position`.

Naming for the coding agent: new column `position.BOOK` (values SYNTH_WEEKLY | LONG_MONTHLY);
`LEG_TEMPLATE.SCOPE` from phase 1 should be RENAMED to `BOOK` with those values (nothing is
deployed, renaming is free); symbol config keeps `scope` = calendar (WEEKLY | MONTHLY);
each book maps to exactly one calendar (SYNTH_WEEKLY→WEEKLY, LONG_MONTHLY→MONTHLY).

---

## 7. IMPLEMENTATION STATUS & PENDING BUILD PLAN (authoritative as of 2026-08-07)

> **BUILD STATUS 2026-08-07 — phases 1-4 BUILT on feature/nQSynthAndMonthly, NOT deployed:**
> - Phase 1 (calendars/config+UI): commit 69b0a65, with test coverage.
> - Phase 2 (§7.1 items 1-8 ALL BUILT): commit 753b857 — SCOPE→BOOK rename, position.BOOK,
>   book-scoped queries, per-book service methods (openWeekly/openMonthlyTrade etc.),
>   signal fan-out (weekly-first, error-isolated), BOOK_CONFIG toggles (monthly seeds OFF),
>   rollover+recenter hard-fenced to SYNTH_WEEKLY, PENDING_OPEN + PendingOpenReconciler.
> - Phase 3 (§7.2 items 9-13 ALL BUILT): commit 73c6938 — MonthlyRollService (DTE≥10 auto-roll,
>   daily 08:40 IST + pre-open + /api/monthly-roll-check, prefix from real tradingsymbol),
>   LONG_MONTHLY expected = (qty/2)×points, WIN/LOSS from rupee PnL, capital chain shared but
>   lot sizing weekly-only, trade log + equity curve book filters, NRML stats exclude monthly.
> - Phase 4: item 16 (NIFTY27* filter) FIXED (expiry-horizon filter); item 15 (spread guard)
>   BUILT — OPEN/CLOSE_SPREAD_PAID per leg + MONTHLY LIQUIDITY ALERT above 2 pts/side;
>   item 14 DECIDED: single-leg PARTIAL keeps orphan-flatten in v1 (revisit with live data);
>   item 17 (nqTicker monthly subscription) is external — still owed in the nqTicker app.
> - Owner decisions (§7.4) locked 2026-08-07: LONG_MONTHLY = 2-lot increments; FULL auto-roll;
>   recenter NONE; 9:15 open-buffer weekly-only (REVERSED 2026-08-11 → both books, see §7.4).
>   Evaluation window ≥1 month stands.
> - Next: mock,test click-through → explicit "promote" → signals.db backup → deploy (§7.0 protocol).

> **ROLLOVER SYMMETRY RESTRUCTURE 2026-08-10 (owner-ordered, built on the same branch):**
> weekly now rolls exactly like monthly — sync the symbol row FIRST, then roll the position
> onto whatever the current slot says. WeeklySymbolService.syncTradedContract (renamed from
> checkAndPromoteRolloverSymbol; date-gated promote + rolloverComplete latch) is weekly's
> counterpart of monthly's DTE sync; SignalService.handleWeeklyRollOver mirrors
> handleMonthlyRollOver (sync → roll, BOOK-ISOLATED catch, PERFORMANCE log); both trigger
> endpoints are thin twins (all gating moved out of RollOverTriggerController; /api/rollover
> in SignalController routes through the same handler and is no longer an ungated bypass).
> buildWeeklyInstrument lost its rollOver-slot peek flag (always trades the CURRENT slot,
> loud guards like monthly); the recenter's useRollover logic was dead in every reachable
> state and is removed. No-churn guard is now CONTRACT-based (skip unless held legs are on
> a different contract than the current slot) — a trigger fire on a non-roll day or with a
> drifted ATM can never re-strike either book. Promotion runs BEFORE the roll, so the
> symbol row advances on expiry day even if the roll fails (owner rule: the trade stream
> must continue on the latest contract). 159/159 tests green; mock,test smoke-verified
> (all 4 endpoints symmetric). NOT deployed.

(Original plan below, kept for the record.)

### 7.0 Phase 1 — Calendars & books CONFIG + UI: BUILT, REVIEWED, ROLLED BACK FROM PROD

Built and adversarially reviewed 2026-08-05 (21/21 tests green), briefly deployed, then
**rolled back from prod at owner's demand** — schema changes must never hit live signals.db
without a mock,test pass AND explicit owner approval. Current state:
- Code: sits UNCOMMITTED in the working tree (baseline commit 5832a34). Covers: symbol
  config scope column + id=2 monthly row + scope-aware upsert; LEG_TEMPLATE scope column +
  scoped cache (engine reads WEEKLY-only) + null backfill; two-form Symbols UI; two-column
  manifestation (MONTHLY left / WEEKLY right); scope threaded through leg CRUD; review fixes
  (scope-less PUT preserves existing scope; /symbol/save rejects unknown scope).
- Prod: OLD jar restored (`nqCore-2026.06.jar.bak-20260805-024624-predeploy` content),
  live DB reverted (columns dropped, monthly rows deleted, integrity verified).
- Owner's monthly entries (calendar 26AUG/26SEP + 2 monthly 1-lot legs) are recoverable
  from `signals.db.bak-20260805-025557-prerollback`.
- **Deploy protocol from now on (non-negotiable):** run under
  `--spring.profiles.active=mock,test` (signals_test.db) → owner clicks through → owner
  says "promote" explicitly → fresh signals.db backup → deploy → verify. Never let
  Hibernate ddl-auto touch live signals.db as a side effect of a casual start.

### 7.1 Phase 2 — Book plumbing (minimum for LONG_MONTHLY to trade safely)

1. Rename phase-1 `LEG_TEMPLATE.SCOPE` → `BOOK` (SYNTH_WEEKLY | LONG_MONTHLY); add
   `position.BOOK`, stamped at open. `strategy_name` stays RIDETHETIDE.
2. **Book-scoped position queries — the core surgery.** `findFirstByStatusOrderByIdDesc`
   (PositionRepository:14) selects THE latest LIVE position bookless; today two books would
   close each other's positions. Thread book through: PositionClosingService.closeTrade(:45)
   + closeOrphanIfAny(:66), SignalService.getLastTrade(:52), ProfitRecenterService(:72-74),
   PositionRolloverService(:55), PendingCloseReconciler, PositionUtil.findLiveTradesWithLiveOrderBooks(:1145).
3. **Signal fan-out**: each signal (open/flip/close) executes once per ENABLED book, error-
   isolated — an exception in one book must never block the other. Mind SQLite write
   contention (busy_timeout=5000 exists; consider sequential book execution, weekly first).
4. **Per-book enable/disable toggles** (DB-backed config + UI switches). Define disable
   semantics: no NEW positions; an already-open position keeps being managed until its
   natural close (recommended), then the book goes dormant.
5. **Calendar-aware order building**: ComputeUtil.buildInstrument(:57) hardwires the weekly
   prefix; each leg must resolve its prefix from its book's calendar (weekly cache slot vs
   monthly cache slot built in phase 1).
6. **Automation fences**: 14:47 weekly rollover (RollOverTriggerController:41 →
   PositionRolloverService) and +500-pt recenter (/api/realize-profits →
   ProfitRecenterService) act on SYNTH_WEEKLY ONLY. Today either one would close a monthly
   leg and reopen it as a WEEKLY leg (audit BLOCKERs). Hard book filter at both entry points.
7. **Per-book signal dedup/sequence seeding**: SignalController's previousByStrategy
   startup seeding loads only one last trade; seed per book.
8. **PENDING_OPEN reconciler** (pulled forward from robustness, trade-73 class): an open
   order marked FAILED with a stored orderId that fills late = untracked broker position
   (PositionOpeningService:135, PositionUtil:786). With a 1-leg book this is the entire
   position. Reconcile FAILED-with-orderId opens from the tradebook, mirroring
   PendingCloseReconciler.

### 7.2 Phase 3 — LONG_MONTHLY lifecycle & book-correct accounting

9.  **Monthly calendar roll (DTE≥10)**: make the monthly "Rollover Day" real — promote
    26AUG→26SEP automatically. Expiry dates via gateway.getInstruments("NFO") (Instrument.expiry);
    monthly expiry = latest NIFTY option expiry in the calendar month; derive the prefix
    from a real tradingsymbol (never regex-guess; weekly prefixes like 26811 make digit
    parsing ambiguous). Cache the NFO dump daily.
10. **Book-aware expected P&L**: calcPnL (ComputeUtil:180,:192) assumes delta-1 pairs —
    ~2× overstated for one bought leg. For LONG_MONTHLY use expected = (qty/2) × points
    (futures-equivalent), so both books' pnlCapturePct read on the same scale.
11. **WIN/LOSS from rupees for LONG_MONTHLY**: calcTradeOutcome (:122) uses spot-points
    sign; a theta-bled small winner gets labeled WIN with negative actualPnl. For
    LONG_MONTHLY derive result from actualPnl.
12. **Per-book capital semantics**: recalculateCapital (:277-280) uses definedRiskPerLot
    = 400000 (margin-era); a LONG_MONTHLY "lot" costs ~premium (~₹21.5k). Make risk-per-lot
    per-book or exclude LONG_MONTHLY from possibleLots math initially. position.lots now
    means option lots for LONG_MONTHLY rows — label in UI.
13. **Trade log + equity curve**: book filter/column; peak_margin means premium outlay for
    LONG_MONTHLY rows (TradeCapitalService 30-day NRML stats will blend regimes — label).

### 7.3 Phase 4 — Robustness & ops

14. Single-leg PARTIAL open = correctly-directed real position; treat as LIVE at reduced
    size instead of orphan-flagged PARTIAL (today: invisible to monitoring, noisy ORPHAN logs).
15. **Monthly liquidity guard**: monthlies are 4–10× thinner than weeklies (Madan's own
    reason to leave them at 60L+). Log effective spread paid per fill; alert > ~2 pts/side.
16. **NIFTY27\* filter bug**: PositionUtil.getNiftyInstruments(:1127) drops all NIFTY27+
    symbols — every contract from Jan 2027. Book-independent time bomb; fix.
17. Subscribe the nqTicker recorder to current monthly ATM strikes (tick history for
    evaluating the live book; the strict backtest was proven impossible — §3.4 note).
18. Commit phase-1 code; after next deploy re-enter monthly calendar+legs (or restore from
    the prerollback backup).

### 7.4 Open decisions (owner)

- **LONG_MONTHLY starting lots**: owner seeded 1 lot/leg; the 2×-delta ratio implies 2 lots
  per futures-equivalent. Confirm 1 (ultra-cautious, capture ~½ point per point) or 2
  (delta≈1, the Madan construction) before first live trade.
- **Recenter policy for LONG_MONTHLY**: recommended NONE (gamma convexity is the point;
  re-striking sells it off). Confirm.
- **9:15 open-buffer (nQTicker longExit delegation)**: ~~recommended weekly-only; LONG_MONTHLY
  closes inline~~ — REVERSED by owner 2026-08-11 after the first live 9:15 exit recorded
  divergent exit spots (weekly 24621.0 via buffer vs monthly 24601.5 off the stale AFL bar
  price). Both books now delegate; /api/execute-close closes both at nQTicker's live price.
- **Evaluation window**: ≥1 month parallel run before any capital-migration discussion.
