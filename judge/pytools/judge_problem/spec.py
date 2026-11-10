"""Signature/test model shared with the Kotlin side (judge-api ProblemSpec)."""

from __future__ import annotations

import ast
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

# Kotlin DType -> (torch dtype name, element bytes)
DTYPES = {
    "FLOAT16": ("float16", 2),
    "BFLOAT16": ("bfloat16", 2),
    "FLOAT32": ("float32", 4),
    "FLOAT64": ("float64", 8),
    "INT8": ("int8", 1),
    "UINT8": ("uint8", 1),
    "INT16": ("int16", 2),
    "INT32": ("int32", 4),
    "UINT32": ("uint32", 4),
    "INT64": ("int64", 8),
    "UINT64": ("uint64", 8),
}


@dataclass
class Param:
    name: str
    type: str
    role: str  # INPUT | OUTPUT | INOUT | SCALAR
    memory: Optional[str] = None
    size: Optional[str] = None

    @property
    def is_array(self) -> bool:
        return self.role != "SCALAR"

    @property
    def is_input(self) -> bool:
        return self.role in ("INPUT", "INOUT")

    @property
    def is_output(self) -> bool:
        return self.role in ("OUTPUT", "INOUT")


@dataclass
class Test:
    """A test declared by the problem's ``tests()``; what generate()/reference() receive as ``test``."""

    index: int  # 1-based
    name: str
    seed: int
    sample: bool
    scalars: Dict[str, Any]
    sizes: Dict[str, int] = field(default_factory=dict)  # element count of every array param
    inputs: Optional[Dict[str, Any]] = None  # hand-written inputs: generate() is not called for this test
    expected: Optional[Dict[str, Any]] = None  # hand-written answers of a test with hand-written inputs

    def __getitem__(self, key: str) -> Any:
        """``test["N"]`` is a shortcut for ``test.scalars["N"]``."""
        return self.scalars[key]

    def describe(self) -> Dict[str, Any]:
        """What the executor learns about the test (``tests.json``)."""
        return {"name": self.name, "sample": self.sample, "scalars": self.scalars}


_ALLOWED_BINOPS = (ast.Add, ast.Sub, ast.Mult, ast.Div, ast.FloorDiv, ast.Mod)


def eval_size(expr: str, scalars: Dict[str, Any]) -> int:
    """Evaluates a size expression with C integer semantics (division truncates toward zero)."""

    def ev(node: ast.AST) -> int:
        if isinstance(node, ast.Expression):
            return ev(node.body)
        if isinstance(node, ast.Constant) and isinstance(node.value, int) and not isinstance(node.value, bool):
            return node.value
        if isinstance(node, ast.Name):
            if node.id not in scalars:
                raise ValueError(f"unknown variable {node.id!r} in size {expr!r}")
            return int(scalars[node.id])
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
            return -ev(node.operand)
        if isinstance(node, ast.BinOp) and isinstance(node.op, _ALLOWED_BINOPS):
            a, b = ev(node.left), ev(node.right)
            if isinstance(node.op, ast.Add):
                return a + b
            if isinstance(node.op, ast.Sub):
                return a - b
            if isinstance(node.op, ast.Mult):
                return a * b
            if b == 0:
                raise ZeroDivisionError(f"division by zero in size {expr!r}")
            q = abs(a) // abs(b)
            q = q if (a >= 0) == (b >= 0) else -q
            return q if not isinstance(node.op, ast.Mod) else a - q * b
        raise ValueError(f"unsupported construct in size {expr!r}")

    return ev(ast.parse(expr, mode="eval"))


def parse_params(signature: Dict[str, Any]) -> List[Param]:
    return [Param(p["name"], p["type"], p["role"], p.get("memory"), p.get("size")) for p in signature["params"]]


TEST_KEYS = {"name", "scalars", "sample", "seed", "inputs", "expected"}
MAX_NAME_LENGTH = 128


def parse_test(index: int, raw: Any, params: List[Param], max_test_bytes: int) -> Test:
    """Checks one entry of ``tests()`` and computes its array sizes. Raises ValueError with what is wrong."""
    where = f"test {index}"
    if not isinstance(raw, dict):
        raise ValueError(f"{where}: tests() must yield dicts, got {type(raw).__name__}")
    unknown = set(raw) - TEST_KEYS
    if unknown:
        raise ValueError(f"{where}: unknown keys {sorted(unknown)}, allowed {sorted(TEST_KEYS)}")
    name = raw.get("name")
    if not isinstance(name, str) or not name.strip() or len(name) > MAX_NAME_LENGTH:
        raise ValueError(f"{where}: name must be a non-empty string of at most {MAX_NAME_LENGTH} characters")
    where = f"test {index} '{name}'"

    scalar_params = {p.name: p for p in params if p.role == "SCALAR"}
    given = raw.get("scalars") or {}
    if not isinstance(given, dict) or set(given) != set(scalar_params):
        raise ValueError(f"{where}: scalars must have exactly the keys {sorted(scalar_params)}")
    scalars: Dict[str, Any] = {}
    for n, p in scalar_params.items():
        v = given[n]
        if isinstance(v, bool) or not isinstance(v, (int, float)):
            raise ValueError(f"{where}: scalar {n} must be a number, got {v!r}")
        if p.type.startswith(("FLOAT", "BFLOAT")):
            scalars[n] = float(v)
        elif isinstance(v, int) or float(v).is_integer():
            scalars[n] = int(v)
        else:
            raise ValueError(f"{where}: scalar {n} must be an integer, got {v!r}")

    sizes = {p.name: eval_size(p.size, scalars) for p in params if p.is_array}
    total = 0
    for p in params:
        if p.is_array:
            if sizes[p.name] < 0:
                raise ValueError(f"{where}: size of {p.name} is negative ({sizes[p.name]})")
            total += sizes[p.name] * DTYPES[p.type][1]
    if total > max_test_bytes:
        raise ValueError(f"{where}: test data is {total} bytes, limit {max_test_bytes}")

    seed = raw.get("seed", index)
    if isinstance(seed, bool) or not isinstance(seed, int):
        raise ValueError(f"{where}: seed must be an integer")
    inputs, expected = raw.get("inputs"), raw.get("expected")
    if expected is not None and inputs is None:
        raise ValueError(f"{where}: expected needs hand-written inputs")
    for what, value, wanted in (
        ("inputs", inputs, {p.name for p in params if p.is_array and p.is_input}),
        ("expected", expected, {p.name for p in params if p.is_array and p.is_output}),
    ):
        if value is not None and (not isinstance(value, dict) or set(value) != wanted):
            raise ValueError(f"{where}: {what} must have exactly the keys {sorted(wanted)}")
    return Test(index, name, seed, bool(raw.get("sample", False)), scalars, sizes, inputs, expected)
