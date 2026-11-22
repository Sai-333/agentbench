package com.agentbench.platform.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.math.BigDecimal;
import java.nio.file.Path;

/**
 * Vulkan 1.0 device copy. Integrated and discrete devices are eligible.
 * CPU and virtual device types are not scored. The timed work is a device
 * copy of {@link GpuScores#BUFFER_BYTES} each way.
 */
final class VulkanTransferProbe implements GpuApiProbe {

    @Override
    public String api() {
        return "Vulkan";
    }

    @Override
    public GpuApiAttempt attempt() {
        if (!NativeLibrary.supportedAbi()) {
            return GpuApiAttempt.failed(api(), null, "unsupported_abi");
        }
        Path library = GpuLibraries.find("libvulkan.so.1", "libvulkan.so", "libvulkan.dylib", "vulkan-1.dll");
        if (library == null) {
            return GpuApiAttempt.absent(api());
        }
        try (NativeLibrary nativeLibrary = NativeLibrary.open(library)) {
            return measure(nativeLibrary);
        } catch (IllegalCallerException ex) {
            return GpuApiAttempt.failed(api(), null, "native_access_denied");
        } catch (RuntimeException ex) {
            return GpuApiAttempt.failed(api(), null, "vulkan_error");
        }
    }

    private GpuApiAttempt measure(NativeLibrary nativeLibrary) {
        Procs procs = Procs.load(nativeLibrary);
        if (procs == null) {
            return GpuApiAttempt.failed(api(), null, "missing_symbol");
        }
        Arena arena = nativeLibrary.arena();
        Handles handles = new Handles();
        String deviceName = null;
        try {
            if (!createInstance(procs, arena, handles)) {
                return GpuApiAttempt.failed(api(), null, "vulkan_instance");
            }
            Chosen chosen = pickDevice(procs, arena, handles.instance);
            if (chosen == null) {
                return GpuApiAttempt.failed(api(), null, "vulkan_devices");
            }
            if (chosen.physical == null) {
                return GpuApiAttempt.noDevice(api());
            }
            deviceName = chosen.name;
            handles.physicalForMemory = chosen.physical;
            int family = queueFamily(procs, arena, chosen.physical);
            if (family < 0) {
                return GpuApiAttempt.failed(api(), deviceName, "vulkan_queue");
            }
            if (!createDevice(procs, arena, handles, chosen.physical, family)) {
                return GpuApiAttempt.failed(api(), deviceName, "vulkan_device");
            }
            MemorySegment queueSlot = arena.allocate(ValueLayout.ADDRESS);
            NativeLibrary.invoke(procs.getQueue, handles.device, family, 0, queueSlot);
            MemorySegment queue = queueSlot.get(ValueLayout.ADDRESS, 0);
            if (!createBuffer(procs, arena, handles, true) || !createBuffer(procs, arena, handles, false)) {
                return GpuApiAttempt.failed(api(), deviceName, "vulkan_buffer");
            }
            if (!createCommands(procs, arena, handles, family)) {
                return GpuApiAttempt.failed(api(), deviceName, "vulkan_commands");
            }
            if (!recordFill(procs, arena, handles) || !recordCopy(procs, arena, handles)) {
                return GpuApiAttempt.failed(api(), deviceName, "vulkan_record");
            }
            if (!createFence(procs, arena, handles)) {
                return GpuApiAttempt.failed(api(), deviceName, "vulkan_fence");
            }
            if (!prepareSubmit(arena, handles)) {
                return GpuApiAttempt.failed(api(), deviceName, "vulkan_submit");
            }
            MemorySegment fenceSlot = arena.allocate(ValueLayout.ADDRESS);
            fenceSlot.set(ValueLayout.ADDRESS, 0, handles.fence);
            if (submit(procs, handles, queue, handles.fillCommand, fenceSlot) != 0) {
                return GpuApiAttempt.failed(api(), deviceName, "vulkan_submit");
            }
            for (int i = 0; i < GpuScores.WARMUP_ITERS; i++) {
                if (submit(procs, handles, queue, handles.copyCommand, fenceSlot) != 0) {
                    return GpuApiAttempt.failed(api(), deviceName, "vulkan_submit");
                }
            }
            long start = System.nanoTime();
            for (int i = 0; i < GpuScores.TIMED_ITERS; i++) {
                if (submit(procs, handles, queue, handles.copyCommand, fenceSlot) != 0) {
                    return GpuApiAttempt.failed(api(), deviceName, "vulkan_submit");
                }
            }
            BigDecimal score = GpuScores.mibPerSec(System.nanoTime() - start);
            if (score == null) {
                return GpuApiAttempt.failed(api(), deviceName, "vulkan_timing");
            }
            return GpuApiAttempt.measured(api(), deviceName, score);
        } finally {
            procs.destroy(handles);
        }
    }

