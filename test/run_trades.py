#!/usr/bin/env python3
"""
nqCore E2E harness — 10 realistic NIFTY weekly option scenarios.

Prerequisites:
  1. nqCore (mock):   mvn spring-boot:run -Dspring-boot.run.profiles=mock
  2. nq-ticker stub:     python test/nq_ticker_stub.py    (trade 9 only)
  3. pip install requests

Signal prices reflect a realistic intraday NIFTY range (24350–24850).
Strategy names are NiftyPulse_D01..D10 so they sort cleanly in the DB.
"""

import sys
import time
import requests
from datetime import datetime

BASE        = "http://localhost:8080"
POST_OPEN   = 5    # afterOpen async (exec price + margin fetch)
POST_CLOSE  = 5    # afterClose async (PnL + capital calc)
POST_FLIP   = 12   # afterClose old leg + afterOpen new leg (serial executor: sequential, not parallel)
POST_RECEN  = 4    # realize-profits is synchronous-ish; small buffer
STUB_WAIT   = 16   # stub 2s delay + execute-close + afterClose (serial executor)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def ts():
    return datetime.now().strftime("%H:%M:%S")

def get(endpoint, **params):
    try:
        r = requests.get(f"{BASE}{endpoint}", params=params, timeout=15)
        snippet = r.text.strip()[:100]
        return r.status_code, snippet
    except Exception as e:
        return 0, str(e)

def pause(label, secs):
    print(f"      ... {label} ({secs}s)")
    time.sleep(secs)

def call(label, endpoint, **kw):
    code, body = get(endpoint, **kw)
    mark = "OK" if 200 <= code < 300 else f"ERR-{code}"
    print(f"    [{mark}] {label:20s}  {body}")

def long_entry(s, price):
    call(f"longEntry  {price}", '/api/longEntry',  signalType='CE', currentPrice=price, strategyName=s, time=ts())

def long_exit(s, price, t=None):
    call(f"longExit   {price}", '/api/longExit',   signalType='CE', currentPrice=price, strategyName=s, time=t or ts())

def short_entry(s, price):
    call(f"shortEntry {price}", '/api/shortEntry', signalType='PE', currentPrice=price, strategyName=s, time=ts())

def short_exit(s, price):
    call(f"shortExit  {price}", '/api/shortExit',  signalType='PE', currentPrice=price, strategyName=s, time=ts())

def flip_short(s, price):
    call(f"flip->SHORT {price}", '/api/flip', signalType='PE', currentPrice=price, strategyName=s, time=ts())

def flip_long(s, price):
    call(f"flip->LONG  {price}", '/api/flip', signalType='CE', currentPrice=price, strategyName=s, time=ts())

def recenter(price):
    call(f"recenter   {price}", '/api/realize-profits', currentPrice=price)


# ---------------------------------------------------------------------------
# 10 Scenarios — signal prices are NIFTY spot (24350–24850 range)
# ---------------------------------------------------------------------------

def trade_1(s):
    """Long CE — clean breakout, strong WIN (+300 pts)"""
    long_entry(s, 24450);  pause("afterOpen",  POST_OPEN)
    long_exit(s,  24750);  pause("afterClose", POST_CLOSE)

def trade_2(s):
    """Long CE — failed breakout, LOSS (-220 pts)"""
    long_entry(s, 24600);  pause("afterOpen",  POST_OPEN)
    long_exit(s,  24380);  pause("afterClose", POST_CLOSE)

def trade_3(s):
    """Short PE — bearish momentum, WIN (-260 pts)"""
    short_entry(s, 24650); pause("afterOpen",  POST_OPEN)
    short_exit(s,  24390); pause("afterClose", POST_CLOSE)

def trade_4(s):
    """Short PE — trap reversal, LOSS (+240 pts against)"""
    short_entry(s, 24500); pause("afterOpen",  POST_OPEN)
    short_exit(s,  24740); pause("afterClose", POST_CLOSE)

def trade_5(s):
    """Long CE → Flip → Short PE → WIN  (trend exhaustion + reversal)"""
    long_entry(s,  24420); pause("afterOpen",  POST_OPEN)
    flip_short(s,  24690); pause("afterFlip",  POST_FLIP)
    short_exit(s,  24430); pause("afterClose", POST_CLOSE)

def trade_6(s):
    """Short PE → Flip → Long CE → WIN  (bounce off support)"""
    short_entry(s, 24720); pause("afterOpen",  POST_OPEN)
    flip_long(s,   24370); pause("afterFlip",  POST_FLIP)
    long_exit(s,   24660); pause("afterClose", POST_CLOSE)

def trade_7(s):
    """Long CE → ProfitRecenter → exit WIN  (trail the trend)"""
    long_entry(s, 24480);  pause("afterOpen",    POST_OPEN)
    recenter(24730);       pause("afterRecenter", POST_RECEN)
    long_exit(s,  24850);  pause("afterClose",   POST_CLOSE)

