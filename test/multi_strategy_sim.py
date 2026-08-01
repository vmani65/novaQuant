"""
Three-strategy concurrent simulation against nqCore running in mock+test profiles.

Run modes:
    python multi_strategy_sim.py                 # full multi-strategy scenario suite
    python multi_strategy_sim.py --margin-block  # margin pre-check scenario (boot the app
                                                 # with -Dmock.available-funds=1000 first)

Prerequisites: app started fresh (empty signals_test.db) with
    mvn spring-boot:run -Dspring-boot.run.profiles=mock,test \
        -Dspring-boot.run.arguments=--server.port=18080

The suite covers, in order:
  1.  bootstrap: symbols + default templates + registry rows (SIM1 lots-override,
      SIM2 own SHORT templates, SIM3 plain defaults)
  2.  unregistered-strategy rejection
  3.  three simultaneous entries at one spot -> distinct strikes, per-strategy sizing
  4.  duplicate + invalid-sequence rejection
  5.  flip isolation (SIM3 flips; SIM1/SIM2 books untouched)
  6.  strategy-less recenter trigger -> only the book whose own baseline qualifies
  7.  rollover-trigger day -> every live book rolls, symbol promoted exactly once
  8.  three simultaneous exits -> each strategy closes its own book only
  9.  accounting invariants: strategy-pnl vs per-trade sums, equity-curve chain identity
  10. queue health: no ERROR outcomes end-to-end
"""

import json
import sys
import threading
import time
import urllib.parse
import urllib.request
from datetime import date

BASE = "http://localhost:18080"
FAILURES = []
CHECKS = 0


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=30) as r:
        body = r.read().decode()
        return json.loads(body) if body.strip() else None


