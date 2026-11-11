import unittest

from judge_problem.spec import (
    check_coverage, coverage_table, declared_processors, eval_size, parse_custom_test, parse_params, parse_test,
)

PARAMS = parse_params({"params": [
    {"name": "A", "type": "FLOAT32", "role": "INPUT", "memory": "HOST", "size": "M*K"},
    {"name": "C", "type": "FLOAT32", "role": "OUTPUT", "memory": "HOST", "size": "M"},
    {"name": "alpha", "type": "FLOAT32", "role": "SCALAR"},
    {"name": "M", "type": "INT32", "role": "SCALAR"},
    {"name": "K", "type": "INT32", "role": "SCALAR"},
]})
LIMIT = 1 << 30


class SpecTest(unittest.TestCase):
    def test_custom_tests_cannot_supply_answers_or_code(self):
        good = {"scalars": {"M": 2, "K": 1, "alpha": 1}, "inputs": {"A": [1, 2]}}
        self.assertEqual(parse_custom_test(1, good, PARAMS).sizes, {"A": 2, "C": 2})
        invalid = [
            {**good, "expected": {"C": [0, 0]}},
            {**good, "source": "print('hello')"},
            {**good, "inputs": {"A": [1]}},
            {**good, "inputs": {"A": [True, 1]}},
            {**good, "inputs": {"A": [float('inf'), 1]}},
            {**good, "inputs": {"A": [1e39, 1]}},
            {**good, "scalars": {"M": 8193, "K": 1, "alpha": 1}},
        ]
        for test in invalid:
            with self.subTest(test=test), self.assertRaises(ValueError):
                parse_custom_test(1, test, PARAMS)

    def test_eval_size_c_semantics(self):
        self.assertEqual(eval_size("(N + 255) / 256 * 256", {"N": 1000}), 1024)
        self.assertEqual(eval_size("M*K - -1", {"M": 3, "K": 4}), 13)
        self.assertEqual(eval_size("-7 / 2", {}), -3)  # truncation toward zero, like Kotlin/C
        self.assertEqual(eval_size("-7 % 2", {}), -1)
        with self.assertRaises(ValueError):
            eval_size("__import__('os')", {})
        with self.assertRaises(ValueError):
            eval_size("N ** 2", {"N": 3})

    def test_declared_test_sizes_and_defaults(self):
        t = parse_test(3, {"name": "t", "scalars": {"M": 2, "K": 3, "alpha": 1}}, PARAMS, LIMIT)
        self.assertEqual(t.sizes, {"A": 6, "C": 2})
        self.assertEqual(t["alpha"], 1.0)
        self.assertIsInstance(t["alpha"], float)
        self.assertEqual((t.index, t.seed, t.sample), (3, 3, False))  # the seed defaults to the test's index
        self.assertEqual(t.describe(), {
            "name": "t", "sample": False, "scalars": {"M": 2, "K": 3, "alpha": 1.0}, "measure": True, "processors": None,
        })

    def test_declared_test_mistakes_are_reported(self):
        ok = {"M": 2, "K": 3, "alpha": 1}
        cases = {
            "unknown keys": {"name": "t", "scalars": ok, "data": {}},
            "exactly the keys": {"name": "t", "scalars": {"M": 2}},
            "must be an integer": {"name": "t", "scalars": {**ok, "M": 2.5}},
            "name must be": {"name": "", "scalars": ok},
            "limit": {"name": "t", "scalars": {**ok, "M": 1 << 20, "K": 1 << 20}},
            "needs hand-written inputs": {"name": "t", "scalars": ok, "expected": {"C": [1, 2]}},
            "inputs must have exactly": {"name": "t", "scalars": ok, "inputs": {"B": [1]}},
        }
        for message, raw in cases.items():
            with self.subTest(message), self.assertRaisesRegex(ValueError, message):
                parse_test(1, raw, PARAMS, LIMIT)

    def test_processors_and_measure(self):
        ok = {"M": 2, "K": 3, "alpha": 1}
        declared = ["gpu-cuda", "rtl"]
        t = parse_test(1, {"name": "small", "scalars": ok, "processors": ["rtl"], "measure": False}, PARAMS, LIMIT, declared)
        self.assertEqual((t.processors, t.measure), (["rtl"], False))
        self.assertTrue(t.runs_on("rtl") and not t.runs_on("gpu-cuda"))
        cases = {
            'test 1 \'t\': unknown processor "gpu"; the problem declares gpu-cuda, rtl': {"processors": ["gpu"]},
            "must be a non-empty list": {"processors": []},
            "listed twice": {"processors": ["rtl", "rtl"]},
            '"measure" must be True or False': {"measure": 0},
        }
        for message, extra in cases.items():
            with self.subTest(message), self.assertRaisesRegex(ValueError, message):
                parse_test(1, {"name": "t", "scalars": ok, **extra}, PARAMS, LIMIT, declared)
        # without declared processors a problem is a gpu-cuda one
        with self.assertRaisesRegex(ValueError, "declares gpu-cuda$"):
            parse_test(1, {"name": "t", "scalars": ok, "processors": ["rtl"]}, PARAMS, LIMIT)

    def test_every_processor_needs_a_measured_test(self):
        ok = {"M": 2, "K": 3, "alpha": 1}
        declared = ["gpu-cuda", "rtl"]
        tests = [
            parse_test(1, {"name": "big", "scalars": ok, "processors": ["gpu-cuda"]}, PARAMS, LIMIT, declared),
            parse_test(2, {"name": "single", "scalars": ok, "measure": False}, PARAMS, LIMIT, declared),
        ]
        with self.assertRaisesRegex(ValueError, 'processor rtl has no measured test: add a test with "processors": \\["rtl"\\]'):
            check_coverage(tests, declared)
        self.assertEqual(coverage_table(tests, declared), {"gpu-cuda": {"tests": 2, "measured": 1}, "rtl": {"tests": 1, "measured": 0}})
        check_coverage(tests, ["gpu-cuda"])

    def test_declared_processors_of_spec_or_problem_json(self):
        self.assertEqual(declared_processors({}), ["gpu-cuda"])
        self.assertEqual(declared_processors({"processors": ["rtl"]}), ["rtl"])
        self.assertEqual(declared_processors({"processors": [{"id": "rtl", "languages": ["systemverilog"]}]}), ["rtl"])


if __name__ == "__main__":
    unittest.main()