def trade_8(s):
    """Short PE → ProfitRecenter → exit WIN  (accelerating decline)"""
    short_entry(s, 24640); pause("afterOpen",    POST_OPEN)
    recenter(24360);       pause("afterRecenter", POST_RECEN)
    short_exit(s,  24200); pause("afterClose",   POST_CLOSE)

def trade_9(s):
    """Long CE → 9:15 longExit → execute-close via nq-ticker stub"""
    long_entry(s, 24530);             pause("afterOpen",  POST_OPEN)
    long_exit(s,  24530, t="09:15"); pause("stub->execute-close", STUB_WAIT)

def trade_10(s):
    """Short PE → WIN  (capital waterfall sanity check)"""
    short_entry(s, 24580); pause("afterOpen",  POST_OPEN)
    short_exit(s,  24320); pause("afterClose", POST_CLOSE)


SCENARIOS = [
    ("NiftyPulse_D01", "Long CE  WIN  +300 pts",              trade_1),
    ("NiftyPulse_D02", "Long CE  LOSS -220 pts",              trade_2),
    ("NiftyPulse_D03", "Short PE WIN  -260 pts",              trade_3),
    ("NiftyPulse_D04", "Short PE LOSS +240 pts against",      trade_4),
    ("NiftyPulse_D05", "Long CE -> Flip Short PE -> WIN",     trade_5),
    ("NiftyPulse_D06", "Short PE -> Flip Long CE -> WIN",     trade_6),
    ("NiftyPulse_D07", "Long CE -> Recenter -> exit WIN",     trade_7),
    ("NiftyPulse_D08", "Short PE -> Recenter -> exit WIN",    trade_8),
    ("NiftyPulse_D09", "Long CE -> 9:15 stub close",          trade_9),
    ("NiftyPulse_D10", "Short PE WIN  capital check",         trade_10),
]


# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------

def check_server():
    try:
        requests.get(f"{BASE}/api/trades", timeout=5)
    except Exception:
        print(f"\nERROR: nqCore not reachable at {BASE}")
        print("  Start it:  mvn spring-boot:run -Dspring-boot.run.profiles=mock")
        sys.exit(1)

def print_summary():
    print("\n" + "=" * 72)
    print("  RESULTS SUMMARY")
    print("=" * 72)
    try:
        trades = requests.get(f"{BASE}/api/trades", timeout=10).json()
        runs = [t for t in trades if str(t.get("statergyName", "")).startswith("NiftyPulse_")]
        runs = sorted(runs, key=lambda t: t.get("id", 0))

        print(f"{'ID':<5} {'Strategy':<18} {'Status':<8} {'Outcome':<6} {'PnL (₹)':>12} {'Capital':>14}")
        print("-" * 68)
        total_pnl = 0
        for t in runs:
            pnl_raw = t.get("actualPnL")
            pnl_str = f"{pnl_raw:>12.2f}" if isinstance(pnl_raw, (int, float)) else f"{'N/A':>12}"
            if isinstance(pnl_raw, (int, float)):
                total_pnl += pnl_raw
            print(
                f"{str(t.get('id','')):<5} "
                f"{str(t.get('statergyName','')):<18} "
                f"{str(t.get('tradeStatus','')):<8} "
                f"{str(t.get('tradeOutcome','')):<6} "
                f"{pnl_str} "
                f"{str(t.get('endingCapital','N/A')):>14}"
            )
        print("-" * 68)
        print(f"{'':37} {'Total PnL':>12}  {total_pnl:>14.2f}")

        # Leg detail for any trade with missing prices
        print("\n  LEG DETAIL (boughtPrice / soldPrice)")
        print(f"  {'ID':<5} {'Symbol':<22} {'Bought':>10} {'Sold':>10} {'Margin':>12}")
        for t in runs:
            for w in t.get("weekly", []):
                sym   = str(w.get("tradedSymbol",""))[:22]
                bp    = w.get("boughtPrice","?")
                sp    = w.get("soldPrice","?")
                mg    = w.get("marginToTrade","?")
                flag  = " *** MISSING" if bp == "?" and sp == "?" else ""
                print(f"  {str(t.get('id','')):<5} {sym:<22} {str(bp):>10} {str(sp):>10} {str(mg):>12}{flag}")
    except Exception as e:
        print(f"  Could not fetch summary: {e}")
    print()


def run_all():
    check_server()
    print("=" * 72)
    print("  nqCore E2E - 10 NIFTY Weekly Option Scenarios  [mock mode]")
    print("=" * 72)

    for i, (strat, label, fn) in enumerate(SCENARIOS, start=1):
        print(f"\nTrade {i:02d}: [{strat}] {label}")
        print("-" * 60)
        fn(strat)

    print_summary()


if __name__ == "__main__":
    run_all()
