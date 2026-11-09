#!/usr/bin/env python3
"""Developer CLI for the judge: submit a solution and watch its events.

    python3 tools/judge_cli.py submit problems/examples/vector_add problems/examples/vector_add/solutions/accepted.cu --watch
    python3 tools/judge_cli.py validate problems/examples/vector_add
    python3 tools/judge_cli.py watch            # print all events (needs `pip install pika`)

A problem directory contains problem.json (ProblemSpec without generator.source) and problem.py (the generator).
`--watch` binds a temporary queue to the `judge.events` exchange, so it does not steal events from the backend.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def load_problem(path: Path) -> dict:
    spec = json.loads((path / "problem.json").read_text())
    spec["generator"] = {"language": "PYTHON", "source": (path / "problem.py").read_text()}
    return spec


def post(url: str, body: dict) -> tuple[int, dict]:
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def open_events_channel(host: str):
    try:
        import pika  # type: ignore
    except ImportError:
        sys.exit("--watch needs pika: pip install pika")
    conn = pika.BlockingConnection(pika.ConnectionParameters(host=host))
    ch = conn.channel()
    q = ch.queue_declare(queue="", exclusive=True, auto_delete=True).method.queue
    ch.queue_bind(queue=q, exchange="judge.events", routing_key="#")
    return conn, ch, q


def print_event(e: dict) -> None:
    status = e["status"]
    head = f"[{e['submissionId']} a{e['attempt']} #{e['seq']}] {status:<9} from {e['source']}"
    if e.get("progress"):
        p = e["progress"]
        print(f"{head}  test {p['currentTest']}/{p['totalTests']}")
    elif e.get("result"):
        r = e["result"]
        print(f"{head}  {r['verdict']}" + (f" on test {r['failedTest']}" if r.get("failedTest") else "")
              + (f", max time {r['maxTimeMs']:.3f} ms" if r.get("maxTimeMs") is not None else ""))
        if r.get("message"):
            print("    message:", r["message"])
        for t in r.get("tests", []):
            tm = f"{t['timeMs']:.3f} ms" if t.get("timeMs") is not None else "-"
            print(f"    test {t['index']:>3} {t['name']:<16} {t['verdict']:<22} {tm:>12}  {t.get('message') or ''}")
        if r.get("compileLog"):
            print("    compile log:\n      " + r["compileLog"].replace("\n", "\n      "))
        if r.get("gpu"):
            g = r["gpu"]
            print(f"    gpu: {g['name']} #{g['index']} on {g['executorId']}, sm clock {g.get('minSmClockMhz')}-{g.get('maxSmClockMhz')} MHz, "
                  f"max temp {g.get('maxTemperatureC')} C, throttled={g.get('throttled')}")
        print(f"    judge time {r['judgeTimeMs']} ms, toolchain: {r.get('toolchain')}")
    else:
        print(head)


def cmd_submit(args) -> int:
    problem = load_problem(Path(args.problem))
    sid = args.id or f"dev-{uuid.uuid4().hex[:12]}"
    body = {"submissionId": sid, "problem": problem, "source": Path(args.solution).read_text()}
    if args.gpu:
        body["gpuProfile"] = args.gpu
    watch = open_events_channel(args.rabbit) if args.watch else None
    code, resp = post(f"{args.gateway}/api/v1/submissions", body)
    print(code, json.dumps(resp, indent=1))
    if code >= 300 or not watch:
        return 0 if code < 300 else 1
    conn, ch, q = watch
    deadline = time.time() + args.timeout
    for method, _props, payload in ch.consume(q, inactivity_timeout=1):
        if time.time() > deadline:
            print("timeout waiting for the verdict")
            return 1
        if method is None:
            continue
        ch.basic_ack(method.delivery_tag)
        e = json.loads(payload)
        if e["submissionId"] != sid:
            continue
        print_event(e)
        if e["status"] == "FINISHED":
            conn.close()
            return 0
    return 1


def cmd_watch(args) -> int:
    conn, ch, q = open_events_channel(args.rabbit)
    for method, _props, payload in ch.consume(q):
        ch.basic_ack(method.delivery_tag)
        print_event(json.loads(payload))
    return 0


def cmd_validate(args) -> int:
    code, resp = post(f"{args.gateway}/api/v1/problems/validate", load_problem(Path(args.problem)))
    print(code, json.dumps(resp, indent=1))
    return 0 if resp.get("valid") else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--gateway", default="http://localhost:8080")
    ap.add_argument("--rabbit", default="localhost")
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("submit")
    s.add_argument("problem")
    s.add_argument("solution")
    s.add_argument("--id")
    s.add_argument("--gpu")
    s.add_argument("--watch", action="store_true")
    s.add_argument("--timeout", type=int, default=600)
    s.set_defaults(fn=cmd_submit)
    w = sub.add_parser("watch")
    w.set_defaults(fn=cmd_watch)
    v = sub.add_parser("validate")
    v.add_argument("problem")
    v.set_defaults(fn=cmd_validate)
    args = ap.parse_args()
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
