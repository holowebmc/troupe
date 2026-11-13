package dev.holoweb.troupe.lua;

import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.Varargs;
import org.squiddev.cobalt.function.LibFunction;

import java.util.function.LongBinaryOperator;

/** Lua 5.2's {@code bit32}, which Cobalt leaves out: unsigned 32-bit operations on numbers. */
final class Bit32 {
    private Bit32() {}

    private static long u32(LuaValue v) throws LuaError {
        return (long) Math.floor(v.checkDouble()) & 0xFFFFFFFFL;
    }

    private static LuaValue out(long v) {
        return ValueFactory.valueOf((double) (v & 0xFFFFFFFFL));
    }

    private static long fold(Varargs a, long start, LongBinaryOperator op) throws LuaError {
        long r = start;
        for (int i = 1; i <= a.count(); i++) r = op.applyAsLong(r, u32(a.arg(i)));
        return r;
    }

    /** A shift by {@code n} to the left (negative: right), zero filled; 32 or more clears everything. */
    private static long shift(long v, int n) {
        if (n <= -32 || n >= 32) return 0;
        return n >= 0 ? v << n : v >>> -n;
    }

    private static int field(Varargs a, int at) throws LuaError {
        int field = a.arg(at).checkInteger(), width = a.arg(at + 1).isNil() ? 1 : a.arg(at + 1).checkInteger();
        if (field < 0 || width < 1 || field + width > 32) throw new LuaError("trying to access non-existent bits");
        return width;
    }

    static LuaTable table() {
        LuaTable t = new LuaTable();
        t.rawset("band", LibFunction.createV((s, a) -> out(fold(a, 0xFFFFFFFFL, (x, y) -> x & y))));
        t.rawset("bor", LibFunction.createV((s, a) -> out(fold(a, 0, (x, y) -> x | y))));
        t.rawset("bxor", LibFunction.createV((s, a) -> out(fold(a, 0, (x, y) -> x ^ y))));
        t.rawset("btest", LibFunction.createV((s, a) -> ValueFactory.valueOf(fold(a, 0xFFFFFFFFL, (x, y) -> x & y) != 0)));
        t.rawset("bnot", LibFunction.create((s, x) -> out(~u32(x))));
        t.rawset("lshift", LibFunction.create((s, x, n) -> out(shift(u32(x), n.checkInteger()))));
        t.rawset("rshift", LibFunction.create((s, x, n) -> out(shift(u32(x), -n.checkInteger()))));
        t.rawset("arshift", LibFunction.create((s, x, n) -> {
            int by = n.checkInteger();
            if (by < 0) return out(shift(u32(x), -by));
            return out((long) ((int) u32(x) >> Math.min(by, 31)));
        }));
        t.rawset("lrotate", LibFunction.create((s, x, n) -> out(Integer.rotateLeft((int) u32(x), n.checkInteger()))));
        t.rawset("rrotate", LibFunction.create((s, x, n) -> out(Integer.rotateRight((int) u32(x), n.checkInteger()))));
        t.rawset("extract", LibFunction.createV((s, a) -> {
            int width = field(a, 2);
            return out(u32(a.arg(1)) >>> a.arg(2).checkInteger() & (1L << width) - 1);
        }));
        t.rawset("replace", LibFunction.createV((s, a) -> {
            int width = field(a, 3), at = a.arg(3).checkInteger();
            long mask = (1L << width) - 1 << at;
            return out(u32(a.arg(1)) & ~mask | u32(a.arg(2)) << at & mask);
        }));
        return t;
    }
}
