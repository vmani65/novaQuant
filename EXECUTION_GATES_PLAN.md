# Execution Gates — Implementation Plan

Status: **PLANNED — not implemented** (written 2026-07-31 from the trade #70 investigation)
Scope: execution-cost optimization only. No strategy logic changes, no signal changes, no accounting-reference changes.

---

## 1. Background and decisions already made (do not revisit)

### 1.1 What triggered this
Trade #70 (LONG, 24-07 → 29-07, +476.1 signal points) showed 82.5% capture vs the expected
95–110%. Full forensic analysis (put-call parity forwards reconstructed from `weekly_leg` fills,
cross-checked against Kite historical minute candles) proved:

- The signal price feed (AmiBroker) is the **front-month NIFTY futures continuous contract**,
  not the spot index. Verified: trade 70 `exit_spot` 24,309.1 == NIFTY26AUGFUT 15:15 candle on
  29-07 (range 24,309–24,315); real NSE index that minute was 24,249.
- Trade 70 was held across the July monthly futures expiry (Tue 28-07). The continuous chart
  rolled July→August overnight, adding ~+60 pts to the reference with no market move. Measured
  against a consistent reference the trade captured **~95% — inside the expected band**.
- Across 60 clean single-segment closed trades, basis P&L is **mean-zero (+0.5 pt/trade)**.
  The strategy itself has no leak.

### 1.2 Decisions locked in
- **Keep the futures-based reference.** The whole strategy was backtested on the futures
  continuous contract; live accounting stays consistent with the backtest. Do NOT introduce
  index-based ("truthful") accounting — it would diverge from backtest logic.
  Consequence to remember when reading the dashboard: capture% on any trade held across a
  monthly futures expiry (last Tuesday of the month) carries ±50–70 phantom points.
  Longs across the roll read artificially low, shorts artificially high. This is cosmetic.
- **Dropped ideas (evidence-based, do not rebuild):**
  - *Dividend model / dividend-season avoidance* — refuted. Futures were in normal contango
    (Aug +46, Sep +177 over spot on 31-07); true dividend effect on the weekly forward ~13–15 pts.
  - *PREFER_EXPIRY (roll target selection by basis)* — replay of 28-07→29-07 showed candidate
    expiries differed by only ~13 pts of capture AND the hindsight winner was the most expensive
    entry (monthly), i.e. the opposite of any rational ex-ante rule. No signal exists here.
  - *Moving the rollover to Tuesday (expiry-day) monitoring* — Tuesday averaged ~8 pts RICHER
    than Monday (day-average Aug-04 premium +31.0 vs +22.6), and the Monday roll harvested the
    dying contract's remaining premium (+17.5) before it converged away. Tuesday also imposes a
    hard deadline that force-fills into the closing spike (+50 at 15:00). Monday roll stays.
  - *Greeks engine* — a synthetic is delta-1, gamma-0, vega-0 at every strike by parity.
    Nothing to compute.

### 1.3 The one open non-execution item
Before **25-Aug-2026** (next monthly futures expiry): check how the AmiBroker continuous series
handles the contract roll. If it is plain-spliced (not back-adjusted), 15-min indicators see a
phantom ~60-pt gap the morning after every monthly expiry → false-signal risk. This is an
AmiBroker configuration audit, not an nqCore change.

---

## 2. Core math used by every gate

### 2.1 Implied forward (put-call parity)
For a CE/PE pair at strike K, same expiry:

```
F = K + CE − PE
```

Execution-aware versions (use quote depth, not LTP):

```
F_receive(K)  = K + CE_bid − PE_ask     // what you COLLECT selling the synthetic (sell CE, buy PE)
F_pay(K)      = K + CE_ask − PE_bid     // what you PAY buying the synthetic (buy CE, sell PE)
```

### 2.2 Direction conventions — READ CAREFULLY, this is where sign bugs will live

A LONG position = long synthetic (BUY CE + SELL PE). A SHORT position = short synthetic.

**Rollover (old expiry → new expiry):**

| Direction | Exit old legs | Enter new legs | Roll cost (points, + = you pay) |
|---|---|---|---|
| LONG  | sell old synthetic → receive `F_receive(K_old, oldExp)` | buy new synthetic → pay `F_pay(K_new, newExp)` | `cost = F_pay(new) − F_receive(old)` |
| SHORT | buy back old synthetic → pay `F_pay(K_old, oldExp)` | sell new synthetic → receive `F_receive(K_new, newExp)` | `cost = F_pay(old) − F_receive(new)` |

In contango (normal state), the inter-expiry spread is positive, so:
- LONG roll cost ≈ **+fairSpread** (you pay carry) → gate wants the spread as LOW as possible.
- SHORT roll cost ≈ **−fairSpread** (you collect carry) → gate wants the spread as HIGH as possible.

**The time-of-day preference therefore FLIPS by direction.** The measured 14:30–15:30 premium
spike (+36 Mon, +50 Tue on 28-07 week) is expensive for LONG rolls and favorable for SHORT
rolls. Do not hardcode "avoid the afternoon" as a universal rule — the dynamic gate below
handles both directions automatically via the signed comparison.

**Recenter (same expiry, strike K_old → K_new):** same-expiry forwards are identical by parity,
so fair cost = **0 exactly** for both directions. Any nonzero measured cost is pure
spread-crossing/microstructure toll.

### 2.3 Fair inter-expiry spread (stays inside the futures universe — no index anywhere)

```
carryPerDay = (nextMonthlyFut_mid − frontMonthlyFut_mid) / daysBetween(frontFutExpiry, nextFutExpiry)
fairSpread(oldExp, newExp) = daysBetween(oldExp, newExp) × carryPerDay
```

- Fetch NFO:NIFTY<yy><MMM>FUT for the two nearest monthlies in the same quote call.
- If the front future has ≤ 2 days to expiry or its quote is missing/illiquid, fall back to the
  configured default (`gate.carryPerDayDefault`, start at 3.5 pts/day — consistent with observed
  ~25 pts for a 7-day gap).
- Sanity-clamp `carryPerDay` to [0, 8]; outside that, use the default and log a warning.

Reference numbers from the investigation (for threshold calibration):
- 27/28-07: Aug-04 weekly premium over index ranged +15.5 … +50 intraday; fair 7-day spread ≈ 25.
- Trade 70's actual roll (Mon ~14:45): net cost paid **12.75 pts** (lucky — sold the old July
  synthetic while it still carried +17.5 of premium). A Tuesday roll would have cost ~20–30.
