"""Generates the tests of one problem version: the list from the module's tests(), their inputs and reference outputs.

    python -m judge_problem.prepare --spec spec.json --source problem.py --out DIR [--processor ID]

spec.json is {"signature": ..., "processors": [...], "limits": {"maxTests": ..., "maxTestBytes": ...}}; processors
(ids, or the ProcessorSpec objects of problem.json) default to gpu-cuda, limits are optional. problem.json itself
will do. With --processor only the tests that run on that processor are generated, numbered from 1 in order;
every test is still checked, and every processor of the problem must have a measured test.
Writes raw little-endian arrays:
    DIR/inputs/t<i>/<param>.bin     INPUT and INOUT params   (mounted read-only into the sandbox)
    DIR/expected/t<i>/<param>.bin   OUTPUT and INOUT params  (only the checker reads these)
    DIR/tests.json                  [{name, sample, scalars, measure, processors}] in order, what the judge needs
    DIR/meta.json                   timings and versions, informational
Exit code 0 on success; on failure a traceback is printed to stderr and the exit code is 2.
"""

from __future__ import annotations

import argparse
import contextlib
import importlib.util
import json
import os
import random
import sys
import time
import traceback
from pathlib import Path
from typing import Dict

from .spec import DTYPES, Param, Test, check_coverage, declared_processors, parse_params, parse_test

# Must be set before CUDA is initialised for deterministic cuBLAS.
os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")

import torch  # noqa: E402


class ProblemError(Exception):
    """Mistake in the problem module (wrong keys, shapes, dtypes)."""


def torch_dtype(type_name: str) -> torch.dtype:
    return getattr(torch, DTYPES[type_name][0])


@contextlib.contextmanager
def reference_context():
    """Strict IEEE fp32 and deterministic algorithms, so reference outputs do not depend on the machine."""
    saved = (
        torch.backends.cuda.matmul.allow_tf32,
        torch.backends.cudnn.allow_tf32,
        torch.backends.cudnn.deterministic,
        torch.backends.cudnn.benchmark,
    )
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    torch.backends.cudnn.deterministic = True
    torch.backends.cudnn.benchmark = False
    torch.use_deterministic_algorithms(True, warn_only=True)
    try:
        with torch.inference_mode():
            yield
    finally:
        (
            torch.backends.cuda.matmul.allow_tf32,
            torch.backends.cudnn.allow_tf32,
            torch.backends.cudnn.deterministic,
            torch.backends.cudnn.benchmark,
        ) = saved


def load_module(path: Path):
    spec = importlib.util.spec_from_file_location("judge_problem_module", path)
    if spec is None or spec.loader is None:
        raise ProblemError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    for fn in ("tests", "reference"):
        if not callable(getattr(module, fn, None)):
            raise ProblemError(f"problem module must define {fn}(...)")
    return module


def check_arrays(what: str, test: Test, arrays: Dict[str, torch.Tensor], params: list[Param]) -> Dict[str, torch.Tensor]:
    if not isinstance(arrays, dict):
        raise ProblemError(f"{what} must return a dict, got {type(arrays).__name__}")
    expected = {p.name for p in params}
    if set(arrays) != expected:
        raise ProblemError(f"{what} for test {test.index} '{test.name}' must return keys {sorted(expected)}, got {sorted(arrays)}")
    out = {}
    for p in params:
        t = arrays[p.name]
        if not isinstance(t, torch.Tensor):
            raise ProblemError(f"{what}: {p.name} must be a torch.Tensor")
        want = torch_dtype(p.type)
        if t.dtype != want:
            raise ProblemError(f"{what}: {p.name} has dtype {t.dtype}, signature says {want}")
        if t.numel() != test.sizes[p.name]:
            raise ProblemError(f"{what}: {p.name} has {t.numel()} elements, size expression gives {test.sizes[p.name]}")
        out[p.name] = t.detach().reshape(-1).contiguous()
    return out


def as_tensors(what: str, test: Test, arrays: Dict[str, object], params: list[Param], device: str) -> Dict[str, torch.Tensor]:
    """Hand-written arrays of a test: tensors are taken as they are (check_arrays checks them), lists are converted."""
    out = {}
    for p in params:
        v = arrays[p.name]
        out[p.name] = v.to(device) if isinstance(v, torch.Tensor) else torch.tensor(v, dtype=torch_dtype(p.type), device=device)
    return check_arrays(what, test, out, params)


