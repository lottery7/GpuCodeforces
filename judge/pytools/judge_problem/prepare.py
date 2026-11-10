"""Generates test inputs and reference outputs for one problem version.

    python -m judge_problem.prepare --spec spec.json --source problem.py --out DIR

Writes raw little-endian arrays:
    DIR/inputs/t<i>/<param>.bin     INPUT and INOUT params   (mounted read-only into the sandbox)
    DIR/expected/t<i>/<param>.bin   OUTPUT and INOUT params  (only the checker reads these)
    DIR/meta.json
Exit code 0 on success; on failure a traceback is printed to stderr and the exit code is 2.
"""

from __future__ import annotations

import argparse
import contextlib
import importlib.util
import json
import os
import sys
import time
import traceback
from pathlib import Path
from typing import Dict

from .spec import DTYPES, Param, Test, parse_params, parse_tests

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
    for fn in ("reference",):
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


def write_array(path: Path, t: torch.Tensor) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = t.cpu().contiguous().view(torch.uint8).numpy()
    data.tofile(path)
    return int(data.nbytes)


def prepare(spec: dict, source: Path, out: Path, device: str) -> dict:
    params = parse_params(spec["signature"])
    tests = parse_tests(spec["tests"], params)
    inputs_p = [p for p in params if p.is_array and p.is_input]
    outputs_p = [p for p in params if p.is_array and p.is_output]
    module = load_module(source)
    generate = getattr(module, "generate", None)

    meta = {"tests": [], "torch": torch.__version__, "device": device, "cuda": torch.version.cuda}
    started = time.monotonic()
    with reference_context():
        for test in tests:
            t0 = time.monotonic()
            if test.data is not None:
                inputs = {p.name: torch.tensor(test.data[p.name], dtype=torch_dtype(p.type), device=device) for p in inputs_p}
            else:
                if not callable(generate):
                    raise ProblemError(f"test {test.index} '{test.name}' has no inline data and the module defines no generate()")
                gen = torch.Generator(device=device)
                gen.manual_seed(test.seed)
                inputs = generate(test, gen, device)
            inputs = check_arrays("generate", test, inputs, inputs_p)

            entry = {"index": test.index, "name": test.name, "inputs": {}, "expected": {}}
            for p in inputs_p:
                entry["inputs"][p.name] = write_array(out / "inputs" / f"t{test.index}" / f"{p.name}.bin", inputs[p.name])

            if test.expected is not None:
                # the answer was written by hand (an example from the statement): the reference has no say
                ref_out = {p.name: torch.tensor(test.expected[p.name], dtype=torch_dtype(p.type), device=device) for p in outputs_p}
                ref_out = check_arrays("expected", test, ref_out, outputs_p)
            else:
                # The reference gets copies: it must not be able to change the stored inputs.
                ref_out = module.reference(test, {k: v.clone() for k, v in inputs.items()})
                ref_out = check_arrays("reference", test, ref_out, outputs_p)
            for p in outputs_p:
                entry["expected"][p.name] = write_array(out / "expected" / f"t{test.index}" / f"{p.name}.bin", ref_out[p.name])
            entry["seconds"] = round(time.monotonic() - t0, 3)
            meta["tests"].append(entry)
            print(f"test {test.index} '{test.name}' prepared in {entry['seconds']}s", file=sys.stderr, flush=True)
            del inputs, ref_out
    meta["seconds"] = round(time.monotonic() - started, 3)
    (out / "meta.json").write_text(json.dumps(meta, indent=1))
    return meta


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--spec", required=True, type=Path)
    ap.add_argument("--source", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--device", default="auto", help="cuda, cpu or auto")
    args = ap.parse_args(argv)
    device = args.device
    if device == "auto":
        device = "cuda" if torch.cuda.is_available() else "cpu"
    try:
        spec = json.loads(args.spec.read_text())
        args.out.mkdir(parents=True, exist_ok=True)
        prepare(spec, args.source, args.out, device)
        return 0
    except ProblemError as e:
        print(f"problem error: {e}", file=sys.stderr)
        return 2
    except Exception:  # noqa: BLE001 - everything goes to the executor log
        traceback.print_exc()
        return 2


if __name__ == "__main__":
    sys.exit(main())