- Intraday spread range on a single day was ~±15 pts around fair → a well-timed roll is worth
  **10–15 pts/month** vs a badly-timed one. At 650 qty that is ₹6.5k–₹10k/month; at the
  1000-lot target every point = ₹65,000.

---

## 3. Fix 1 — Rollover cost gate (Monday expiry roll)

### 3.1 Behavior
When the rollover triggers (manual via `RollOverTriggerController` or any automated path):

1. Fetch quotes WITH DEPTH for: old CE/PE, candidate new CE/PE, front + next monthly futures.
2. Compute `cost` per the direction table (§2.2) and `fairSpread` (§2.3).
3. Decision:
   - LONG:  execute if `cost ≤ fairSpread + gate.tolerancePoints`
   - SHORT: execute if `cost ≤ −fairSpread + gate.tolerancePoints`
     (equivalently: credit received ≥ fairSpread − tolerance)
   - else → **DEFER**: do not place orders; schedule re-evaluation in `gate.retryMinutes`.
4. Forced completion: at `gate.cutoffTime` (default 14:00) on the LAST possible roll day,
   execute unconditionally regardless of cost (**fail-open — never miss an expiry roll**).
   On non-final days (if policy ever rolls earlier than Monday), an end-of-day deferral simply
   resumes next morning.
5. Every evaluation — including deferrals — is persisted (§7) and logged.

### 3.2 Where it hooks in
- `PositionRolloverService` — wrap the existing "close old legs + open new legs" sequence behind
  the gate decision. The LIMIT-walk / confirm-cancel execution machinery is NOT touched
  (see OVERFILL race fix — leave that path exactly as is).
- `RollOverTriggerController` — when the gate defers, respond with the evaluation payload
  (cost, fair, decision, next retry time) instead of silently doing nothing, so the manual
  trigger is observable.
- Retry scheduling: reuse the existing scheduler; alternatively enqueue on `TradeExecutionQueue`
  if that becomes the standard path in the multi-strategy work. One retry loop per position id;
  a new manual trigger while a deferral is pending should re-evaluate immediately, not stack.

### 3.3 Failure handling
- Quote map empty / auth dead → same behavior as today's close path (abort + log), and keep the
  retry loop alive. At cutoff with no quotes: execute with market-protection LIMIT walk as today
  (the roll must happen; a bad price beats an expired short leg).
