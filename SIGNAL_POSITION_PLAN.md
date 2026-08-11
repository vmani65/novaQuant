# One Position Per Signal — restructure plan (2026-08-10)

## Goal

A signal is ONE opportunity. The POSITION row is what the owner does with that opportunity:
direction, sizing, capital requirement, cumulative P&L. Execution detail — which books traded
it, on which contracts, with what fills — lives on the legs. This replaces the two-rows-per-signal
model (one POSITION row per book) deployed 2026-08-10.

Future road: at 1000+ lots one signal's size is SPLIT across buying and selling weekly and
monthly options. Books are allocations of one sized opportunity, not independent positions.
The leg table scales to that without further schema change (each leg: book, qty, margin, P&L).

## Schema

- `WEEKLY_LEG.BOOK` (new, String 15): which book owns the leg — SYNTH_WEEKLY / LONG_MONTHLY.
  Stamped at leg creation by buildWeeklyInstrument / buildMonthlyInstrument (the builder IS the
  book context). This is the discriminator; instrument prefix cannot be (in monthly-expiry week
  both books hold the identical contract).
- `POSITION.MONTHLY_BASELINE_SPOT`, `POSITION.MONTHLY_BANKED_POINTS` (new, Double): the
  LONG_MONTHLY banking chain. Existing BASELINE_SPOT / BANKED_POINTS become the SYNTH_WEEKLY
  chain (all history is weekly, so legacy semantics are unchanged). Both chains telescope to
  the same spot move (entry→exit); they differ only in SEGMENT boundaries (weekly recenters,
  monthly rolls), which is what per-leg expectedPnl stamping needs.
