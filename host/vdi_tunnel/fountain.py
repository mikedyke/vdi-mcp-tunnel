"""Self-contained LT fountain. Encoder + decoder MUST match Fountain.kt bit-for-bit.

Systematic symbols (id < K) carry source blocks verbatim; repair symbols (id >= K)
XOR a seed-derived subset. Decoder peels (belief propagation)."""
MASK64 = (1 << 64) - 1
GOLDEN = 0x9E3779B97F4A7C15

def _lcg(x): return (x * 6364136223846793005 + 1442695040888963407) & MASK64

def _neighbors(symbol_id: int, k: int, dmax: int):
    """Return the sorted list of source indices this symbol XORs together.
    Identical logic required on both sides."""
    if symbol_id < k:
        return [symbol_id]
    if k <= 1:
        return [0]
    state = _lcg(symbol_id ^ GOLDEN)
    hi = min(k, dmax)
    d = 2 + int((state >> 33) % (hi - 1)) if hi > 2 else 2
    d = min(d, k)
    ns = set()
    while len(ns) < d:
        state = _lcg(state)
        ns.add(int((state >> 33) % k))
    return sorted(ns)

def _xor(a: bytes, b: bytes) -> bytes:
    return bytes(x ^ y for x, y in zip(a, b))

def encode_symbol(payload: bytes, symbol_id: int, symbol_size: int, dmax: int) -> bytes:
    """Used by tests / reference; the bridge is the real encoder."""
    k = (len(payload) + symbol_size - 1) // symbol_size
    blocks = [payload[i*symbol_size:(i+1)*symbol_size].ljust(symbol_size, b"\0")
              for i in range(k)]
    out = bytes(symbol_size)
    for idx in _neighbors(symbol_id, k, dmax):
        out = _xor(out, blocks[idx])
    return out

class Decoder:
    def __init__(self, k: int, payload_len: int, symbol_size: int, dmax: int):
        self.k, self.payload_len, self.symbol_size, self.dmax = k, payload_len, symbol_size, dmax
        self.solved = [None] * k
        self.n_solved = 0
        self.pending = []          # list of [set_of_unknown_indices, data]
        self.seen = set()          # symbol_ids already consumed

    def add(self, symbol_id: int, data: bytes) -> bool:
        if symbol_id in self.seen or self.n_solved >= self.k:
            return self.n_solved >= self.k
        self.seen.add(symbol_id)
        idxs = set(_neighbors(symbol_id, self.k, self.dmax))
        d = bytes(data[:self.symbol_size].ljust(self.symbol_size, b"\0"))
        # reduce by already-solved indices
        for i in list(idxs):
            if self.solved[i] is not None:
                d = _xor(d, self.solved[i]); idxs.discard(i)
        self._absorb(idxs, d)
        self._peel()
        return self.n_solved >= self.k

    def _absorb(self, idxs, d):
        if len(idxs) == 1:
            (i,) = tuple(idxs)
            if self.solved[i] is None:
                self.solved[i] = d; self.n_solved += 1
        elif len(idxs) > 1:
            self.pending.append([idxs, d])

    def _peel(self):
        changed = True
        while changed:
            changed = False
            for eq in self.pending:
                idxs, d = eq
                for i in list(idxs):
                    if self.solved[i] is not None:
                        d = _xor(d, self.solved[i]); idxs.discard(i)
                eq[1] = d
                if len(idxs) == 1:
                    (i,) = tuple(idxs)
                    if self.solved[i] is None:
                        self.solved[i] = d; self.n_solved += 1; changed = True
            self.pending = [e for e in self.pending if len(e[0]) > 1]

    def result(self) -> bytes:
        if self.n_solved < self.k:
            raise RuntimeError("incomplete")
        return b"".join(self.solved)[:self.payload_len]
