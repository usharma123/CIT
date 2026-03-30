#!/usr/bin/env python3
"""
Mocknet load test – 1000 FX_SPOT trades submitted in matched pairs.
Each pair shares counterparties, currencies, and value date so the
matching engine can pair them.  500 pairs = 1000 trades total.

Each pair gets a unique value date to prevent TradeRepository.findMatchCandidate
from returning multiple rows (IncorrectResultSizeDataAccessException).

Usage:
    python3 script/load_test.py [--url URL] [--workers N] [--trades N]
"""
import argparse
import concurrent.futures
import datetime
import statistics
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import List

BASE_URL = "http://localhost:8080/api/trades"
CURRENCIES = [
    ("USD", "EUR"), ("USD", "GBP"), ("USD", "JPY"),
    ("EUR", "GBP"), ("EUR", "CHF"), ("GBP", "CHF"),
    ("USD", "CHF"), ("EUR", "JPY"), ("GBP", "JPY"), ("AUD", "USD"),
]
PARTIES = [
    ("BANK_A", "BANK_B"), ("BANK_C", "BANK_D"), ("BANK_E", "BANK_F"),
    ("BANK_G", "BANK_H"), ("BANK_I", "BANK_J"),
]
BASE_DATE = datetime.date(2027, 1, 1)


def build_xml(trade_id: str, message_id: str,
              party1: str, party2: str,
              ccy1: str, ccy2: str,
              amount1: int, amount2: int,
              rate: float,
              value_date: str) -> bytes:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<tradeMessage>
  <header>
    <messageId>{message_id}</messageId>
    <creationTimestamp>2026-03-25T18:00:00Z</creationTimestamp>
  </header>
  <trade>
    <tradeId>{trade_id}</tradeId>
    <tradeType>FX_SPOT</tradeType>
    <party1><partyId>{party1}</partyId><role>BUYER</role></party1>
    <party2><partyId>{party2}</partyId><role>SELLER</role></party2>
    <currencyPair>
      <currency1>{ccy1}</currency1><amount1>{amount1}</amount1>
      <currency2>{ccy2}</currency2><amount2>{amount2}</amount2>
      <exchangeRate>{rate}</exchangeRate>
    </currencyPair>
    <valueDate>{value_date}</valueDate>
  </trade>