- One-sided book (bid or ask 0 / absent) on any required leg → treat as DEFER unless at cutoff.

---

## 4. Fix 2 — Recenter cost gate (same expiry)

### 4.1 Behavior
Fair cost is exactly 0 (§2.2), which makes this gate trivial and safe:

1. On recenter trigger, compute `cost` (direction-aware, same formulas, old strike vs new strike,
   same expiry).
2. Execute if `|cost| ≤ recenter.tolerancePoints` (start: 3). Else DEFER + retry.
3. Deferral is essentially free: the synthetic stays delta-1 at the old strike, so no market
   exposure is lost while waiting. The banked-points recenter can happen an hour later with zero
   P&L consequence.
4. Bound the wait anyway (`recenter.cutoffTime`, default 15:00 same day): the longer the wait,
   the deeper ITM one leg drifts and the wider its spread gets; and margin on the short leg
   grows. At cutoff, execute unconditionally.
5. If the deferred recenter is overtaken by events (flip/close signal arrives first), cancel the
   pending recenter — the close supersedes it. THIS IS A FEATURE: waiting sometimes saves the
   entire recenter round-trip.

### 4.2 Where it hooks in
- `ProfitRecenterService` — gate wraps the strike-shift execution.
- Shares the same gate/evaluator class and the same log table as Fix 1 (`type = RECENTER`).

Expected saving: 2–4 pts per recenter (spread-crossing avoided at bad moments), on every trade
that recenters.

---

## 5. Fix 3 — Strike selection at recenter (execution cost only)

Exposure is strike-independent (parity), so the ONLY selection criterion is the cheaper book.

1. Candidates: the two round strikes bracketing the current reference (e.g. ref 24,231 →
   24,200 and 24,250; always include the nearest 100-multiple even if slightly further).
2. For each candidate, from one quote call compute:
   - `effSpread = (CE_ask − CE_bid) + (PE_ask − PE_bid)` — the round-trip toll of that strike;
   - `depthOk` — top-5-level quantity on the relevant side of each leg ≥ order qty
     (see Fix 4 for the walk);
   - parity cross-check: implied mid-forward of candidate A vs candidate B; a disagreement
     > ~2 pts flags a stale/skewed book on one of them — prefer the other.
3. Pick min `effSpread` among candidates with `depthOk`; tie-break to the 100-multiple strike
   (structurally deeper books).
