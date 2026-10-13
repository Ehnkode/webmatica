import struct
import unittest

import numpy as np

from viewer.core import nbt


def _first_tag(buf: bytes) -> int:
    """Tag id of the first entry in an unnamed root compound."""
    # root: TAG_Compound, name length 0 (2 bytes), then the first child's tag id
    return buf[3]


class NbtRoundTripTest(unittest.TestCase):
    def test_typed_scalars_round_trip(self):
        root = {
            "b": nbt.Byte(-5), "s": nbt.Short(-300), "i": nbt.Int(123456),
            "l": nbt.Long(-2**40), "f": nbt.Float(1.5), "d": nbt.Double(-0.25), "str": "Grüße",
        }
        back = nbt.read(nbt.write(root))
        self.assertEqual(back, {"b": -5, "s": -300, "i": 123456, "l": -2**40, "f": 1.5, "d": -0.25, "str": "Grüße"})

    def test_typed_read_preserves_tag_types(self):
        root = {"b": nbt.Byte(1), "s": nbt.Short(2), "i": nbt.Int(3), "l": nbt.Long(4),
                "f": nbt.Float(0.5), "d": nbt.Double(0.5)}
        back = nbt.read(nbt.write(root), typed=True)
        for k, cls in (("b", nbt.Byte), ("s", nbt.Short), ("i", nbt.Int), ("l", nbt.Long),
                       ("f", nbt.Float), ("d", nbt.Double)):
            self.assertIs(type(back[k]), cls, k)

    def test_typed_rewrite_is_byte_identical(self):
        # Block entities are copied this way into exported schematics; nothing may change.
        root = {
            "Items": [{"Slot": nbt.Byte(0), "id": "minecraft:stone", "count": nbt.Int(64)}],
            "ba": nbt.ByteArray(b"\x00\x01\xff"), "ia": nbt.IntArray([1, -2, 3]),
            "la": nbt.LongArray([0, -1, 2**62]), "empty": [], "nested": {"x": {"y": nbt.Short(7)}},
        }
        raw = nbt.write(root, "named")
        self.assertEqual(nbt.write(nbt.read(raw, typed=True), "named"), raw)

    def test_untyped_arrays_are_numpy(self):
        root = {"ba": nbt.ByteArray(b"\x01\x02"), "ia": nbt.IntArray([5, -6]), "la": nbt.LongArray([7, 8])}
        back = nbt.read(nbt.write(root))
        self.assertEqual(back["ba"].tolist(), [1, 2])
        self.assertEqual(back["ia"].tolist(), [5, -6])
        self.assertEqual(back["la"].dtype, np.uint64)
        self.assertEqual(back["la"].tolist(), [7, 8])

    def test_long_array_negative_values_keep_their_bits(self):
        back = nbt.read(nbt.write({"la": nbt.LongArray([-1, -2**63])}))
        self.assertEqual(back["la"].tolist(), [2**64 - 1, 2**63])


class NbtIntLongTest(unittest.TestCase):
    """Untyped Python ints must pick TAG_Int or TAG_Long by range (e.g. chest LootTableSeed)."""

    def test_small_int_is_tag_int(self):
        for v in (0, 2**31 - 1, -2**31):
            raw = nbt.write({"v": v})
            self.assertEqual(_first_tag(raw), nbt.TAG_INT, v)
            self.assertEqual(nbt.read(raw)["v"], v)

    def test_int_outside_32_bit_is_tag_long(self):
        for v in (2**31, -2**31 - 1, 2**63 - 1, -2**63, -8031296426196815232):
            raw = nbt.write({"v": v})
            self.assertEqual(_first_tag(raw), nbt.TAG_LONG, v)
            self.assertEqual(nbt.read(raw)["v"], v)

    def test_bool_is_tag_byte(self):
        raw = nbt.write({"v": True})
        self.assertEqual(_first_tag(raw), nbt.TAG_BYTE)
        self.assertEqual(nbt.read(raw)["v"], 1)

    def test_list_of_untyped_ints_with_large_value(self):
        # Regression: the list type used to come from the first element only, so
        # [1, 2**40] became a TAG_Int list and writing the second value failed.
        self.assertEqual(nbt.read(nbt.write({"v": [1, 2**40]}))["v"], [1, 2**40])


class NbtErrorTest(unittest.TestCase):
    def test_root_must_be_compound(self):
        with self.assertRaises(ValueError):
            nbt.read(b"")
        with self.assertRaises(ValueError):
            nbt.read(bytes([nbt.TAG_LIST]) + struct.pack(">H", 0))

    def test_unknown_python_type_is_rejected(self):
        with self.assertRaises(TypeError):
            nbt.write({"v": object()})


if __name__ == "__main__":
    unittest.main()
