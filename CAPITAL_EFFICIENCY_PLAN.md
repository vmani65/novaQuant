# Capital Efficiency Plan — Option A (Hedged Synthetic) & Option B (2× Monthly ATM Buying)

> Status: DESIGN — approved direction, not yet implemented.
> Owner decision so far: **Option A chosen for the main strategy.** Option B is a candidate
> second strategy (multi-strategy branch), gated on a backtest.
> This document is self-contained: it carries the problem, the evidence, and the full
> implementable spec for both options. It is written to be handed to a coding agent.

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

## 2. OPTION A — Hedged synthetic ("synthetic + wing") — CHOSEN for main strategy

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

## 3. OPTION B — 2× Monthly ATM buying (Madan-style) — candidate 2nd strategy, backtest-gated

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

### 3.4 Backtest gate (MANDATORY before any live order)

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

### 3.5 Rollout (only if backtest passes)

Run as a **separate strategy on the multi-strategy branch** at small size
(2–4 lots bought, i.e. 1–2 futures-equivalent) in parallel with the main Option A
strategy for ≥1 month. Compare realized capture, slippage, decay vs model. Only then
discuss capital migration.

---

## 4. What is explicitly OUT of scope

- No change to signal generation, entries, exits, or trade selection. Every signal is
  taken, always-in-market flip behavior unchanged.
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
