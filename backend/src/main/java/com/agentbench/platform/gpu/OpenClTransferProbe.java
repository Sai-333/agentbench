package com.agentbench.platform.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.math.BigDecimal;
import java.nio.file.Path;

/**
 * OpenCL GPU transfer. CPU devices and software renderer names are skipped.
 * The score is blocking write plus read MiB/s. A missing ICD is not a score.
 */
final class OpenClTransferProbe implements GpuApiProbe {

    private static final int CL_SUCCESS = 0;
    private static final int CL_DEVICE_NOT_FOUND = -1;
    private static final long CL_DEVICE_TYPE_GPU = 4L;
    private static final int CL_DEVICE_NAME = 0x102B;
    private static final int CL_DEVICE_TYPE = 0x1000;
    private static final long CL_MEM_READ_WRITE = 1L;

    @Override
    public String api() {
        return "OpenCL";
    }

    @Override
    public GpuApiAttempt attempt() {
        if (!NativeLibrary.supportedAbi()) {
            return GpuApiAttempt.failed(api(), null, "unsupported_abi");
        }
        Path library = GpuLibraries.find("libOpenCL.so.1", "libOpenCL.so", "libOpenCL.dylib", "OpenCL.dll");
        if (library == null) {
            return GpuApiAttempt.absent(api());
        }
        try (NativeLibrary nativeLibrary = NativeLibrary.open(library)) {
            return measure(nativeLibrary);
        } catch (IllegalCallerException ex) {
            return GpuApiAttempt.failed(api(), null, "native_access_denied");
        } catch (RuntimeException ex) {
            return GpuApiAttempt.failed(api(), null, "opencl_error");
        }
    }

