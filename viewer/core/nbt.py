"""Minimaler NBT-Leser und -Schreiber.

Leser: Compounds -> dict, Listen -> list, Long-/Int-/Byte-Arrays -> numpy-Arrays
(das ist der teure Teil eines Chunks, daher ohne Python-Schleife).

Schreiber: nur fuer Tests und den Schematic-Export. Typen werden ueber die
Wrapper-Klassen unten festgelegt, weil Python-int mehrdeutig ist.
"""

from __future__ import annotations

import struct

import numpy as np

TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG, TAG_FLOAT, TAG_DOUBLE = range(7)
TAG_BYTE_ARRAY, TAG_STRING, TAG_LIST, TAG_COMPOUND, TAG_INT_ARRAY, TAG_LONG_ARRAY = range(7, 13)

_SCALAR = {
    TAG_BYTE: (">b", 1), TAG_SHORT: (">h", 2), TAG_INT: (">i", 4),
    TAG_LONG: (">q", 8), TAG_FLOAT: (">f", 4), TAG_DOUBLE: (">d", 8),
}


class _Reader:
    __slots__ = ("b", "typed")

    def __init__(self, buf: bytes, typed: bool = False):
        self.b = buf
        self.typed = typed

    def string(self, o: int):
        (n,) = struct.unpack_from(">H", self.b, o)
        o += 2
        return self.b[o:o + n].decode("utf-8", "replace"), o + n

    def value(self, tag: int, o: int):
        b = self.b
        sc = _SCALAR.get(tag)
        if sc is not None:
            v = struct.unpack_from(sc[0], b, o)[0]
            return (_WRAP[tag](v) if self.typed else v), o + sc[1]
        if tag == TAG_STRING:
            return self.string(o)
        if tag == TAG_COMPOUND:
            d = {}
            while True:
                t = b[o]
                o += 1
                if t == TAG_END:
                    return d, o
                name, o = self.string(o)
                d[name], o = self.value(t, o)
        if tag == TAG_LIST:
            et = b[o]
            (n,) = struct.unpack_from(">i", b, o + 1)
            o += 5
            out = []
            for _ in range(n):
                v, o = self.value(et, o)
                out.append(v)
            return out, o
        if tag == TAG_BYTE_ARRAY:
            (n,) = struct.unpack_from(">i", b, o)
            if self.typed:
                return ByteArray(b[o + 4:o + 4 + n]), o + 4 + n
            return np.frombuffer(b, dtype=np.int8, count=n, offset=o + 4), o + 4 + n
        if tag == TAG_INT_ARRAY:
            (n,) = struct.unpack_from(">i", b, o)
            arr = np.frombuffer(b, dtype=">i4", count=n, offset=o + 4)
            return (IntArray(arr.tolist()) if self.typed else arr), o + 4 + 4 * n
        if tag == TAG_LONG_ARRAY:
            (n,) = struct.unpack_from(">i", b, o)
            arr = np.frombuffer(b, dtype=">u8", count=n, offset=o + 4).astype(np.uint64)
            return (LongArray(arr.tolist()) if self.typed else arr), o + 4 + 8 * n
        raise ValueError(f"unbekannter NBT-Tag {tag} bei Offset {o}")


def read(buf: bytes, typed: bool = False) -> dict:
    """Liest einen unkomprimierten NBT-Datensatz mit Compound als Wurzel.

    ``typed=True`` liefert die Wrapper-Typen unten, damit sich Daten
    verlustfrei wieder schreiben lassen (Block-Entities im Schematic-Export).
    """
    if not buf or buf[0] != TAG_COMPOUND:
        raise ValueError("NBT-Wurzel ist kein Compound")
    r = _Reader(buf, typed)
    _, o = r.string(1)
    root, _ = r.value(TAG_COMPOUND, o)
    return root


# --------------------------------------------------------------------------- Schreiben

class Byte(int): tag = TAG_BYTE
class Short(int): tag = TAG_SHORT
class Int(int): tag = TAG_INT
class Long(int): tag = TAG_LONG
class Float(float): tag = TAG_FLOAT
class Double(float): tag = TAG_DOUBLE


class ByteArray(bytes): tag = TAG_BYTE_ARRAY


class IntArray(list): tag = TAG_INT_ARRAY


class LongArray(list): tag = TAG_LONG_ARRAY


def _tag_of(v) -> int:
    t = getattr(v, "tag", None)
    if t is not None:
        return t
    if isinstance(v, bool):
        return TAG_BYTE
    if isinstance(v, int):
        # ungetypte Zahlen (z. B. LootTableSeed aus Kisten) passen nicht immer in 32 Bit
        return TAG_INT if -2**31 <= v < 2**31 else TAG_LONG
    if isinstance(v, float):
        return TAG_DOUBLE
    if isinstance(v, str):
        return TAG_STRING
    if isinstance(v, dict):
        return TAG_COMPOUND
    if isinstance(v, list):
        return TAG_LIST
    if isinstance(v, (bytes, bytearray)):
        return TAG_BYTE_ARRAY
    raise TypeError(f"kein NBT-Typ fuer {type(v)}")


_WRAP = {TAG_BYTE: Byte, TAG_SHORT: Short, TAG_INT: Int, TAG_LONG: Long, TAG_FLOAT: Float, TAG_DOUBLE: Double}


def _w_str(out: bytearray, s: str):
    e = s.encode("utf-8")
    out += struct.pack(">H", len(e))
    out += e


def _w_val(out: bytearray, tag: int, v):
    sc = _SCALAR.get(tag)
    if sc is not None:
        out += struct.pack(sc[0], int(v) if tag not in (TAG_FLOAT, TAG_DOUBLE) else v)
    elif tag == TAG_STRING:
        _w_str(out, v)
    elif tag == TAG_COMPOUND:
        for k, x in v.items():
            t = _tag_of(x)
            out.append(t)
            _w_str(out, k)
            _w_val(out, t, x)
        out.append(TAG_END)
    elif tag == TAG_LIST:
        et = _tag_of(v[0]) if v else TAG_END
        out.append(et)
        out += struct.pack(">i", len(v))
        for x in v:
            _w_val(out, et, x)
    elif tag == TAG_BYTE_ARRAY:
        out += struct.pack(">i", len(v))
        out += bytes(v)
    elif tag == TAG_INT_ARRAY:
        out += struct.pack(f">i{len(v)}i", len(v), *v)
    elif tag == TAG_LONG_ARRAY:
        out += struct.pack(">i", len(v))
        arr = v if isinstance(v, np.ndarray) else np.array([int(x) & 0xFFFFFFFFFFFFFFFF for x in v], dtype=np.uint64)
        out += arr.astype(np.uint64).astype(">u8").tobytes()
    else:
        raise TypeError(tag)


def write(root: dict, name: str = "") -> bytes:
    out = bytearray([TAG_COMPOUND])
    _w_str(out, name)
    _w_val(out, TAG_COMPOUND, root)
    return bytes(out)