- `POSITION.WEEKLY_MARGIN_PER_LOT` (new, Double): running max of
  Σ(weekly LIVE legs' marginRequired) / weekly lots, maintained by calcPeakMargin. Feeds the
  NRML cost stats, which must not be poisoned by monthly premium in the now-cumulative
  PEAK_MARGIN.
- `POSITION.BOOK`: field REMOVED from the entity (column stays in SQLite, dead). Leg book is
  backfilled from it first — see Migration.
- PEAK_MARGIN becomes cumulative across books (running max of Σ ALL live legs' margin).
  ACTUAL_PNL / EXPECTED_PNL / TOTAL_CHARGES / LOTS become cross-book sums.

## Status roll-up (single truth table, used by open/close/reconcilers)

rollUpStatus(all legs of the row), first match wins:
1. any leg PENDING_OPEN            → PENDING_OPEN
2. any leg PENDING_CLOSE           → PENDING_CLOSE
3. any book with a LIVE leg AND a neverTraded FAILED leg → PARTIAL  (orphan-origin open)
4. any leg LIVE                    → LIVE
5. any traded leg, all traded CLOSED → CLOSED; else → FAILED
6. no traded legs                  → FAILED

Operational selection no longer reads row status: the close/roll/recenter/flip paths find
"the row holding LIVE legs of book X" via a leg-scoped query. Reconcilers find rows via
leg-status queries. Row status is reporting + the post-close gate.

## Invariants

- The fan-out (SignalService) creates ONE Position per signal and passes the same instance to
  both books. Each book appends its legs. A disabled book appends nothing (close-only).
- Every mutation path (close, rollover, recenter, interleaved flip) operates ONLY on
  legs.filter(book == X). The other book's legs on the same row are untouchable.
- Weekly banking writes baselineSpot/bankedPoints; monthly banking writes the MONTHLY_ pair.
  entrySpot/exitSpot stay immutable and shared (same underlying opportunity).
- afterOpen/afterClose run ONCE per row per signal, at the END of the fan-out — never inside a
  book branch. (Inside-branch calls raced the other book's save of the same row: the async
  enrichment merge could be clobbered by the second book saving a stale in-memory copy.
  Once-per-row also halves the Kite enrichment calls.)
- Post-close accounting (calcTradeOutcome/calcPnL/recalculateCapital) runs only when the row is
  terminal: no leg LIVE / PENDING_*. Capital chain moves ONCE per signal. A monthly leg stuck
  PENDING_CLOSE defers the signal's capital stamp until the reconciler settles it — one
  opportunity's accounting completes when all its money moves are known.
- calcPnL runs per book over the shared row: moneyness pair-grouping, banked chain, and the
  expected factor (0.5 monthly futures-equivalent, 1.0 weekly) are all per-book; row totals are
  the sums. result = points sign when a full weekly pair exists (delta-1 authoritative), else
  rupee sign.

## Migration (startup, idempotent — no row merging ever needed)

Monthly book never fired in prod (toggle OFF since deploy), and all history is one row per
signal already (weekly-only). So:
1. Hibernate ddl-auto adds the new columns.
2. LegBookBackfill (@PostConstruct, native SQL, replaces BookBackfill):
   `UPDATE WEEKLY_LEG SET BOOK = COALESCE((SELECT BOOK FROM POSITION p WHERE p.ID =
    WEEKLY_LEG.POSITION_ID), 'SYNTH_WEEKLY') WHERE BOOK IS NULL`
3. Nothing else. Legacy rows keep their columns; null MONTHLY_* is guarded everywhere.

## Deliberate behavior changes (owner-visible)

- Equity curve: one point per signal (the owner's stated requirement). Book filter becomes a
  leg predicate; a single-book view plots the cumulative Σ of that book's leg P&L (a per-book
  capital chain no longer exists — capital is account+signal level).
- Trade log: the client-side signal-grouping heuristic (b5537d5) is deleted; the row IS the
  signal. Legs show their book badge.
- A failed quote fetch during one book's close no longer stamps the (now shared) row FAILED —
  the row keeps its roll-up status and the book's legs stay LIVE for the next attempt.
- MonthlyFlipService's "multi-leg → decline interleave" guard now counts LONG_MONTHLY legs only.
- NRML cost stats read WEEKLY_MARGIN_PER_LOT (fallback for legacy rows: old book-filtered
  PEAK_MARGIN/LOTS formula).

## Test plan

Update the 17 book-dependent classes (inventory in session notes); keep the 10 pure classes.
New coverage: rollUpStatus matrix; one-row fan-out (3 legs, 2 books, one save chain); mixed
close failure (weekly CLOSED + monthly PENDING_CLOSE → capital deferred); shared-contract week
roll on ONE row (weekly trigger moves only weekly-booked legs); orphan flatten touches only its
book's legs; capital stamped once per signal; equity curve one point; LegBookBackfill.

## Build status — 2026-08-10 (NOT deployed)

BUILT AND VERIFIED, awaiting owner approval to deploy. 184/184 tests green (was 161; new:
LegScopeTest, PostTradeServiceSharedRowTest, EquityCurveServiceBookTest, shared-row fan-out
tests, interleave weekly-leg test). mock,test smoke on signals_test.db: LegBookBackfill
stamped 33 legacy legs; a two-book flip opened ONE row with 3 booked legs and shared
monthlyBaselineSpot; the exit closed the row with cumulative P&L, per-book expected factors
(weekly 1.0 / monthly 0.5) and exactly one capital-chain step; a second flip→exit chained
starting/ending capital correctly; both roll triggers no-churn-skipped per book on the shared
contracts; recenter 450-floor abort intact; mid-fan-out rows logged "stays LIVE — the other
book's legs are still open".

Hardenings added beyond the plan during implementation:
- rollUpStatus rule 5 uses trade EVIDENCE (fills OR openOrderId) so a close-FAILED leg whose
  async fill enrichment hasn't landed can never let the row read CLOSED over a live broker
  position.
- A trim-to-filled partial open stamps PARTIAL explicitly in materializeAndSave (its legs are
  all LIVE, so the roll-up alone can't see the half-failed open).
- afterClose re-derives status from the full leg set (never downgrading an explicit PARTIAL)
  and re-runs of an accounted row can't double-advance the capital chain (endingCapital guard).
- A roll/recenter that closes legs but opens nothing now rolls the status up and runs
  post-close accounting instead of leaving a zombie LIVE row invisible to the leg-scoped
  finders.
