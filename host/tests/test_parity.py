"""Host half of the cross-language codec parity test.

Checks host/vdi_tunnel/{protocol,fountain}.py against vectors/parity-vectors.txt.
The bridge half (ParityTest.kt) checks Protocol.kt / Fountain.kt against the very
same file, so a codec change that lands in only one language fails here or there.

Run:  python -m unittest discover -s host/tests   (stdlib only, no deps)
"""
import os
import sys
import unittest

_HOST = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_ROOT = os.path.dirname(_HOST)
sys.path.insert(0, _HOST)

from vdi_tunnel import fountain, protocol  # noqa: E402

VECTORS = os.path.join(_ROOT, "vectors", "parity-vectors.txt")


def unhex(s: str) -> bytes:
    return b"" if s == "-" else bytes.fromhex(s)


def load_records():
    """Yield (name, args, expected) for every non-comment line."""
    with open(VECTORS, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            lhs, _, rhs = line.partition(" => ")
            assert rhs, f"{VECTORS}:{lineno}: no ' => ' in {line!r}"
            name, *args = lhs.split()
            yield name, args, rhs.split()


class ParityVectors(unittest.TestCase):
    """Every record in the vector file must reproduce from the host code."""

    @classmethod
    def setUpClass(cls):
        cls.records = list(load_records())
        assert cls.records, f"no vectors found in {VECTORS}"

    def _records(self, name):
        return [(a, e) for n, a, e in self.records if n == name]

    def test_neighbors(self):
        rows = self._records("NEIGHBORS")
        self.assertGreaterEqual(len(rows), 9)   # the nine published in PROTOCOL.md
        for args, exp in rows:
            sid, k, dmax = (int(a) for a in args)
            with self.subTest(symbol_id=sid, k=k, dmax=dmax):
                want = [int(x) for x in exp[0].split(",")]
                self.assertEqual(fountain._neighbors(sid, k, dmax), want)

    def test_encode_symbol(self):
        for args, exp in self._records("SYMBOL"):
            payload, sid, ssize, dmax = unhex(args[0]), int(args[1]), int(args[2]), int(args[3])
            with self.subTest(symbol_id=sid, symbol_size=ssize):
                got = fountain.encode_symbol(payload, sid, ssize, dmax)
                self.assertEqual(got.hex(), unhex(exp[0]).hex())

    def test_pack_resp(self):
        for args, exp in self._records("RESP"):
            gen, sid, k, plen, codec = (int(a) for a in args[:5])
            sha, symbol = int(args[5], 16), unhex(args[6])
            with self.subTest(gen_id=gen, symbol_id=sid):
                frame = protocol.pack_resp(gen, sid, k, plen, codec, sha, symbol)
                self.assertEqual(frame.hex(), exp[0])
                # and the frame must read back as what went in
                got = protocol.unpack_resp(frame)
                self.assertEqual(
                    (got["gen_id"], got["symbol_id"], got["k"], got["payload_len"],
                     got["codec"], got["sha"], got["symbol"]),
                    (gen, sid, k, plen, codec, sha, symbol))

    def test_unpack_heartbeat(self):
        for args, exp in self._records("HEARTBEAT"):
            gen, state, bver = int(args[0]), int(args[1]), int(args[3])
            rx, schema = int(args[2], 16), int(args[4], 16)
            with self.subTest(gen_id=gen, state=state):
                got = protocol.unpack_heartbeat(unhex(exp[0]))
                self.assertEqual(got, dict(gen_id=gen, state=state, rx_hash=rx,
                                           bridge_ver=bver, schema_hash=schema))

    def test_pack_req(self):
        for args, exp in self._records("REQ"):
            gen, seq, total, codec = (int(a) for a in args[:4])
            chunk = unhex(args[4])
            with self.subTest(gen_id=gen, seq=seq):
                line = protocol.req_to_line(protocol.pack_req(gen, seq, total, codec, chunk))
                self.assertEqual(line, exp[0])

    def test_rolling_ack(self):
        for args, exp in self._records("ACK"):
            prev, chunk = int(args[0], 16), unhex(args[1])
            with self.subTest(prev=args[0], chunk=args[1]):
                self.assertEqual(f"{protocol.rolling_ack(prev, chunk):08x}", exp[0])

    def test_sha_tail(self):
        for args, exp in self._records("SHATAIL"):
            with self.subTest(payload=args[0][:16]):
                self.assertEqual(f"{protocol.sha_tail(unhex(args[0])):08x}", exp[0])

    def test_compress(self):
        for args, exp in self._records("ZLIB"):
            raw = unhex(args[0])
            with self.subTest(raw=args[0][:16]):
                codec, data = protocol.compress(raw)
                self.assertEqual(codec, int(exp[0]))
                self.assertEqual(data.hex(), unhex(exp[1]).hex())
                self.assertEqual(protocol.decompress(codec, data), raw)


class RoundTrip(unittest.TestCase):
    """Properties the vector file can't express: the codec used as a pipeline."""

    def test_fountain_decodes_from_systematic_symbols(self):
        payload = bytes((i * 31 + 7) % 256 for i in range(5000))
        k = (len(payload) + 511) // 512
        dec = fountain.Decoder(k, len(payload), 512, 8)
        for sid in range(k):
            done = dec.add(sid, fountain.encode_symbol(payload, sid, 512, 8))
        self.assertTrue(done)
        self.assertEqual(dec.result(), payload)

    def test_fountain_decodes_with_losses_from_repair_symbols(self):
        """Drop every third systematic symbol; repair symbols must fill the holes."""
        payload = bytes((i * 131 + 17) % 256 for i in range(5000))
        k = (len(payload) + 511) // 512
        dec = fountain.Decoder(k, len(payload), 512, 8)
        done = False
        for sid in range(k):
            if sid % 3 == 2:
                continue
            done = dec.add(sid, fountain.encode_symbol(payload, sid, 512, 8))
        self.assertFalse(done, "test is vacuous if the systematic subset already decodes")
        for sid in range(k, k * 8):
            done = dec.add(sid, fountain.encode_symbol(payload, sid, 512, 8))
            if done:
                break
        self.assertTrue(done, "repair symbols failed to close the gaps")
        self.assertEqual(dec.result(), payload)

    def test_payload_survives_compress_and_frame(self):
        raw = b'{"jsonrpc":"2.0","id":9,"result":{"content":[{"text":"' + b"x" * 4000 + b'"}]}}'
        codec, payload = protocol.compress(raw)
        k = (len(payload) + 511) // 512
        sha = protocol.sha_tail(payload)
        dec = fountain.Decoder(k, len(payload), 512, 8)
        for sid in range(k):
            frame = protocol.pack_resp(1, sid, k, len(payload), codec, sha,
                                       fountain.encode_symbol(payload, sid, 512, 8))
            f = protocol.unpack_resp(frame)
            dec.add(f["symbol_id"], f["symbol"])
        out = dec.result()
        self.assertEqual(protocol.sha_tail(out), sha)
        self.assertEqual(protocol.decompress(codec, out), raw)

    def test_corrupt_frame_is_rejected(self):
        frame = bytearray(protocol.pack_resp(1, 0, 1, 4, 0, 0, b"abcd"))
        frame[21] ^= 0x01
        with self.assertRaises(ValueError):
            protocol.unpack_resp(bytes(frame))


if __name__ == "__main__":
    unittest.main()
