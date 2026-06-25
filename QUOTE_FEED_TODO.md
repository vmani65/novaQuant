# TODO / Design note — move quote freshness off the order-placement critical path

**Status:** parked, not implemented. Raised during review of the 2026-06-25 LIMIT-walk staleness fix.

## Problem

`PositionUtil.placeGraduatedLimit` calls `refreshQuote(ins)` — a **synchronous `getQuote` REST
round-trip** — at walk-start and again on every walk step. With 2–4 legs executing in parallel
(close + open on a flip), each firing up to ~4 `getQuote` calls, this puts network latency and a
burst of REST traffic directly on the order-placement critical path, exactly when the market is
moving fastest.

The walk thread should instead **read a cache** that some other thread keeps fresh, so the critical
path does zero quote I/O. (Java 25 virtual threads make the background refresher cheap.)

See: [`PositionUtil.refreshQuote`](src/main/java/path/to/_40c/nqCore/util/PositionUtil.java),
used inside `placeGraduatedLimit` (walk-start + per-step chase).

## Key constraint

**Kite rate-limits the quote REST endpoint to ~1 request/sec.** This caps any polling approach.

## Options considered

### A. WS market-data ticks (push)  ← recommended for true freshness
- The app already runs a `KiteTicker` WebSocket, but only for order postbacks
  ([`KiteTickerOrderStream`](src/main/java/path/to/_40c/nqCore/gateway/KiteTickerOrderStream.java)).
- That same ticker can `subscribe(tokens)` + `setMode(MODE_FULL)` to stream **5-level depth** in
  real time — multiple ticks/sec, no REST rate limit. `onTicks` → update a
  `ConcurrentHashMap<token, Depth>` cache; the walk reads it.
- Cost / work:
  - Ticks key on **instrument tokens**, not `NFO:SYMBOL` — need a token lookup
    (`getInstruments(NFO)` once/day, cache symbol→token).
  - Subscription lifecycle: subscribe the leg tokens when a trade starts, unsubscribe when done
    (or keep a small rolling working-set). Decide whether to reuse the existing order-stream ticker
    (simpler infra, mixes concerns) or stand up a parallel market-data ticker (cleaner separation,
    second connection to manage — note the 2026-06-11 reconnect/timer-leak lessons baked into
    `KiteTickerOrderStream`).

### B. Virtual-thread quote poller (the literal "self-refreshing virtual thread")
- One background virtual thread batches `getQuote` for all currently-active leg instruments
  (one call covers all legs — Kite quote takes many symbols) into a `volatile` cache every ~1s.
- Walk reads `cache.latest(ins)` — O(1), no I/O.
- Simpler (pure REST, no token lookup, easy lifecycle), BUT freshness is capped at ~1/sec by the
  rate limit, so the cache can be ~1s stale — roughly one update per walk step. Still far better
  than today's 3–6s-stale entry snapshot, but not truly "live."

### C. Status quo
- Keep the synchronous per-step `refreshQuote`. Works; just leaves REST latency on the critical path.

## Either-way design points
- Walk reads the cache; the background thread (VT poller or WS event thread) does the work.
- **Cold-cache fallback:** first read may be empty — fall back to the last-known / passed-in quote
  (`q`), exactly as `placeGraduatedLimit` already does when `midHalfSpread` returns null. So the
  change is drop-in behind the existing fallback.
- **Batching** (option B) is the efficiency win: one `getQuote` for all active legs, not N×4.
- Reuse `midHalfSpread(...)` for the bid/ask/mid/half-spread extraction regardless of source.

## Recommendation
If we want genuinely-live depth, go **A (WS ticks)** — the rate limit makes polling a compromise.
If we want the smaller, lower-risk step first, **B** is a clean intermediate that still gets the
REST call off the critical path. This is a separate follow-up from the `confirmMarketFill` +
walk-chase change (that one stands on its own; the walk-chase already works with the synchronous
fetch, just sub-optimally).
