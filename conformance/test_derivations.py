"""Independent arithmetic cross-checks for authored answers; no engine, DSL reader or recording."""
from decimal import Decimal, localcontext, ROUND_HALF_UP, ROUND_HALF_EVEN, ROUND_HALF_DOWN, ROUND_FLOOR, ROUND_CEILING, ROUND_DOWN, ROUND_UP
from fractions import Fraction
import json
from pathlib import Path
import unittest


def value(vector, query):
    data = json.loads((Path(__file__).parent / "vectors" / vector / "expected.json").read_text())
    return Decimal(data["values"][query]["n"])


class DerivationTest(unittest.TestCase):
    def test_rounded_bonus_first_qualifying_next_and_exact_closed_form(self):
        exact = Fraction(100000, 11)
        self.assertEqual(Fraction(100000), exact * 11)
        with localcontext() as context:
            context.prec = 80
            current = Decimal(0)
            history = []
            for _ in range(50):
                following = (Decimal(".1") * (Decimal(100000) - current)).quantize(Decimal(".01"), rounding=ROUND_HALF_UP)
                history.append(following)
                if abs(following - current) <= Decimal(".01"):
                    break
                current = following
            else:
                self.fail("Independent rounded recurrence did not converge")
            self.assertEqual([Decimal(x) for x in ("10000", "9000", "9100", "9090", "9091", "9090.90", "9090.91")], history)
            self.assertEqual(following, value("16-rounded-convergence", "bonus"))
            self.assertEqual(Decimal(100000) - following, value("16-rounded-convergence", "remaining"))

    def test_signed_modes_and_weighted_fraction(self):
        modes = {"half-up": ROUND_HALF_UP, "half-even": ROUND_HALF_EVEN, "half-down": ROUND_HALF_DOWN,
                 "floor": ROUND_FLOOR, "ceiling": ROUND_CEILING, "down": ROUND_DOWN, "up": ROUND_UP}
        for name, mode in modes.items():
            self.assertEqual(Decimal("-1.25").quantize(Decimal(".1"), rounding=mode), value("13-signed-rounding", f"rounded-{name}"))
        exact = Fraction(10 + 40, 100 + 200)
        self.assertEqual(Fraction(1, 6), exact)
        with localcontext() as context:
            context.prec = 80
            rounded = (Decimal(exact.numerator) / Decimal(exact.denominator)).quantize(Decimal(".000001"), rounding=ROUND_HALF_UP)
            self.assertEqual(rounded, value("23-weighted-ratio", "weighted-rate@*"))
            self.assertEqual((Decimal(1) / 3).quantize(Decimal(".001"), rounding=ROUND_HALF_UP), value("13-signed-rounding", "third"))
        # A finite base-ten denominator may contain only factors 2 and 5; 1/3 does not.
        self.assertEqual(3, Fraction(1, 3).denominator)

    def test_two_series_boundaries_are_selected_before_summing(self):
        starts = {"A": 100, "B": 40}
        changes = {"A": [5, 7, -2], "B": [3, -1, 4]}
        closes = {key: starts[key] + sum(changes[key]) for key in starts}
        self.assertEqual(Decimal(sum(starts.values())), value("11-two-axis-stocks", "opening@*"))
        self.assertEqual(Decimal(sum(closes.values())), value("11-two-axis-stocks", "closing@*"))
        for key in starts:
            self.assertEqual(Decimal(closes[key]), value("11-two-axis-stocks", f"closing@{key}/P3"))
        self.assertNotEqual(sum(closes.values()), sum(starts.values()) + sum(sum(changes[key][:index + 1]) for key in starts for index in range(3)))

    def test_tolerance_boundary_and_two_cycle_do_not_imply_convergence(self):
        self.assertEqual(Decimal(".005"), abs(Decimal("1.005") - Decimal(1)))
        self.assertEqual(Decimal(".005"), value("15-business-boundary", "match-value"))
        current = Fraction(0)
        values = []
        for _ in range(4):
            following = 1 - current
            self.assertGreater(abs(following - current), 0)
            values.append(following)
            current = following
        self.assertEqual([1, 0, 1, 0], values)
        expected = json.loads((Path(__file__).parent / "vectors/17-two-cycle-failure/expected.json").read_text())
        self.assertIsNone(expected["values"]["oscillating"])
        self.assertFalse(expected["succeeded"])


if __name__ == "__main__":
    unittest.main()