    private static boolean createInstance(Procs procs, Arena arena, Handles handles) {
        MemorySegment appName = arena.allocateUtf8String("agentbench");
        MemorySegment engine = arena.allocateUtf8String("agentbench-gpu-probe-v1");
        MemorySegment app = VulkanLayouts.alloc(arena, VulkanLayouts.APPLICATION_INFO);
        VulkanLayouts.i32(app, VulkanLayouts.APPLICATION_INFO, "sType", VulkanLayouts.TYPE_APPLICATION_INFO);
        VulkanLayouts.addr(app, VulkanLayouts.APPLICATION_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.addr(app, VulkanLayouts.APPLICATION_INFO, "pApplicationName", appName);
        VulkanLayouts.i32(app, VulkanLayouts.APPLICATION_INFO, "applicationVersion", 1);
        VulkanLayouts.addr(app, VulkanLayouts.APPLICATION_INFO, "pEngineName", engine);
        VulkanLayouts.i32(app, VulkanLayouts.APPLICATION_INFO, "engineVersion", 1);
        VulkanLayouts.i32(app, VulkanLayouts.APPLICATION_INFO, "apiVersion", VulkanLayouts.API_VERSION_1_0);
        MemorySegment info = VulkanLayouts.alloc(arena, VulkanLayouts.INSTANCE_CREATE_INFO);
        VulkanLayouts.i32(info, VulkanLayouts.INSTANCE_CREATE_INFO, "sType", VulkanLayouts.TYPE_INSTANCE_CREATE_INFO);
        VulkanLayouts.addr(info, VulkanLayouts.INSTANCE_CREATE_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(info, VulkanLayouts.INSTANCE_CREATE_INFO, "flags", 0);
        VulkanLayouts.addr(info, VulkanLayouts.INSTANCE_CREATE_INFO, "pApplicationInfo", app);
        VulkanLayouts.i32(info, VulkanLayouts.INSTANCE_CREATE_INFO, "enabledLayerCount", 0);
        VulkanLayouts.addr(info, VulkanLayouts.INSTANCE_CREATE_INFO, "ppEnabledLayerNames", MemorySegment.NULL);
        VulkanLayouts.i32(info, VulkanLayouts.INSTANCE_CREATE_INFO, "enabledExtensionCount", 0);
        VulkanLayouts.addr(info, VulkanLayouts.INSTANCE_CREATE_INFO, "ppEnabledExtensionNames", MemorySegment.NULL);
        MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
        if (NativeLibrary.invoke(procs.createInstance, info, MemorySegment.NULL, out) != 0) {
            return false;
        }
        handles.instance = out.get(ValueLayout.ADDRESS, 0);
        return handles.instance != null && !handles.instance.equals(MemorySegment.NULL);
    }

    private static Chosen pickDevice(Procs procs, Arena arena, MemorySegment instance) {
        MemorySegment countSlot = arena.allocate(ValueLayout.JAVA_INT);
        if (NativeLibrary.invoke(procs.enumerate, instance, countSlot, MemorySegment.NULL) != 0) {
            return null;
        }
        int count = countSlot.get(ValueLayout.JAVA_INT, 0);
        if (count <= 0) {
            return Chosen.none();
        }
        int cap = Math.min(count, 16);
        countSlot.set(ValueLayout.JAVA_INT, 0, cap);
        MemorySegment list = arena.allocateArray(ValueLayout.ADDRESS, cap);
        if (NativeLibrary.invoke(procs.enumerate, instance, countSlot, list) != 0) {
            return null;
        }
        int written = Math.min(cap, countSlot.get(ValueLayout.JAVA_INT, 0));
        MemorySegment props = arena.allocate(1024);
        Chosen best = null;
        for (int i = 0; i < written; i++) {
            MemorySegment physical = list.getAtIndex(ValueLayout.ADDRESS, i);
            if (physical == null || physical.equals(MemorySegment.NULL)) {
                continue;
            }
            NativeLibrary.invoke(procs.deviceProps, physical, props);
            int type = props.get(ValueLayout.JAVA_INT, 16);
            if (!VulkanLayouts.usableDeviceType(type)) {
                continue;
            }
            String name = GpuText.device(readName(props));
            if (name == null || GpuText.software(name)) {
                continue;
            }
            int rank = type == 2 ? 2 : 1;
            if (best == null || rank > best.rank) {
                best = new Chosen(physical, name, rank);
            }
        }
        return best == null ? Chosen.none() : best;
    }

    private static String readName(MemorySegment props) {
        try {
            return props.getUtf8String(20);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static int queueFamily(Procs procs, Arena arena, MemorySegment physical) {
        MemorySegment countSlot = arena.allocate(ValueLayout.JAVA_INT);
        NativeLibrary.invoke(procs.queueProps, physical, countSlot, MemorySegment.NULL);
        int count = countSlot.get(ValueLayout.JAVA_INT, 0);
        if (count <= 0 || count > 64) {
            return -1;
        }
        countSlot.set(ValueLayout.JAVA_INT, 0, count);
        MemorySegment families = arena.allocate((long) count * VulkanLayouts.QUEUE_FAMILY.byteSize());
        NativeLibrary.invoke(procs.queueProps, physical, countSlot, families);
        int written = Math.min(count, countSlot.get(ValueLayout.JAVA_INT, 0));
        long stride = VulkanLayouts.QUEUE_FAMILY.byteSize();
        int transfer = -1;
        int graphics = -1;
        int compute = -1;
        for (int i = 0; i < written; i++) {
            int flags = families.get(ValueLayout.JAVA_INT, i * stride);
            int queues = families.get(ValueLayout.JAVA_INT, i * stride + 4);
            if (queues <= 0) {
                continue;
            }
            if (transfer < 0 && (flags & VulkanLayouts.QUEUE_TRANSFER) != 0) {
                transfer = i;
            }
            if (graphics < 0 && (flags & VulkanLayouts.QUEUE_GRAPHICS) != 0) {
                graphics = i;
            }
            if (compute < 0 && (flags & VulkanLayouts.QUEUE_COMPUTE) != 0) {
                compute = i;
            }
        }
        if (transfer >= 0) {
            return transfer;
        }
        if (graphics >= 0) {
            return graphics;
        }
        return compute;
    }

    private static boolean createDevice(
            Procs procs, Arena arena, Handles handles, MemorySegment physical, int family) {
        MemorySegment priority = arena.allocate(ValueLayout.JAVA_FLOAT);
        priority.set(ValueLayout.JAVA_FLOAT, 0, 1.0f);
        MemorySegment queueInfo = VulkanLayouts.alloc(arena, VulkanLayouts.DEVICE_QUEUE_CREATE_INFO);
        VulkanLayouts.i32(queueInfo, VulkanLayouts.DEVICE_QUEUE_CREATE_INFO, "sType", VulkanLayouts.TYPE_DEVICE_QUEUE_CREATE_INFO);
        VulkanLayouts.addr(queueInfo, VulkanLayouts.DEVICE_QUEUE_CREATE_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(queueInfo, VulkanLayouts.DEVICE_QUEUE_CREATE_INFO, "flags", 0);
        VulkanLayouts.i32(queueInfo, VulkanLayouts.DEVICE_QUEUE_CREATE_INFO, "queueFamilyIndex", family);
        VulkanLayouts.i32(queueInfo, VulkanLayouts.DEVICE_QUEUE_CREATE_INFO, "queueCount", 1);
        VulkanLayouts.addr(queueInfo, VulkanLayouts.DEVICE_QUEUE_CREATE_INFO, "pQueuePriorities", priority);
        MemorySegment info = VulkanLayouts.alloc(arena, VulkanLayouts.DEVICE_CREATE_INFO);
        VulkanLayouts.i32(info, VulkanLayouts.DEVICE_CREATE_INFO, "sType", VulkanLayouts.TYPE_DEVICE_CREATE_INFO);
        VulkanLayouts.addr(info, VulkanLayouts.DEVICE_CREATE_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(info, VulkanLayouts.DEVICE_CREATE_INFO, "flags", 0);
        VulkanLayouts.i32(info, VulkanLayouts.DEVICE_CREATE_INFO, "queueCreateInfoCount", 1);
        VulkanLayouts.addr(info, VulkanLayouts.DEVICE_CREATE_INFO, "pQueueCreateInfos", queueInfo);
        VulkanLayouts.i32(info, VulkanLayouts.DEVICE_CREATE_INFO, "enabledLayerCount", 0);
        VulkanLayouts.addr(info, VulkanLayouts.DEVICE_CREATE_INFO, "ppEnabledLayerNames", MemorySegment.NULL);
        VulkanLayouts.i32(info, VulkanLayouts.DEVICE_CREATE_INFO, "enabledExtensionCount", 0);
        VulkanLayouts.addr(info, VulkanLayouts.DEVICE_CREATE_INFO, "ppEnabledExtensionNames", MemorySegment.NULL);
        VulkanLayouts.addr(info, VulkanLayouts.DEVICE_CREATE_INFO, "pEnabledFeatures", MemorySegment.NULL);
        MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
        if (NativeLibrary.invoke(procs.createDevice, physical, info, MemorySegment.NULL, out) != 0) {
            return false;
        }
        handles.device = out.get(ValueLayout.ADDRESS, 0);
        return handles.device != null && !handles.device.equals(MemorySegment.NULL);
    }

    private static boolean createBuffer(Procs procs, Arena arena, Handles handles, boolean source) {
        MemorySegment info = VulkanLayouts.alloc(arena, VulkanLayouts.BUFFER_CREATE_INFO);
        VulkanLayouts.i32(info, VulkanLayouts.BUFFER_CREATE_INFO, "sType", VulkanLayouts.TYPE_BUFFER_CREATE_INFO);
        VulkanLayouts.addr(info, VulkanLayouts.BUFFER_CREATE_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(info, VulkanLayouts.BUFFER_CREATE_INFO, "flags", 0);
        VulkanLayouts.i64(info, VulkanLayouts.BUFFER_CREATE_INFO, "size", GpuScores.BUFFER_BYTES);
        VulkanLayouts.i32(info, VulkanLayouts.BUFFER_CREATE_INFO, "usage", VulkanLayouts.BUFFER_TRANSFER);
        VulkanLayouts.i32(info, VulkanLayouts.BUFFER_CREATE_INFO, "sharingMode", 0);
        VulkanLayouts.i32(info, VulkanLayouts.BUFFER_CREATE_INFO, "queueFamilyIndexCount", 0);
        VulkanLayouts.addr(info, VulkanLayouts.BUFFER_CREATE_INFO, "pQueueFamilyIndices", MemorySegment.NULL);
        MemorySegment bufferOut = arena.allocate(ValueLayout.ADDRESS);
        if (NativeLibrary.invoke(procs.createBuffer, handles.device, info, MemorySegment.NULL, bufferOut) != 0) {
            return false;
        }
        MemorySegment buffer = bufferOut.get(ValueLayout.ADDRESS, 0);
        if (source) {
            handles.srcBuffer = buffer;
        } else {
            handles.dstBuffer = buffer;
        }
        MemorySegment requirements = VulkanLayouts.alloc(arena, VulkanLayouts.MEMORY_REQUIREMENTS);
        NativeLibrary.invoke(procs.requirements, handles.device, buffer, requirements);
        MemorySegment memProps = arena.allocate(1024);
        NativeLibrary.invoke(procs.memoryProps, handles.physicalForMemory, memProps);
        int typeBits = VulkanLayouts.i32(requirements, VulkanLayouts.MEMORY_REQUIREMENTS, "memoryTypeBits");
        int typeIndex = memoryType(memProps, typeBits);
        if (typeIndex < 0) {
            return false;
        }
        long bytes = VulkanLayouts.i64(requirements, VulkanLayouts.MEMORY_REQUIREMENTS, "size");
        if (bytes <= 0) {
            return false;
        }
        MemorySegment alloc = VulkanLayouts.alloc(arena, VulkanLayouts.MEMORY_ALLOCATE_INFO);
        VulkanLayouts.i32(alloc, VulkanLayouts.MEMORY_ALLOCATE_INFO, "sType", VulkanLayouts.TYPE_MEMORY_ALLOCATE_INFO);
        VulkanLayouts.addr(alloc, VulkanLayouts.MEMORY_ALLOCATE_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.i64(alloc, VulkanLayouts.MEMORY_ALLOCATE_INFO, "allocationSize", bytes);
        VulkanLayouts.i32(alloc, VulkanLayouts.MEMORY_ALLOCATE_INFO, "memoryTypeIndex", typeIndex);
        MemorySegment memoryOut = arena.allocate(ValueLayout.ADDRESS);
        if (NativeLibrary.invoke(procs.allocateMemory, handles.device, alloc, MemorySegment.NULL, memoryOut) != 0) {
            return false;
        }
        MemorySegment memory = memoryOut.get(ValueLayout.ADDRESS, 0);
        if (source) {
            handles.srcMemory = memory;
        } else {
            handles.dstMemory = memory;
        }
        return NativeLibrary.invoke(procs.bindMemory, handles.device, buffer, memory, 0L) == 0;
    }

    private static int memoryType(MemorySegment props, int typeBits) {
        int count = props.get(ValueLayout.JAVA_INT, 0);
        if (count <= 0 || count > 32) {
            return -1;
        }
        int fallback = -1;
        for (int i = 0; i < count; i++) {
            if ((typeBits & (1 << i)) == 0) {
                continue;
            }
            int flags = props.get(ValueLayout.JAVA_INT, 4L + (long) i * 8L);
            if (fallback < 0) {
                fallback = i;
            }
            if ((flags & VulkanLayouts.MEMORY_DEVICE_LOCAL) != 0) {
                return i;
            }
        }
        return fallback;
    }

    private static boolean createCommands(Procs procs, Arena arena, Handles handles, int family) {
        MemorySegment poolInfo = VulkanLayouts.alloc(arena, VulkanLayouts.COMMAND_POOL_CREATE_INFO);
        VulkanLayouts.i32(poolInfo, VulkanLayouts.COMMAND_POOL_CREATE_INFO, "sType", VulkanLayouts.TYPE_COMMAND_POOL_CREATE_INFO);
        VulkanLayouts.addr(poolInfo, VulkanLayouts.COMMAND_POOL_CREATE_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(poolInfo, VulkanLayouts.COMMAND_POOL_CREATE_INFO, "flags", 0);
        VulkanLayouts.i32(poolInfo, VulkanLayouts.COMMAND_POOL_CREATE_INFO, "queueFamilyIndex", family);
        MemorySegment poolOut = arena.allocate(ValueLayout.ADDRESS);
        if (NativeLibrary.invoke(procs.createPool, handles.device, poolInfo, MemorySegment.NULL, poolOut) != 0) {
            return false;
        }
        handles.pool = poolOut.get(ValueLayout.ADDRESS, 0);
        handles.fillCommand = allocateCommand(procs, arena, handles.device, handles.pool);
        handles.copyCommand = allocateCommand(procs, arena, handles.device, handles.pool);
        return handles.fillCommand != null && handles.copyCommand != null
                && !handles.fillCommand.equals(MemorySegment.NULL)
                && !handles.copyCommand.equals(MemorySegment.NULL);
    }

    private static MemorySegment allocateCommand(Procs procs, Arena arena, MemorySegment device, MemorySegment pool) {
        MemorySegment info = VulkanLayouts.alloc(arena, VulkanLayouts.COMMAND_BUFFER_ALLOCATE_INFO);
        VulkanLayouts.i32(info, VulkanLayouts.COMMAND_BUFFER_ALLOCATE_INFO, "sType", VulkanLayouts.TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
        VulkanLayouts.addr(info, VulkanLayouts.COMMAND_BUFFER_ALLOCATE_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.addr(info, VulkanLayouts.COMMAND_BUFFER_ALLOCATE_INFO, "commandPool", pool);
        VulkanLayouts.i32(info, VulkanLayouts.COMMAND_BUFFER_ALLOCATE_INFO, "level", 0);
        VulkanLayouts.i32(info, VulkanLayouts.COMMAND_BUFFER_ALLOCATE_INFO, "commandBufferCount", 1);
        MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
        if (NativeLibrary.invoke(procs.allocateCommand, device, info, out) != 0) {
            return MemorySegment.NULL;
        }
        return out.get(ValueLayout.ADDRESS, 0);
    }

    private static boolean recordFill(Procs procs, Arena arena, Handles handles) {
        if (!begin(procs, arena, handles.fillCommand)) {
            return false;
        }
        NativeLibrary.invoke(
                procs.fill,
                handles.fillCommand,
                handles.srcBuffer,
                0L,
                (long) GpuScores.BUFFER_BYTES,
                0xA5A5A5A5);
        return NativeLibrary.invoke(procs.end, handles.fillCommand) == 0;
    }

    private static boolean recordCopy(Procs procs, Arena arena, Handles handles) {
        if (!begin(procs, arena, handles.copyCommand)) {
            return false;
        }
        barrier(procs, arena, handles.copyCommand, handles.srcBuffer);
        copyRegion(procs, arena, handles.copyCommand, handles.srcBuffer, handles.dstBuffer);
        barrier(procs, arena, handles.copyCommand, handles.dstBuffer);
        copyRegion(procs, arena, handles.copyCommand, handles.dstBuffer, handles.srcBuffer);
        return NativeLibrary.invoke(procs.end, handles.copyCommand) == 0;
    }

    private static boolean begin(Procs procs, Arena arena, MemorySegment command) {
        MemorySegment info = VulkanLayouts.alloc(arena, VulkanLayouts.COMMAND_BUFFER_BEGIN_INFO);
        VulkanLayouts.i32(info, VulkanLayouts.COMMAND_BUFFER_BEGIN_INFO, "sType", VulkanLayouts.TYPE_COMMAND_BUFFER_BEGIN_INFO);
        VulkanLayouts.addr(info, VulkanLayouts.COMMAND_BUFFER_BEGIN_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(info, VulkanLayouts.COMMAND_BUFFER_BEGIN_INFO, "flags", 0);
        VulkanLayouts.addr(info, VulkanLayouts.COMMAND_BUFFER_BEGIN_INFO, "pInheritanceInfo", MemorySegment.NULL);
        return NativeLibrary.invoke(procs.begin, command, info) == 0;
    }

    private static void barrier(Procs procs, Arena arena, MemorySegment command, MemorySegment buffer) {
        MemorySegment barrier = VulkanLayouts.alloc(arena, VulkanLayouts.BUFFER_MEMORY_BARRIER);
        VulkanLayouts.i32(barrier, VulkanLayouts.BUFFER_MEMORY_BARRIER, "sType", VulkanLayouts.TYPE_BUFFER_MEMORY_BARRIER);
        VulkanLayouts.addr(barrier, VulkanLayouts.BUFFER_MEMORY_BARRIER, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(barrier, VulkanLayouts.BUFFER_MEMORY_BARRIER, "srcAccessMask", VulkanLayouts.ACCESS_TRANSFER_WRITE);
        VulkanLayouts.i32(barrier, VulkanLayouts.BUFFER_MEMORY_BARRIER, "dstAccessMask", VulkanLayouts.ACCESS_TRANSFER_READ);
        VulkanLayouts.i32(barrier, VulkanLayouts.BUFFER_MEMORY_BARRIER, "srcQueueFamilyIndex", -1);
        VulkanLayouts.i32(barrier, VulkanLayouts.BUFFER_MEMORY_BARRIER, "dstQueueFamilyIndex", -1);
        VulkanLayouts.addr(barrier, VulkanLayouts.BUFFER_MEMORY_BARRIER, "buffer", buffer);
        VulkanLayouts.i64(barrier, VulkanLayouts.BUFFER_MEMORY_BARRIER, "offset", 0L);
        VulkanLayouts.i64(barrier, VulkanLayouts.BUFFER_MEMORY_BARRIER, "size", GpuScores.BUFFER_BYTES);
        NativeLibrary.invoke(
                procs.barrier,
                command,
                VulkanLayouts.STAGE_TRANSFER,
                VulkanLayouts.STAGE_TRANSFER,
                0,
                0,
                MemorySegment.NULL,
                1,
                barrier,
                0,
                MemorySegment.NULL);
    }

    private static void copyRegion(
            Procs procs, Arena arena, MemorySegment command, MemorySegment src, MemorySegment dst) {
        MemorySegment region = VulkanLayouts.alloc(arena, VulkanLayouts.BUFFER_COPY);
        VulkanLayouts.i64(region, VulkanLayouts.BUFFER_COPY, "srcOffset", 0L);
        VulkanLayouts.i64(region, VulkanLayouts.BUFFER_COPY, "dstOffset", 0L);
        VulkanLayouts.i64(region, VulkanLayouts.BUFFER_COPY, "size", GpuScores.BUFFER_BYTES);
        NativeLibrary.invoke(procs.copy, command, src, dst, 1, region);
    }

    private static boolean createFence(Procs procs, Arena arena, Handles handles) {
        MemorySegment info = VulkanLayouts.alloc(arena, VulkanLayouts.FENCE_CREATE_INFO);
        VulkanLayouts.i32(info, VulkanLayouts.FENCE_CREATE_INFO, "sType", VulkanLayouts.TYPE_FENCE_CREATE_INFO);
        VulkanLayouts.addr(info, VulkanLayouts.FENCE_CREATE_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(info, VulkanLayouts.FENCE_CREATE_INFO, "flags", 0);
        MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
        if (NativeLibrary.invoke(procs.createFence, handles.device, info, MemorySegment.NULL, out) != 0) {
            return false;
        }
        handles.fence = out.get(ValueLayout.ADDRESS, 0);
        return handles.fence != null && !handles.fence.equals(MemorySegment.NULL);
    }

    private static boolean prepareSubmit(Arena arena, Handles handles) {
        MemorySegment commandSlot = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment submit = VulkanLayouts.alloc(arena, VulkanLayouts.SUBMIT_INFO);
        VulkanLayouts.i32(submit, VulkanLayouts.SUBMIT_INFO, "sType", VulkanLayouts.TYPE_SUBMIT_INFO);
        VulkanLayouts.addr(submit, VulkanLayouts.SUBMIT_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(submit, VulkanLayouts.SUBMIT_INFO, "waitSemaphoreCount", 0);
        VulkanLayouts.addr(submit, VulkanLayouts.SUBMIT_INFO, "pWaitSemaphores", MemorySegment.NULL);
        VulkanLayouts.addr(submit, VulkanLayouts.SUBMIT_INFO, "pWaitDstStageMask", MemorySegment.NULL);
        VulkanLayouts.i32(submit, VulkanLayouts.SUBMIT_INFO, "commandBufferCount", 1);
        VulkanLayouts.addr(submit, VulkanLayouts.SUBMIT_INFO, "pCommandBuffers", commandSlot);
        VulkanLayouts.i32(submit, VulkanLayouts.SUBMIT_INFO, "signalSemaphoreCount", 0);
        VulkanLayouts.addr(submit, VulkanLayouts.SUBMIT_INFO, "pSignalSemaphores", MemorySegment.NULL);
        handles.submitScratch = commandSlot;
        handles.submitInfo = submit;
        return true;
    }

    private static int submit(
            Procs procs, Handles handles, MemorySegment queue, MemorySegment command, MemorySegment fenceSlot) {
        if (handles.submitScratch == null || handles.submitInfo == null) {
            return -1;
        }
        if (NativeLibrary.invoke(procs.resetFences, handles.device, 1, fenceSlot) != 0) {
            return -1;
        }
        handles.submitScratch.set(ValueLayout.ADDRESS, 0, command);
        int submitted = NativeLibrary.invoke(procs.queueSubmit, queue, 1, handles.submitInfo, handles.fence);
        if (submitted != 0) {
            return submitted;
        }
        return NativeLibrary.invoke(
                procs.waitFences, handles.device, 1, fenceSlot, 1, VulkanLayouts.WAIT_NANOS);
    }

    private static final class Chosen {
        final MemorySegment physical;
        final String name;
        final int rank;

        Chosen(MemorySegment physical, String name, int rank) {
            this.physical = physical;
            this.name = name;
            this.rank = rank;
        }

        static Chosen none() {
            return new Chosen(null, null, 0);
        }
    }

    private static final class Handles {
        MemorySegment instance = MemorySegment.NULL;
        MemorySegment device = MemorySegment.NULL;
        MemorySegment physicalForMemory = MemorySegment.NULL;
        MemorySegment srcBuffer = MemorySegment.NULL;
        MemorySegment dstBuffer = MemorySegment.NULL;
        MemorySegment srcMemory = MemorySegment.NULL;
        MemorySegment dstMemory = MemorySegment.NULL;
        MemorySegment pool = MemorySegment.NULL;
        MemorySegment fillCommand = MemorySegment.NULL;
        MemorySegment copyCommand = MemorySegment.NULL;
        MemorySegment fence = MemorySegment.NULL;
        MemorySegment submitInfo = MemorySegment.NULL;
        MemorySegment submitScratch = MemorySegment.NULL;
    }

    private static final class Procs {
        final MethodHandle createInstance;
        final MethodHandle destroyInstance;
        final MethodHandle enumerate;
        final MethodHandle deviceProps;
        final MethodHandle queueProps;
        final MethodHandle createDevice;
        final MethodHandle destroyDevice;
        final MethodHandle getQueue;
        final MethodHandle createBuffer;
        final MethodHandle destroyBuffer;
        final MethodHandle requirements;
        final MethodHandle memoryProps;
        final MethodHandle allocateMemory;
        final MethodHandle freeMemory;
        final MethodHandle bindMemory;
        final MethodHandle createPool;
        final MethodHandle destroyPool;
        final MethodHandle allocateCommand;
        final MethodHandle begin;
        final MethodHandle end;
        final MethodHandle fill;
        final MethodHandle copy;
        final MethodHandle barrier;
        final MethodHandle createFence;
        final MethodHandle destroyFence;
        final MethodHandle resetFences;
        final MethodHandle queueSubmit;
        final MethodHandle waitFences;

        private Procs(
                MethodHandle createInstance,
                MethodHandle destroyInstance,
                MethodHandle enumerate,
                MethodHandle deviceProps,
                MethodHandle queueProps,
                MethodHandle createDevice,
                MethodHandle destroyDevice,
                MethodHandle getQueue,
                MethodHandle createBuffer,
                MethodHandle destroyBuffer,
                MethodHandle requirements,
                MethodHandle memoryProps,
                MethodHandle allocateMemory,
                MethodHandle freeMemory,
                MethodHandle bindMemory,
                MethodHandle createPool,
                MethodHandle destroyPool,
                MethodHandle allocateCommand,
                MethodHandle begin,
                MethodHandle end,
                MethodHandle fill,
                MethodHandle copy,
                MethodHandle barrier,
                MethodHandle createFence,
                MethodHandle destroyFence,
                MethodHandle resetFences,
                MethodHandle queueSubmit,
                MethodHandle waitFences) {
            this.createInstance = createInstance;
            this.destroyInstance = destroyInstance;
            this.enumerate = enumerate;
            this.deviceProps = deviceProps;
            this.queueProps = queueProps;
            this.createDevice = createDevice;
            this.destroyDevice = destroyDevice;
            this.getQueue = getQueue;
            this.createBuffer = createBuffer;
            this.destroyBuffer = destroyBuffer;
            this.requirements = requirements;
            this.memoryProps = memoryProps;
            this.allocateMemory = allocateMemory;
            this.freeMemory = freeMemory;
            this.bindMemory = bindMemory;
            this.createPool = createPool;
            this.destroyPool = destroyPool;
            this.allocateCommand = allocateCommand;
            this.begin = begin;
            this.end = end;
            this.fill = fill;
            this.copy = copy;
            this.barrier = barrier;
            this.createFence = createFence;
            this.destroyFence = destroyFence;
            this.resetFences = resetFences;
            this.queueSubmit = queueSubmit;
            this.waitFences = waitFences;
        }

        static Procs load(NativeLibrary nativeLibrary) {
            Procs procs = new Procs(
                    down(nativeLibrary, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkCreateInstance"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkDestroyInstance"),
                    down(nativeLibrary, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkEnumeratePhysicalDevices"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkGetPhysicalDeviceProperties"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkGetPhysicalDeviceQueueFamilyProperties"),
                    down(nativeLibrary, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkCreateDevice"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkDestroyDevice"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS), "vkGetDeviceQueue"),
                    down(nativeLibrary, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkCreateBuffer"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkDestroyBuffer"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkGetBufferMemoryRequirements"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkGetPhysicalDeviceMemoryProperties"),
                    down(nativeLibrary, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkAllocateMemory"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkFreeMemory"),
                    down(nativeLibrary, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), "vkBindBufferMemory"),
                    down(nativeLibrary, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkCreateCommandPool"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkDestroyCommandPool"),
                    down(nativeLibrary, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkAllocateCommandBuffers"),
                    down(nativeLibrary, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkBeginCommandBuffer"),
                    down(nativeLibrary, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), "vkEndCommandBuffer"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT), "vkCmdFillBuffer"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS), "vkCmdCopyBuffer"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS), "vkCmdPipelineBarrier"),
                    down(nativeLibrary, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkCreateFence"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkDestroyFence"),
                    down(nativeLibrary, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS), "vkResetFences"),
                    down(nativeLibrary, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), "vkQueueSubmit"),
                    down(nativeLibrary, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG), "vkWaitForFences"));
            if (procs.createInstance == null || procs.enumerate == null || procs.deviceProps == null
                    || procs.queueProps == null || procs.createDevice == null || procs.getQueue == null
                    || procs.createBuffer == null || procs.requirements == null || procs.memoryProps == null
                    || procs.allocateMemory == null || procs.bindMemory == null || procs.createPool == null
                    || procs.allocateCommand == null || procs.begin == null || procs.end == null
                    || procs.fill == null || procs.copy == null || procs.barrier == null
                    || procs.createFence == null || procs.resetFences == null || procs.queueSubmit == null
                    || procs.waitFences == null || procs.destroyInstance == null || procs.destroyDevice == null) {
                return null;
            }
            return procs;
        }

        void destroy(Handles handles) {
            quiet(destroyFence, handles.device, handles.fence);
            quiet(destroyPool, handles.device, handles.pool);
            quiet(destroyBuffer, handles.device, handles.srcBuffer);
            quiet(destroyBuffer, handles.device, handles.dstBuffer);
            quiet(freeMemory, handles.device, handles.srcMemory);
            quiet(freeMemory, handles.device, handles.dstMemory);
            quiet(destroyDevice, handles.device, null);
            quiet(destroyInstance, handles.instance, null);
        }

        private void quiet(MethodHandle handle, MemorySegment first, MemorySegment second) {
            if (handle == null || first == null || first.equals(MemorySegment.NULL)) {
                return;
            }
            if (second != null && second.equals(MemorySegment.NULL)) {
                return;
            }
            try {
                if (second == null) {
                    NativeLibrary.invoke(handle, first, MemorySegment.NULL);
                } else {
                    NativeLibrary.invoke(handle, first, second, MemorySegment.NULL);
                }
            } catch (RuntimeException ignored) {
                // The probe result is already decided. Cleanup must not replace it.
            }
        }
    }

    private static MethodHandle down(NativeLibrary nativeLibrary, FunctionDescriptor descriptor, String name) {
        return nativeLibrary.downcall(descriptor, name);
    }
}
