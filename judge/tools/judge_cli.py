#!/usr/bin/env python3
"""Developer CLI for the judge: submit a solution and watch its events.

    python3 tools/judge_cli.py submit problems/examples/vector_add problems/examples/vector_add/solutions/accepted.cu --watch
    python3 tools/judge_cli.py submit PROBLEM SOLUTION --processor rtl --language systemverilog
    python3 tools/judge_cli.py validate problems/examples/vector_add
    python3 tools/judge_cli.py watch            # print all events (needs `pip install pika`)

A problem directory contains problem.json (ProblemSpec without generator.source) and problem.py (the generator).
`--processor` may be left out when the problem declares one processor, `--language` when the processor allows one
language there or the solution's extension names it. `--watch` binds a temporary queue to the `judge.events`
exchange, so it does not steal events from the backend; the verdict line shows the score to pick a problem's limit.
`validate` also lists, per processor, how many tests run on it and how many of them are measured: that needs the
generator to be importable here (torch installed).
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


PYTOOLS = Path(__file__).resolve().parent.parent / "pytools"

# Extensions of the languages in judge-api Processors.
EXTENSIONS = {".cu": "cuda", ".sv": "systemverilog", ".c": "c", ".cpp": "cpp", ".S": "asm"}


def declared_processors(problem: dict) -> list[dict]:
    """What the gateway judges a problem on: its processors, or gpu-cuda alone."""
    return problem.get("processors") or [{"id": "gpu-cuda", "languages": ["cuda"]}]


def pick_target(problem: dict, solution: Path, processor: str | None, language: str | None) -> tuple[str, str]:
    declared = declared_processors(problem)
    if processor is None:
        if len(declared) != 1:
            sys.exit(f"--processor is needed: the problem declares {', '.join(p['id'] for p in declared)}")
        processor = declared[0]["id"]
    if language is None:
        languages = next((p["languages"] for p in declared if p["id"] == processor), [])
        by_extension = next((lang for ext, lang in EXTENSIONS.items() if solution.name.endswith(ext)), None)
        if len(languages) == 1:
            language = languages[0]
        elif by_extension in languages:
            language = by_extension
        else:
            sys.exit(f"--language is needed: {processor} allows {', '.join(languages) or 'nothing'} in this problem")
    return processor, language


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


def fmt_score(score: dict | None) -> str:
    if not score:
        return "-"
    unit = {"TIME_MS": "ms", "CYCLES": "cycles", "CYCLES_X_DEPTH_X_TRANSISTORS": "cycles x depth x transistors", "WEIGHTED_INSTRUCTIONS": "weighted instructions"}
    return f"{score['value']:.3f} {unit.get(score['type'], score['type'])}"


def print_event(e: dict) -> None:
    status = e["status"]
    head = f"[{e['submissionId']} a{e['attempt']} #{e['seq']}] {status:<9} from {e['source']}"
    if e.get("progress"):
        p = e["progress"]
        print(f"{head}  test {p['currentTest']}/{p['totalTests']}")
    elif e.get("result"):
        r = e["result"]
        print(f"{head}  {r['verdict']}" + (f" on test {r['failedTest']}" if r.get("failedTest") else "")
              + (f", score {fmt_score(r['score'])}" if r.get("score") else ""))
        if r.get("metrics"):
            print("    metrics:", ", ".join(f"{k} {v:g}" for k, v in r["metrics"].items()))
        if r.get("message"):
            print("    message:", r["message"])
        for t in r.get("tests", []):
            measured = "" if t.get("measured", True) else " (not measured)"
            print(f"    test {t['index']:>3} {t['name']:<16} {t['verdict']:<22} {fmt_score(t.get('score')):>20}{measured}  {t.get('message') or ''}")
        if r.get("compileLog"):
            print("    compile log:\n      " + r["compileLog"].replace("\n", "\n      "))
        if r.get("gpu"):
            g = r["gpu"]
            print(f"    gpu: {g['name']} #{g['index']} on {g['executorId']}, sm clock {g.get('minSmClockMhz')}-{g.get('maxSmClockMhz')} MHz, "
                  f"max temp {g.get('maxTemperatureC')} C, throttled={g.get('throttled')}")
        p = r.get("processor") or {}
        print(f"    judge time {r['judgeTimeMs']} ms on {p.get('id', '-')} ({p.get('model', '-')}) of {p.get('executorId', '-')}, "
              f"versions: {p.get('version', '-')}")
    else:
        print(head)


def cmd_submit(args) -> int:
    problem = load_problem(Path(args.problem))
    solution = Path(args.solution)
    processor, language = pick_target(problem, solution, args.processor, args.language)
    sid = args.id or f"dev-{uuid.uuid4().hex[:12]}"
    body = {"submissionId": sid, "problem": problem, "source": solution.read_text(), "processor": processor, "language": language}
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


def count_tests(path: Path, problem: dict) -> bool:
    """Runs the generator's tests() here and prints, per processor, its tests and measured tests."""
    sys.path.insert(0, str(PYTOOLS))
    from judge_problem.spec import check_coverage, coverage_table, parse_params, parse_test  # noqa: E402

    try:
        spec = importlib.util.spec_from_file_location("judge_problem_module", path / "problem.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    except ImportError as e:
        print(f"tests not counted: problem.py cannot be imported here ({e})")
        return True
    processors = [p["id"] for p in declared_processors(problem)]
    params = parse_params(problem["signature"])
    try:
        tests = [parse_test(i, raw, params, 4 << 30, processors) for i, raw in enumerate(module.tests(), start=1)]
        table = coverage_table(tests, processors)
        for p, row in table.items():
            print(f"{p}: {row['tests']} tests, {row['measured']} measured")
        check_coverage(tests, processors)
    except ValueError as e:
        print("error:", e)
        return False
    return True


def cmd_validate(args) -> int:
    path = Path(args.problem)
    problem = load_problem(path)
    code, resp = post(f"{args.gateway}/api/v1/problems/validate", problem)
    print(code, json.dumps(resp, indent=1))
    tests_ok = count_tests(path, problem)
    return 0 if resp.get("valid") and tests_ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--gateway", default="http://localhost:8090")
    ap.add_argument("--rabbit", default="localhost")
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("submit")
    s.add_argument("problem")
    s.add_argument("solution")
    s.add_argument("--id")
    s.add_argument("--processor", help="processor id, e.g. gpu-cuda or rtl")
    s.add_argument("--language", help="language id, e.g. cuda or systemverilog")
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