</tradeMessage>""".encode()


@dataclass
class Result:
    trade_id: str
    status: int
    latency_ms: float
    error: str = ""


def submit(trade_id: str, message_id: str,
           party1: str, party2: str,
           ccy1: str, ccy2: str,
           amount1: int, amount2: int,
           rate: float,
           value_date: str,
           url: str) -> Result:
    body = build_xml(trade_id, message_id, party1, party2,
                     ccy1, ccy2, amount1, amount2, rate, value_date)
    req = urllib.request.Request(
        url, data=body,
        headers={"Content-Type": "application/xml"},
        method="POST"
    )
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            latency = (time.perf_counter() - t0) * 1000
            return Result(trade_id, resp.status, latency)
    except urllib.error.HTTPError as e:
        latency = (time.perf_counter() - t0) * 1000
        return Result(trade_id, e.code, latency, str(e))
    except Exception as e:
        latency = (time.perf_counter() - t0) * 1000
        return Result(trade_id, 0, latency, str(e))


def build_trade_pairs(n_pairs: int):
    """
    Build n_pairs matched pairs. Each pair:
      - trade A: party1 buys ccy1, party2 sells ccy2
      - trade B: party2 buys ccy1, party1 sells ccy2 (exact mirror)
    Each pair gets a unique value date so findMatchCandidate always returns
    exactly one row and avoids IncorrectResultSizeDataAccessException.
    """
    ts = int(time.time() * 1000)
    trades = []
    for i in range(n_pairs):
        ccy1, ccy2  = CURRENCIES[i % len(CURRENCIES)]
        p1, p2      = PARTIES[i % len(PARTIES)]
        amount1     = 1_000_000 + i * 1000
        amount2     = int(amount1 * 0.9)
        rate        = round(amount2 / amount1, 6)
        # Unique value date per pair prevents non-unique match query results
        value_date  = (BASE_DATE + datetime.timedelta(days=i)).isoformat()
        pair_tag    = f"{ts}-P{i:04d}"
        # Side A
        trades.append(dict(
            trade_id=f"TRD-{pair_tag}-A",
            message_id=f"MSG-{pair_tag}-A",
            party1=p1, party2=p2,
            ccy1=ccy1, ccy2=ccy2,
            amount1=amount1, amount2=amount2, rate=rate,
            value_date=value_date,
        ))
        # Side B (mirror – same value date, exact counterparty swap)
        trades.append(dict(
            trade_id=f"TRD-{pair_tag}-B",
            message_id=f"MSG-{pair_tag}-B",
            party1=p2, party2=p1,
            ccy1=ccy1, ccy2=ccy2,
            amount1=amount1, amount2=amount2, rate=rate,
            value_date=value_date,
        ))
    return trades


def run(n_trades: int, workers: int, url: str):
    if n_trades % 2 != 0:
        n_trades += 1  # round up to even for pairs
    n_pairs = n_trades // 2
    trades = build_trade_pairs(n_pairs)

    print(f"Load test: {len(trades)} trades  |  {workers} workers  |  {url}")
    print(f"Pairs: {n_pairs}  (each pair will match in the matching engine)")
    print("-" * 60)

    results: List[Result] = []
    t_start = time.perf_counter()

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {
            pool.submit(
                submit,
                t["trade_id"], t["message_id"],
                t["party1"],   t["party2"],
                t["ccy1"],     t["ccy2"],
                t["amount1"],  t["amount2"],
                t["rate"],
                t["value_date"],
                url
            ): t["trade_id"]
            for t in trades
        }
        done = 0
        for fut in concurrent.futures.as_completed(futures):
            r = fut.result()
            results.append(r)
            done += 1
            if done % 100 == 0 or done == len(trades):
                elapsed = time.perf_counter() - t_start
                rps = done / elapsed
                print(f"  {done:4d}/{len(trades)}  elapsed={elapsed:.1f}s  rps={rps:.1f}")

    total_elapsed = time.perf_counter() - t_start

    # ── Summary ──────────────────────────────────────────────────────────────
    ok      = [r for r in results if r.status in (200, 202)]
    err     = [r for r in results if r.status not in (200, 202)]
    lats    = [r.latency_ms for r in results]
    ok_lats = [r.latency_ms for r in ok]

    print()
    print("=" * 60)
    print("LOAD TEST RESULTS")
    print("=" * 60)
    print(f"Total trades submitted : {len(results)}")
    print(f"  HTTP 200 (accepted)  : {len(ok)}")
    print(f"  Errors               : {len(err)}")
    print(f"Total wall time        : {total_elapsed:.2f}s")
    print(f"Throughput             : {len(results)/total_elapsed:.1f} req/s")
    print()
    if lats:
        print("Latency (all requests, ms):")
        print(f"  min    : {min(lats):.1f}")
        print(f"  median : {statistics.median(lats):.1f}")
        print(f"  p95    : {sorted(lats)[int(len(lats)*0.95)]:.1f}")
        print(f"  p99    : {sorted(lats)[int(len(lats)*0.99)]:.1f}")
        print(f"  max    : {max(lats):.1f}")
    if ok_lats:
        print()
        print("Latency (HTTP 200 only, ms):")
        print(f"  min    : {min(ok_lats):.1f}")
        print(f"  median : {statistics.median(ok_lats):.1f}")
        print(f"  p95    : {sorted(ok_lats)[int(len(ok_lats)*0.95)]:.1f}")
        print(f"  p99    : {sorted(ok_lats)[int(len(ok_lats)*0.99)]:.1f}")
        print(f"  max    : {max(ok_lats):.1f}")
    if err:
        print()
        print(f"Error breakdown (first 10):")
        for r in err[:10]:
            print(f"  {r.trade_id}  HTTP {r.status}  {r.error[:80]}")
    print("=" * 60)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--url",     default=BASE_URL)
    parser.add_argument("--workers", type=int, default=20)
    parser.add_argument("--trades",  type=int, default=1000)
    args = parser.parse_args()
    run(args.trades, args.workers, args.url)