4. Log the comparison alongside the gate evaluation (both candidates' numbers).

Expected saving: a few points per recenter, effectively free since the quotes are already
fetched for Fix 2.

---

## 6. Fix 4 — Depth-aware impact check before every leg order

Preparation for the 1000-lot plan; already useful at 650 qty.

1. Kite quote API returns 5 depth levels per side. Before placing any leg order, walk the book:
   accumulate level quantities until order qty is covered; compute estimated fill VWAP.
2. `impact = |VWAP_est − bestPrice|`. If `impact > depth.maxImpactPoints` (start: 2) OR the
   5 visible levels do not cover qty → slice the order (child size = qty covered within the
   impact budget, respecting MAX_SIZE_PER_ORDER), pause `depth.sliceDelayMs` between children.
3. This must compose with, not replace, the existing LIMIT walk + confirm-cancel logic
   (the 2026-06-19 OVERFILL race fix). The impact check decides SIZE; the walk decides PRICE.
4. Known open blocker it relates to (from the scale plan): the MAX_SIZE_PER_ORDER MARKET bypass
   — closing that hole should land together with this fix.

---

## 7. Persistence: `execution_gate_log` table

One row per evaluation (executions AND deferrals). This is the receipt system that makes every
roll auditable in minutes instead of requiring candle forensics.

| column | type | notes |
|---|---|---|
| id | INTEGER PK | |
| evaluated_at | TEXT | app timestamp, same format as `position` dates |
| position_id | INTEGER | FK → position |
| gate_type | TEXT | `ROLLOVER` \| `RECENTER` |
| direction | TEXT | `LONG` \| `SHORT` |
| old_instrument_ce / _pe | TEXT | |
| new_instrument_ce / _pe | TEXT | chosen candidate (Fix 3 winner for recenters) |
| f_old / f_new | REAL | execution-aware forwards (§2.1) |
| cost_points | REAL | signed per §2.2 |
| fair_points | REAL | 0 for RECENTER |
| carry_per_day | REAL | as used; NULL when default fallback used |
| eff_spread_old / _new | REAL | round-trip spread toll of each pair |
| depth_ok | INTEGER | 0/1 |
| decision | TEXT | `EXECUTE` \| `DEFER` \| `FORCED` |
| decision_reason | TEXT | free text: threshold hit, cutoff, quote failure, superseded... |
| retry_at | TEXT | NULL unless DEFER |
| alt_strike_json | TEXT | Fix 3 comparison payload (both candidates' numbers) |

Weekly eyeball query: sum of (cost_points − fair_points) over executed rollovers = points saved
or lost vs fair; distribution of DEFER→EXECUTE improvements = what the gate is earning.

---

## 8. Configuration (`application.properties`)

```properties
# master switches — each gate independently killable in prod
gate.roll.enabled=true
gate.recenter.enabled=true
gate.strikeSelect.enabled=true
gate.depth.enabled=true

# rollover gate
gate.roll.tolerancePoints=5
gate.roll.retryMinutes=20
gate.roll.cutoffTime=14:00
gate.roll.startTime=09:25          # never evaluate before this (opening chaos)
gate.carryPerDayDefault=3.5
gate.carryPerDayMax=8.0

# recenter gate
gate.recenter.tolerancePoints=3
gate.recenter.retryMinutes=15
gate.recenter.cutoffTime=15:00

# depth / impact
gate.depth.maxImpactPoints=2.0
gate.depth.sliceDelayMs=1500
```

Threshold calibration note: tolerances above are seeded from the two-day candle study
(27/28-07). After 3–4 weeks of `execution_gate_log` data, revisit: set
`gate.roll.tolerancePoints` ≈ the observed intraday spread stdev, and tighten
`gate.recenter.tolerancePoints` toward the typical ATM effSpread.

---

## 9. Test plan

Unit (MockKiteGateway quote fixtures):
1. LONG roll, spread rich (cost = fair + 12) → DEFER with retry_at set.
2. LONG roll, spread fair (cost = fair + 2) → EXECUTE.
3. SHORT roll, same rich-spread fixture → EXECUTE (sign flip — the spike is a credit for
   shorts). This test exists specifically to catch the §2.2 sign bug.
4. Cutoff reached while DEFER pending → FORCED execution, decision_reason=cutoff.
5. Quote map empty pre-cutoff → DEFER; empty at cutoff → FORCED via existing path.
6. Recenter with |cost| 1.5 → EXECUTE; with 6 → DEFER; flip signal arrives during DEFER →
   recenter cancelled, decision_reason=superseded.
7. Strike selection: fixture where 100-strike has wider spread than 50-strike → 50 chosen;
   equal → 100 chosen (tie-break).
8. Depth walk: qty larger than top-5 depth → slicing invoked; child sizes respect
   MAX_SIZE_PER_ORDER.

Replay validation (documented expectation, manual):
- Using the 27-07 half-hourly basis path (+18.0/+21.5/+25.3/+24.8/+20.8/+15.5/+22.2/+23.7/
  +18.0/+19.7/+25.2/+36.0 at 09:30→15:00 vs fair ≈ 25 and tolerance 5): a LONG roll gate
  evaluating from 09:30 would EXECUTE immediately (18.0 ≤ 30) — i.e. on that particular day the
  gate's win is avoiding the 14:30+ window, worth ~10–15 pts vs the actual 14:45 execution.

Prod rollout:
1. Phase A — observe-only: gates evaluate and LOG but always execute (decision recorded as what
   it WOULD have done). Run across at least one monthly roll cycle (target: the 25-Aug roll).
2. Phase B — enable `gate.recenter` first (fair=0, lowest risk), then `gate.roll`.
3. Phase C — depth slicing, together with the MAX_SIZE_PER_ORDER MARKET-bypass fix.

---

## 10. Expected value summary

| Fix | Saving | Frequency | Annual (650 qty) |
|---|---|---|---|
| 1. Roll cost gate | 10–15 pts | ~12 rolls/yr | ~₹80k–₹120k |
| 2. Recenter gate | 2–4 pts | every recentering trade | ~₹15k–₹40k |
| 3. Strike selection | 1–3 pts | every recenter | ~₹10k–₹25k |
| 4. Depth/impact | ~0 now; existential at 1000 lots | every order | scale enabler |

At 1000 lots multiply by ~100: the package is worth roughly ₹1–1.5 Cr/yr of execution
difference at target scale.
