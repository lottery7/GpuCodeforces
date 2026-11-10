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
    """What generate()/reference() receive as ``test``."""

    index: int  # 1-based
    name: str
    seed: int
    sample: bool
    scalars: Dict[str, Any]
    sizes: Dict[str, int] = field(default_factory=dict)  # element count of every array param
    data: Optional[Dict[str, List[Any]]] = None
    expected: Optional[Dict[str, List[Any]]] = None  # hand-written answers of a hand-written test

    def __getitem__(self, key: str) -> Any:
        """``test["N"]`` is a shortcut for ``test.scalars["N"]``."""
        return self.scalars[key]


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


def parse_tests(tests: List[Dict[str, Any]], params: List[Param]) -> List[Test]:
    out = []
    for i, t in enumerate(tests, start=1):
        scalars = dict(t.get("scalars") or {})
        for p in params:
            if p.role == "SCALAR" and p.name in scalars:
                scalars[p.name] = float(scalars[p.name]) if p.type.startswith(("FLOAT", "BFLOAT")) else int(scalars[p.name])
        sizes = {p.name: eval_size(p.size, scalars) for p in params if p.is_array}
        out.append(Test(i, t["name"], int(t.get("seed", 0)), bool(t.get("sample", False)), scalars, sizes, t.get("data"), t.get("expected")))
    return out
