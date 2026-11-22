package com.agentbench.platform.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

/**
 * One loaded native library for the duration of a probe attempt.
 * Callers must not put the library path or the process environment on a stored row.
 */
final class NativeLibrary implements AutoCloseable {

    private final Arena arena;
    private final SymbolLookup lookup;
    private final Linker linker;

    private NativeLibrary(Arena arena, SymbolLookup lookup, Linker linker) {
        this.arena = arena;
        this.lookup = lookup;
        this.linker = linker;
    }

    static NativeLibrary open(Path path) {
        Arena arena = Arena.ofConfined();
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup(path, arena);
            return new NativeLibrary(arena, lookup, Linker.nativeLinker());
        } catch (Throwable ex) {
            arena.close();
            throw ex;
        }
    }

    Arena arena() {
        return arena;
    }

    java.lang.invoke.MethodHandle downcall(FunctionDescriptor descriptor, String... names) {
        for (String name : names) {
            MemorySegment symbol = lookup.find(name).orElse(null);
            if (symbol != null) {
                return linker.downcallHandle(symbol, descriptor);
            }
        }
        return null;
    }

    static int invoke(java.lang.invoke.MethodHandle handle, Object... args) {
        try {
            Object value = handle.invokeWithArguments(args);
            if (value == null) {
                return 0;
            }
            if (value instanceof Integer integer) {
                return integer;
            }
            if (value instanceof Long number) {
                return number.intValue();
            }
            throw new IllegalStateException("bad_return");
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IllegalStateException("native_call");
        }
    }

    @Override
    public void close() {
        arena.close();
    }

    static boolean supportedAbi() {
        return ValueLayout.ADDRESS.byteSize() == 8;
    }
}
