package com.agentbench.platform.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.math.BigDecimal;
import java.nio.file.Path;

/**
 * CUDA driver-API transfer. The score is host-to-device plus device-to-host
 * MiB/s for {@link GpuScores#BUFFER_BYTES}. No score is returned when the
 * driver or a device is missing.
 */
final class CudaTransferProbe implements GpuApiProbe {

    private static final int CUDA_ERROR_NO_DEVICE = 100;

    @Override
    public String api() {
        return "CUDA";
    }

    @Override
    public GpuApiAttempt attempt() {
        if (!NativeLibrary.supportedAbi()) {
            return GpuApiAttempt.failed(api(), null, "unsupported_abi");
        }
        Path library = GpuLibraries.find("libcuda.so.1", "libcuda.so", "libcuda.dylib", "nvcuda.dll");
        if (library == null) {
            return GpuApiAttempt.absent(api());
        }
        try (NativeLibrary nativeLibrary = NativeLibrary.open(library)) {
            return measure(nativeLibrary);
        } catch (IllegalCallerException ex) {
            return GpuApiAttempt.failed(api(), null, "native_access_denied");
        } catch (RuntimeException ex) {
            return GpuApiAttempt.failed(api(), null, "cuda_error");
        }
    }

    private GpuApiAttempt measure(NativeLibrary nativeLibrary) {
        Arena arena = nativeLibrary.arena();
        MethodHandle cuInit = nativeLibrary.downcall(
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), "cuInit");
        MethodHandle cuDeviceGetCount = nativeLibrary.downcall(
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), "cuDeviceGetCount");
        MethodHandle cuDeviceGet = nativeLibrary.downcall(
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), "cuDeviceGet");
        MethodHandle cuDeviceGetName = nativeLibrary.downcall(
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                "cuDeviceGetName");
        MethodHandle cuCtxCreate = nativeLibrary.downcall(
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                "cuCtxCreate_v2", "cuCtxCreate");
        MethodHandle cuCtxDestroy = nativeLibrary.downcall(
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                "cuCtxDestroy_v2", "cuCtxDestroy");
        MethodHandle cuMemAlloc = nativeLibrary.downcall(
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                "cuMemAlloc_v2", "cuMemAlloc");
        MethodHandle cuMemFree = nativeLibrary.downcall(
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
                "cuMemFree_v2", "cuMemFree");
        MethodHandle cuMemcpyHtoD = nativeLibrary.downcall(
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                "cuMemcpyHtoD_v2", "cuMemcpyHtoD");
        MethodHandle cuMemcpyDtoH = nativeLibrary.downcall(
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                "cuMemcpyDtoH_v2", "cuMemcpyDtoH");
        if (cuInit == null || cuDeviceGetCount == null || cuDeviceGet == null || cuDeviceGetName == null
                || cuCtxCreate == null || cuCtxDestroy == null || cuMemAlloc == null || cuMemFree == null
                || cuMemcpyHtoD == null || cuMemcpyDtoH == null) {
            return GpuApiAttempt.failed(api(), null, "missing_symbol");
        }
        int init = NativeLibrary.invoke(cuInit, 0);
        if (init == CUDA_ERROR_NO_DEVICE) {
            return GpuApiAttempt.noDevice(api());
        }
        if (init != 0) {
            return GpuApiAttempt.failed(api(), null, "cuda_init_" + init);
        }
        MemorySegment countSlot = arena.allocate(ValueLayout.JAVA_INT);
        int countRc = NativeLibrary.invoke(cuDeviceGetCount, countSlot);
        int count = countSlot.get(ValueLayout.JAVA_INT, 0);
        if (countRc != 0 || count <= 0) {
            return countRc == CUDA_ERROR_NO_DEVICE || count == 0
                    ? GpuApiAttempt.noDevice(api())
                    : GpuApiAttempt.failed(api(), null, "cuda_count_" + countRc);
        }
        MemorySegment deviceSlot = arena.allocate(ValueLayout.JAVA_INT);
        if (NativeLibrary.invoke(cuDeviceGet, deviceSlot, 0) != 0) {
            return GpuApiAttempt.failed(api(), null, "cuda_device");
        }
        int deviceOrdinal = deviceSlot.get(ValueLayout.JAVA_INT, 0);
        MemorySegment nameBuf = arena.allocate(256);
        if (NativeLibrary.invoke(cuDeviceGetName, nameBuf, 255, deviceOrdinal) != 0) {
            return GpuApiAttempt.failed(api(), null, "cuda_name");
        }
        String deviceName = GpuText.device(nameBuf.getUtf8String(0));
        if (deviceName == null || GpuText.software(deviceName)) {
            return GpuApiAttempt.noDevice(api());
        }
        MemorySegment contextSlot = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment pointerSlot = arena.allocate(ValueLayout.JAVA_LONG);
        MemorySegment host = arena.allocate(GpuScores.BUFFER_BYTES);
        host.fill((byte) 0x5A);
        long devicePointer = 0L;
        boolean contextOpen = false;
        try {
            if (NativeLibrary.invoke(cuCtxCreate, contextSlot, 0, deviceOrdinal) != 0) {
                return GpuApiAttempt.failed(api(), deviceName, "cuda_context");
            }
            contextOpen = true;
            if (NativeLibrary.invoke(cuMemAlloc, pointerSlot, (long) GpuScores.BUFFER_BYTES) != 0) {
                return GpuApiAttempt.failed(api(), deviceName, "cuda_alloc");
            }
            devicePointer = pointerSlot.get(ValueLayout.JAVA_LONG, 0);
            if (!copy(cuMemcpyHtoD, cuMemcpyDtoH, devicePointer, host)) {
                return GpuApiAttempt.failed(api(), deviceName, "cuda_copy");
            }
            for (int i = 0; i < GpuScores.WARMUP_ITERS; i++) {
                if (!copy(cuMemcpyHtoD, cuMemcpyDtoH, devicePointer, host)) {
                    return GpuApiAttempt.failed(api(), deviceName, "cuda_copy");
                }
            }
            long start = System.nanoTime();
            for (int i = 0; i < GpuScores.TIMED_ITERS; i++) {
                if (!copy(cuMemcpyHtoD, cuMemcpyDtoH, devicePointer, host)) {
                    return GpuApiAttempt.failed(api(), deviceName, "cuda_copy");
                }
            }
            long nanos = System.nanoTime() - start;
            BigDecimal score = GpuScores.mibPerSec(nanos);
            if (score == null) {
                return GpuApiAttempt.failed(api(), deviceName, "cuda_timing");
            }
            return GpuApiAttempt.measured(api(), deviceName, score);
        } finally {
            if (devicePointer != 0L && cuMemFree != null) {
                NativeLibrary.invoke(cuMemFree, devicePointer);
            }
            if (contextOpen) {
                MemorySegment context = contextSlot.get(ValueLayout.ADDRESS, 0);
                NativeLibrary.invoke(cuCtxDestroy, context);
            }
        }
    }

    private static boolean copy(MethodHandle htoD, MethodHandle dtoH, long devicePointer, MemorySegment host) {
        if (NativeLibrary.invoke(htoD, devicePointer, host, (long) GpuScores.BUFFER_BYTES) != 0) {
            return false;
        }
        return NativeLibrary.invoke(dtoH, host, devicePointer, (long) GpuScores.BUFFER_BYTES) == 0;
    }
}