    private GpuApiAttempt measure(NativeLibrary nativeLibrary) {
        Arena arena = nativeLibrary.arena();
        MethodHandle getPlatforms = nativeLibrary.downcall(
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                "clGetPlatformIDs");
        MethodHandle getDevices = nativeLibrary.downcall(
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS),
                "clGetDeviceIDs");
        MethodHandle getInfo = nativeLibrary.downcall(
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS),
                "clGetDeviceInfo");
        MethodHandle createContext = nativeLibrary.downcall(
                FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS),
                "clCreateContext");
        MethodHandle createQueue = nativeLibrary.downcall(
                FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS),
                "clCreateCommandQueue");
        MethodHandle createQueueProps = nativeLibrary.downcall(
                FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS),
                "clCreateCommandQueueWithProperties");
        MethodHandle createBuffer = nativeLibrary.downcall(
                FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS),
                "clCreateBuffer");
        MethodHandle enqueueWrite = nativeLibrary.downcall(enqueueDescriptor(), "clEnqueueWriteBuffer");
        MethodHandle enqueueRead = nativeLibrary.downcall(enqueueDescriptor(), "clEnqueueReadBuffer");
        MethodHandle finish = nativeLibrary.downcall(
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), "clFinish");
        MethodHandle releaseMem = nativeLibrary.downcall(
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), "clReleaseMemObject");
        MethodHandle releaseQueue = nativeLibrary.downcall(
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), "clReleaseCommandQueue");
        MethodHandle releaseContext = nativeLibrary.downcall(
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), "clReleaseContext");
        if (getPlatforms == null || getDevices == null || getInfo == null || createContext == null
                || createBuffer == null || enqueueWrite == null || enqueueRead == null || finish == null
                || releaseMem == null || releaseQueue == null || releaseContext == null
                || (createQueue == null && createQueueProps == null)) {
            return GpuApiAttempt.failed(api(), null, "missing_symbol");
        }
        MemorySegment countSlot = arena.allocate(ValueLayout.JAVA_INT);
        int platformRc = NativeLibrary.invoke(getPlatforms, 0, MemorySegment.NULL, countSlot);
        int platformCount = countSlot.get(ValueLayout.JAVA_INT, 0);
        if (platformRc != CL_SUCCESS || platformCount <= 0) {
            return GpuApiAttempt.noDevice(api());
        }
        int fetchPlatforms = Math.min(platformCount, 8);
        MemorySegment platforms = arena.allocateArray(ValueLayout.ADDRESS, fetchPlatforms);
        if (NativeLibrary.invoke(getPlatforms, fetchPlatforms, platforms, countSlot) != CL_SUCCESS) {
            return GpuApiAttempt.failed(api(), null, "opencl_platforms");
        }
        String seenDevice = null;
        boolean sawGpu = false;
        for (int p = 0; p < fetchPlatforms; p++) {
            MemorySegment platform = platforms.getAtIndex(ValueLayout.ADDRESS, p);
            int deviceRc = NativeLibrary.invoke(
                    getDevices, platform, CL_DEVICE_TYPE_GPU, 0, MemorySegment.NULL, countSlot);
            int deviceCount = countSlot.get(ValueLayout.JAVA_INT, 0);
            if (deviceRc == CL_DEVICE_NOT_FOUND || deviceCount <= 0) {
                continue;
            }
            if (deviceRc != CL_SUCCESS) {
                continue;
            }
            int fetchDevices = Math.min(deviceCount, 8);
            MemorySegment devices = arena.allocateArray(ValueLayout.ADDRESS, fetchDevices);
            if (NativeLibrary.invoke(getDevices, platform, CL_DEVICE_TYPE_GPU, fetchDevices, devices, countSlot) != CL_SUCCESS) {
                continue;
            }
            for (int d = 0; d < fetchDevices; d++) {
                MemorySegment device = devices.getAtIndex(ValueLayout.ADDRESS, d);
                if (!isGpu(getInfo, device, arena)) {
                    continue;
                }
                String name = deviceName(getInfo, device, arena);
                if (name == null || GpuText.software(name)) {
                    continue;
                }
                sawGpu = true;
                seenDevice = name;
                GpuApiAttempt measured = transfer(
                        nativeLibrary, createContext, createQueue, createQueueProps, createBuffer,
                        enqueueWrite, enqueueRead, finish, releaseMem, releaseQueue, releaseContext,
                        device, name);
                if (measured.kind() == GpuApiAttempt.Kind.MEASURED) {
                    return measured;
                }
            }
        }
        if (!sawGpu) {
            return GpuApiAttempt.noDevice(api());
        }
        return GpuApiAttempt.failed(api(), seenDevice, "opencl_copy");
    }

    private GpuApiAttempt transfer(
            NativeLibrary nativeLibrary,
            MethodHandle createContext,
            MethodHandle createQueue,
            MethodHandle createQueueProps,
            MethodHandle createBuffer,
            MethodHandle enqueueWrite,
            MethodHandle enqueueRead,
            MethodHandle finish,
            MethodHandle releaseMem,
            MethodHandle releaseQueue,
            MethodHandle releaseContext,
            MemorySegment device,
            String deviceName) {
        Arena arena = nativeLibrary.arena();
        MemorySegment deviceSlot = arena.allocate(ValueLayout.ADDRESS);
        deviceSlot.set(ValueLayout.ADDRESS, 0, device);
        MemorySegment err = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment context = MemorySegment.NULL;
        MemorySegment queue = MemorySegment.NULL;
        MemorySegment buffer = MemorySegment.NULL;
        try {
            context = (MemorySegment) createContext.invoke(
                    MemorySegment.NULL, 1, deviceSlot, MemorySegment.NULL, MemorySegment.NULL, err);
            if (err.get(ValueLayout.JAVA_INT, 0) != CL_SUCCESS || context == null || context.equals(MemorySegment.NULL)) {
                return GpuApiAttempt.failed(api(), deviceName, "opencl_context");
            }
            queue = openQueue(createQueue, createQueueProps, context, device, err);
            if (queue == null || queue.equals(MemorySegment.NULL) || err.get(ValueLayout.JAVA_INT, 0) != CL_SUCCESS) {
                return GpuApiAttempt.failed(api(), deviceName, "opencl_queue");
            }
            buffer = (MemorySegment) createBuffer.invoke(
                    context, CL_MEM_READ_WRITE, (long) GpuScores.BUFFER_BYTES, MemorySegment.NULL, err);
            if (err.get(ValueLayout.JAVA_INT, 0) != CL_SUCCESS || buffer == null || buffer.equals(MemorySegment.NULL)) {
                return GpuApiAttempt.failed(api(), deviceName, "opencl_buffer");
            }
            MemorySegment host = arena.allocate(GpuScores.BUFFER_BYTES);
            host.fill((byte) 0x5A);
            if (!copy(enqueueWrite, enqueueRead, finish, queue, buffer, host)) {
                return GpuApiAttempt.failed(api(), deviceName, "opencl_copy");
            }
            for (int i = 0; i < GpuScores.WARMUP_ITERS; i++) {
                if (!copy(enqueueWrite, enqueueRead, finish, queue, buffer, host)) {
                    return GpuApiAttempt.failed(api(), deviceName, "opencl_copy");
                }
            }
            long start = System.nanoTime();
            for (int i = 0; i < GpuScores.TIMED_ITERS; i++) {
                if (!copy(enqueueWrite, enqueueRead, finish, queue, buffer, host)) {
                    return GpuApiAttempt.failed(api(), deviceName, "opencl_copy");
                }
            }
            BigDecimal score = GpuScores.mibPerSec(System.nanoTime() - start);
            if (score == null) {
                return GpuApiAttempt.failed(api(), deviceName, "opencl_timing");
            }
            return GpuApiAttempt.measured(api(), deviceName, score);
        } catch (Throwable ex) {
            return GpuApiAttempt.failed(api(), deviceName, "opencl_error");
        } finally {
            if (buffer != null && !buffer.equals(MemorySegment.NULL)) {
                NativeLibrary.invoke(releaseMem, buffer);
            }
            if (queue != null && !queue.equals(MemorySegment.NULL)) {
                NativeLibrary.invoke(releaseQueue, queue);
            }
            if (context != null && !context.equals(MemorySegment.NULL)) {
                NativeLibrary.invoke(releaseContext, context);
            }
        }
    }

    private static MemorySegment openQueue(
            MethodHandle createQueue,
            MethodHandle createQueueProps,
            MemorySegment context,
            MemorySegment device,
            MemorySegment err) throws Throwable {
        if (createQueue != null) {
            MemorySegment queue = (MemorySegment) createQueue.invoke(context, device, 0L, err);
            if (err.get(ValueLayout.JAVA_INT, 0) == CL_SUCCESS && queue != null && !queue.equals(MemorySegment.NULL)) {
                return queue;
            }
        }
        if (createQueueProps == null) {
            return MemorySegment.NULL;
        }
        err.set(ValueLayout.JAVA_INT, 0, 0);
        return (MemorySegment) createQueueProps.invoke(context, device, MemorySegment.NULL, err);
    }

    private static boolean copy(
            MethodHandle write,
            MethodHandle read,
            MethodHandle finish,
            MemorySegment queue,
            MemorySegment buffer,
            MemorySegment host) {
        int writeRc = NativeLibrary.invoke(
                write, queue, buffer, 1, 0L, (long) GpuScores.BUFFER_BYTES, host, 0, MemorySegment.NULL, MemorySegment.NULL);
        int readRc = NativeLibrary.invoke(
                read, queue, buffer, 1, 0L, (long) GpuScores.BUFFER_BYTES, host, 0, MemorySegment.NULL, MemorySegment.NULL);
        int done = NativeLibrary.invoke(finish, queue);
        return writeRc == CL_SUCCESS && readRc == CL_SUCCESS && done == CL_SUCCESS;
    }

    private static boolean isGpu(MethodHandle getInfo, MemorySegment device, Arena arena) {
        MemorySegment type = arena.allocate(ValueLayout.JAVA_LONG);
        int rc = NativeLibrary.invoke(getInfo, device, CL_DEVICE_TYPE, 8L, type, MemorySegment.NULL);
        if (rc != CL_SUCCESS) {
            return false;
        }
        return (type.get(ValueLayout.JAVA_LONG, 0) & CL_DEVICE_TYPE_GPU) != 0;
    }

    private static String deviceName(MethodHandle getInfo, MemorySegment device, Arena arena) {
        MemorySegment name = arena.allocate(256);
        int rc = NativeLibrary.invoke(getInfo, device, CL_DEVICE_NAME, 255L, name, MemorySegment.NULL);
        if (rc != CL_SUCCESS) {
            return null;
        }
        return GpuText.device(name.getUtf8String(0));
    }

    private static FunctionDescriptor enqueueDescriptor() {
        return FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS);
    }
}