def declared_tests(module, params: list[Param], max_tests: int, max_test_bytes: int, processors: list[str]):
    """Yields the module's tests one by one, so tests() may build hand-written tensors lazily."""
    # tests() runs once per executor, and two executors must agree on the list: no unseeded randomness
    random.seed(0)
    torch.manual_seed(0)
    names = set()
    for index, raw in enumerate(module.tests(), start=1):
        if index > max_tests:
            raise ProblemError(f"tests() declares more than {max_tests} tests")
        try:
            test = parse_test(index, raw, params, max_test_bytes, processors)
        except (ValueError, ZeroDivisionError) as e:
            raise ProblemError(str(e)) from None
        if test.name in names:
            raise ProblemError(f"test {index}: duplicate test name '{test.name}'")
        names.add(test.name)
        yield test
    if not names:
        raise ProblemError("tests() declares no tests")


def write_array(path: Path, t: torch.Tensor) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = t.cpu().contiguous().view(torch.uint8).numpy()
    data.tofile(path)
    return int(data.nbytes)


def prepare(spec: dict, source: Path, out: Path, device: str, processor: str | None = None) -> dict:
    params = parse_params(spec["signature"])
    processors = declared_processors(spec)
    if processor is not None and processor not in processors:
        raise ProblemError(f"processor {processor} is not one the problem declares: {', '.join(processors)}")
    limits = spec.get("limits") or {}
    inputs_p = [p for p in params if p.is_array and p.is_input]
    outputs_p = [p for p in params if p.is_array and p.is_output]
    module = load_module(source)
    generate = getattr(module, "generate", None)

    meta = {"tests": [], "torch": torch.__version__, "device": device, "cuda": torch.version.cuda, "processor": processor}
    declared = []
    seen: list[Test] = []
    started = time.monotonic()
    with reference_context():
        tests = declared_tests(
            module, params, int(limits.get("maxTests", 200)), int(limits.get("maxTestBytes", 4 << 30)), processors,
        )
        for test in tests:
            seen.append(test)
            if processor is not None and not test.runs_on(processor):
                test.inputs = test.expected = None
                continue
            # numbered among the generated tests: that is how the judge finds their files
            n = len(declared) + 1
            t0 = time.monotonic()
            if test.inputs is not None:
                inputs = as_tensors("inputs", test, test.inputs, inputs_p, device)
            else:
                if not callable(generate):
                    raise ProblemError(f"test {test.index} '{test.name}' has no hand-written inputs and the module defines no generate()")
                gen = torch.Generator(device=device)
                gen.manual_seed(test.seed)
                inputs = check_arrays("generate", test, generate(test, gen, device), inputs_p)

            entry = {"index": n, "declared": test.index, "name": test.name, "inputs": {}, "expected": {}}
            for p in inputs_p:
                entry["inputs"][p.name] = write_array(out / "inputs" / f"t{n}" / f"{p.name}.bin", inputs[p.name])

            if test.expected is not None:
                # the answer was written by hand (an example from the statement): the reference has no say
                ref_out = as_tensors("expected", test, test.expected, outputs_p, device)
            else:
                # The reference gets copies: it must not be able to change the stored inputs.
                ref_out = module.reference(test, {k: v.clone() for k, v in inputs.items()})
                ref_out = check_arrays("reference", test, ref_out, outputs_p)
            for p in outputs_p:
                entry["expected"][p.name] = write_array(out / "expected" / f"t{n}" / f"{p.name}.bin", ref_out[p.name])
            entry["seconds"] = round(time.monotonic() - t0, 3)
            meta["tests"].append(entry)
            declared.append(test.describe())
            print(f"test {test.index} '{test.name}' prepared in {entry['seconds']}s", file=sys.stderr, flush=True)
            del inputs, ref_out
            test.inputs = test.expected = None
    try:
        check_coverage(seen, processors)
    except ValueError as e:
        raise ProblemError(str(e)) from None
    meta["seconds"] = round(time.monotonic() - started, 3)
    (out / "tests.json").write_text(json.dumps(declared, indent=1))
    (out / "meta.json").write_text(json.dumps(meta, indent=1))
    return meta


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--spec", required=True, type=Path)
    ap.add_argument("--source", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--device", default="auto", help="cuda, cpu or auto")
    ap.add_argument("--processor", help="generate only the tests of this processor")
    args = ap.parse_args(argv)
    device = args.device
    if device == "auto":
        device = "cuda" if torch.cuda.is_available() else "cpu"
    try:
        spec = json.loads(args.spec.read_text())
        args.out.mkdir(parents=True, exist_ok=True)
        prepare(spec, args.source, args.out, device, args.processor)
        return 0
    except ProblemError as e:
        print(f"problem error: {e}", file=sys.stderr)
        return 2
    except Exception:  # noqa: BLE001 - everything goes to the executor log
        traceback.print_exc()
        return 2


if __name__ == "__main__":
    sys.exit(main())