def post(path, payload=None):
    data = json.dumps(payload).encode() if payload is not None else b""
    req = urllib.request.Request(BASE + path, data=data, method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        body = r.read().decode()
        return json.loads(body) if body.strip() else None


def check(name, condition, detail=""):
    global CHECKS
    CHECKS += 1
    status = "PASS" if condition else "FAIL"
    print(f"  [{status}] {name}" + (f"  ({detail})" if detail and not condition else ""))
    if not condition:
        FAILURES.append(f"{name}: {detail}")


def drain_queue(timeout_s=90):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        st = get("/api/queue/status")
        if st["depth"] == 0:
            return st
        time.sleep(0.4)
    raise TimeoutError("queue did not drain")


def signal(action, sig_type, price, strategy, when):
    q = urllib.parse.urlencode({"signalType": sig_type, "currentPrice": price,
                                "strategyName": strategy, "time": when})
    with urllib.request.urlopen(f"{BASE}/api/{action}?{q}", timeout=30) as r:
        r.read()


def fire_concurrently(calls):
    threads = [threading.Thread(target=signal, args=c) for c in calls]
    for t in threads:
        t.start()
    for t in threads:
        t.join()


def trades():
    return get("/api/trades")


def live_positions():
    return [t for t in trades() if t["status"] in ("LIVE", "PARTIAL")]


def positions_of(strategy, status=None):
    return [t for t in trades()
            if t["strategyName"] == strategy and (status is None or t["status"] == status)]


def live_leg_rows(t):
    return [l for l in t["legs"] if l["status"] == "LIVE"]


# ---------------------------------------------------------------- scenarios

def scenario_bootstrap():
    print("\n== 1. bootstrap: symbols, templates, registry ==")
    today = date.today().isoformat()
    q = urllib.parse.urlencode({"thisWeekSymbol": "26505", "rolloverSymbol": "26512",
                                "rolloverDay": today})
    post(f"/symbol/save?{q}")
    for tpl in [("LONG", "CE", "BUY"), ("LONG", "PE", "SELL"),
                ("SHORT", "PE", "BUY"), ("SHORT", "CE", "SELL")]:
        post("/api/leg-templates", {"direction": tpl[0], "optionType": tpl[1],
                                    "side": tpl[2], "offsetPts": 0, "lots": 1})
    for tpl in [("SHORT", "PE", "BUY"), ("SHORT", "CE", "SELL")]:
        post("/api/leg-templates", {"direction": tpl[0], "optionType": tpl[1], "side": tpl[2],
                                    "offsetPts": 0, "lots": 3, "strategyName": "SIM2"})
    post("/api/strategy-registry/save", {"name": "SIM1", "enabled": True, "lots": 2})
    post("/api/strategy-registry/save", {"name": "SIM2", "enabled": True})
    post("/api/strategy-registry/save", {"name": "SIM3", "enabled": True})
    post("/api/capital/save", {"currentCapital": 2000000.0, "ceilingToHit": 5000000.0,
                               "definedRiskPerLot": 200000})
    registry = {r["name"] for r in get("/api/strategy-registry")}
    check("three strategies registered", {"SIM1", "SIM2", "SIM3"} <= registry, str(registry))
    capital = get("/api/capital")
    check("capital pool configured", capital and capital.get("currentCapital") == 2000000.0,
          str(capital))


def scenario_unregistered_rejected():
    print("\n== 2. unregistered strategy rejected ==")
    before = len(trades())
    signal("longEntry", "CE", "23500", "GHOST", "31-07-2026 09:30:00")
    drain_queue()
    check("no position created for GHOST", len(trades()) == before)


def scenario_simultaneous_entries():
    print("\n== 3. three simultaneous entries at spot 23500 ==")
    fire_concurrently([
        ("longEntry", "CE", "23500", "SIM1", "31-07-2026 10:00:00"),
        ("shortEntry", "PE", "23500", "SIM2", "31-07-2026 10:00:00"),
        ("longEntry", "CE", "23500", "SIM3", "31-07-2026 10:00:00"),
    ])
    drain_queue()
    time.sleep(2)
    live = live_positions()
    check("exactly 3 live positions", len(live) == 3, f"got {len(live)}")
    by_strategy = {t["strategyName"]: t for t in live}
    check("one book per strategy", set(by_strategy) == {"SIM1", "SIM2", "SIM3"}, str(set(by_strategy)))

    strikes = set()
    for t in live:
        legs = live_leg_rows(t)
        leg_strikes = {l["strike"] for l in legs}
        check(f"{t['strategyName']} legs share one strike", len(leg_strikes) == 1, str(leg_strikes))
        strikes.add(leg_strikes.pop())
    check("three DISTINCT strikes (occupancy)", len(strikes) == 3, str(strikes))
    check("strikes within +/-150 of ATM", all(abs(s - 23500) <= 150 for s in strikes), str(strikes))

    qty = {s: live_leg_rows(by_strategy[s])[0]["quantity"] for s in by_strategy}
    check("SIM1 sized by registry override (2 lots -> 130)", qty["SIM1"] == 130, str(qty))
    check("SIM2 sized by own templates (3 lots -> 195)", qty["SIM2"] == 195, str(qty))
    check("SIM3 sized by defaults (1 lot -> 65)", qty["SIM3"] == 65, str(qty))
    check("SIM2 direction SHORT", by_strategy["SIM2"]["direction"] == "SHORT")


def scenario_duplicate_and_sequence():
    print("\n== 4. duplicate + invalid sequence rejected ==")
    before = len(positions_of("SIM1"))
    signal("longEntry", "CE", "23500", "SIM1", "31-07-2026 10:00:00")   # exact duplicate
    signal("longEntry", "CE", "23600", "SIM1", "31-07-2026 10:15:00")   # invalid after entry
    drain_queue()
    check("no extra SIM1 position from duplicate/invalid-sequence",
          len(positions_of("SIM1")) == before)


def scenario_flip_isolation():
    print("\n== 5. SIM3 flips; other books untouched ==")
    sim1_before = positions_of("SIM1", "LIVE")[0]["id"]
    sim2_before = positions_of("SIM2", "LIVE")[0]["id"]
    signal("flip", "PE", "23480", "SIM3", "31-07-2026 10:30:00")
    drain_queue()
    time.sleep(2)
    sim3_live = positions_of("SIM3", "LIVE")
    sim3_closed = positions_of("SIM3", "CLOSED")
    check("SIM3 old book closed", len(sim3_closed) == 1, str(len(sim3_closed)))
    check("SIM3 new book live and SHORT",
          len(sim3_live) == 1 and sim3_live[0]["direction"] == "SHORT")
    check("SIM1 book untouched by flip", positions_of("SIM1", "LIVE")[0]["id"] == sim1_before)
    check("SIM2 book untouched by flip", positions_of("SIM2", "LIVE")[0]["id"] == sim2_before)


def scenario_selective_recenter():
    print("\n== 6. recenter trigger: only the qualifying book recenters ==")
    with urllib.request.urlopen(f"{BASE}/api/realize-profits?currentPrice=24000", timeout=30) as r:
        r.read()
    drain_queue()
    time.sleep(2)
    sim1 = positions_of("SIM1", "LIVE")[0]
    sim2 = positions_of("SIM2", "LIVE")[0]
    sim3 = positions_of("SIM3", "LIVE")[0]
    check("SIM1 (LONG +500) recentered: baseline 24000", sim1["baselineSpot"] == 24000.0, str(sim1["baselineSpot"]))
    check("SIM1 banked 500 points", sim1["bankedPoints"] == 500.0, str(sim1["bankedPoints"]))
    check("SIM2 (SHORT, losing) NOT recentered", sim2["baselineSpot"] == 23500.0, str(sim2["baselineSpot"]))
    check("SIM3 (SHORT, losing) NOT recentered", sim3["baselineSpot"] == 23480.0, str(sim3["baselineSpot"]))


def scenario_rollover_all():
    print("\n== 7. rollover-trigger rolls every live book, promotes once ==")
    with urllib.request.urlopen(f"{BASE}/api/rollover-trigger?currentPrice=24000", timeout=30) as r:
        r.read()
    drain_queue(timeout_s=120)
    time.sleep(2)
    strike_map = get("/api/strike-map")
    check("all live legs on rollover symbol 26512",
          all(row["instrument"].startswith("NIFTY26512") for row in strike_map),
          str([r["instrument"] for r in strike_map]))
    strikes_by_strategy = {}
    for row in strike_map:
        strikes_by_strategy.setdefault(row["strategyName"], set()).add(row["strike"])
    per_book_strikes = [next(iter(v)) for v in strikes_by_strategy.values()]
    check("each book on a single strike", all(len(v) == 1 for v in strikes_by_strategy.values()),
          str(strikes_by_strategy))
    check("strikes still distinct across books after roll",
          len(set(per_book_strikes)) == len(per_book_strikes), str(strikes_by_strategy))
    check("three books still live", len(live_positions()) == 3)


def scenario_simultaneous_exits():
    print("\n== 8. three simultaneous exits: each closes its own book ==")
    live_ids = {t["strategyName"]: t["id"] for t in live_positions()}
    fire_concurrently([
        ("longExit", "CE", "24050", "SIM1", "31-07-2026 14:00:00"),
        ("shortExit", "PE", "24050", "SIM2", "31-07-2026 14:00:00"),
        ("shortExit", "PE", "24050", "SIM3", "31-07-2026 14:00:00"),
    ])
    drain_queue(timeout_s=120)
    time.sleep(3)
    check("no live positions remain", len(live_positions()) == 0,
          str([(t['strategyName'], t['status']) for t in live_positions()]))
    for strategy, pid in live_ids.items():
        closed = [t for t in trades() if t["id"] == pid]
        check(f"{strategy} book id={pid} CLOSED with its own exit",
              closed and closed[0]["status"] == "CLOSED"
              and closed[0]["strategyName"] == strategy,
              str(closed[0]["status"] if closed else "missing"))


def scenario_accounting_invariants():
    print("\n== 9. accounting invariants ==")
    time.sleep(3)  # allow post-close tasks to finish
    drain_queue()
    all_trades = trades()
    pnl_rows = {r["strategyName"]: r for r in get("/api/strategy-pnl")}
    for strategy in ("SIM1", "SIM2", "SIM3"):
        per_trade = round(sum(t["actualPnl"] or 0.0 for t in all_trades
                              if t["strategyName"] == strategy and t["actualPnl"] is not None), 1)
        summary = pnl_rows.get(strategy, {}).get("totalPnl")
        check(f"{strategy} strategy-pnl equals per-trade sum ({per_trade})",
              summary is not None and abs(summary - per_trade) < 0.11,
              f"summary={summary} per-trade={per_trade}")
        curve = get(f"/api/equity-curve?strategy={strategy}")
        chain = round(sum(e - s for e, s in zip(curve["equity"], curve["startingCapitals"])), 1)
        check(f"{strategy} equity chain sums to its P&L",
              abs(chain - per_trade) < 0.11, f"chain={chain} per-trade={per_trade}")


def scenario_queue_health():
    print("\n== 10. queue health ==")
    st = get("/api/queue/status")
    errors = [r for r in st["recent"] if str(r["outcome"]).startswith("ERROR")]
    check("no ERROR outcomes in queue history", not errors, str(errors))


def scenario_margin_block():
    print("\n== margin-block: app booted with mock.available-funds=1000 ==")
    today = date.today().isoformat()
    q = urllib.parse.urlencode({"thisWeekSymbol": "26505", "rolloverSymbol": "26512",
                                "rolloverDay": today})
    post(f"/symbol/save?{q}")
    for tpl in [("LONG", "CE", "BUY"), ("LONG", "PE", "SELL")]:
        post("/api/leg-templates", {"direction": tpl[0], "optionType": tpl[1],
                                    "side": tpl[2], "offsetPts": 0, "lots": 1})
    post("/api/strategy-registry/save", {"name": "SIMM", "enabled": True})
    before_live = len(live_positions())
    signal("longEntry", "CE", "23500", "SIMM", "31-07-2026 09:30:00")
    drain_queue()
    time.sleep(1)
    blocked = positions_of("SIMM")
    check("position persisted", len(blocked) == 1, str(len(blocked)))
    if blocked:
        check("position FAILED (never reached broker)", blocked[0]["status"] == "FAILED",
              blocked[0]["status"])
        check("block reason recorded", "MARGIN BLOCKED" in (blocked[0]["message"] or ""),
              str(blocked[0]["message"]))
        check("no leg has an order id",
              all(not l.get("openOrderId") for l in blocked[0]["legs"]))
    check("no new live position", len(live_positions()) == before_live)


def main():
    margin_mode = "--margin-block" in sys.argv
    print(f"multi-strategy sim against {BASE} ({'margin-block' if margin_mode else 'full suite'})")
    if margin_mode:
        scenario_margin_block()
    else:
        scenario_bootstrap()
        scenario_unregistered_rejected()
        scenario_simultaneous_entries()
        scenario_duplicate_and_sequence()
        scenario_flip_isolation()
        scenario_selective_recenter()
        scenario_rollover_all()
        scenario_simultaneous_exits()
        scenario_accounting_invariants()
        scenario_queue_health()

    print(f"\n{'=' * 60}")
    print(f"checks: {CHECKS}  failures: {len(FAILURES)}")
    for f in FAILURES:
        print(f"  FAIL: {f}")
    sys.exit(1 if FAILURES else 0)


if __name__ == "__main__":
    main()
