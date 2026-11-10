"""Problem preparation tooling for the GPU Codeforces judge.

Problem authors write a Python module with two functions (see docs/problem-format.md):

    def generate(test, gen, device) -> dict[str, torch.Tensor]   # inputs of a generated test
    def reference(test, inputs) -> dict[str, torch.Tensor]        # expected outputs

The executor runs ``python -m judge_problem.prepare`` once per problem version and caches the result.
"""

from .spec import DTYPES, Param, Test, eval_size  # noqa: F401
