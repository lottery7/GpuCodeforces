import unittest

from judge_problem.spec import eval_size, parse_params, parse_tests


class SpecTest(unittest.TestCase):
    def test_eval_size_c_semantics(self):
        self.assertEqual(eval_size("(N + 255) / 256 * 256", {"N": 1000}), 1024)
        self.assertEqual(eval_size("M*K - -1", {"M": 3, "K": 4}), 13)
        self.assertEqual(eval_size("-7 / 2", {}), -3)  # truncation toward zero, like Kotlin/C
        self.assertEqual(eval_size("-7 % 2", {}), -1)
        with self.assertRaises(ValueError):
            eval_size("__import__('os')", {})
        with self.assertRaises(ValueError):
            eval_size("N ** 2", {"N": 3})

    def test_parse_tests_sizes(self):
        params = parse_params({"params": [
            {"name": "A", "type": "FLOAT32", "role": "INPUT", "memory": "HOST", "size": "M*K"},
            {"name": "alpha", "type": "FLOAT32", "role": "SCALAR"},
            {"name": "M", "type": "INT32", "role": "SCALAR"},
            {"name": "K", "type": "INT32", "role": "SCALAR"},
        ]})
        t = parse_tests([{"name": "t", "scalars": {"M": 2, "K": 3, "alpha": 1}, "seed": 5}], params)[0]
        self.assertEqual(t.sizes, {"A": 6})
        self.assertEqual(t["alpha"], 1.0)
        self.assertIsInstance(t["alpha"], float)
        self.assertEqual((t.index, t.seed), (1, 5))


if __name__ == "__main__":
    unittest.main()
