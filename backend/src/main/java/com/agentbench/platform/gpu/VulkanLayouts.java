package com.agentbench.platform.gpu;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * LP64 Vulkan 1.0 struct layouts. Field offsets match the C ABI used by the loader.
 * A mismatch fails the layout test rather than producing a score.
 */
final class VulkanLayouts {

    static final int TYPE_APPLICATION_INFO = 0;
    static final int TYPE_INSTANCE_CREATE_INFO = 1;
    static final int TYPE_DEVICE_QUEUE_CREATE_INFO = 2;
    static final int TYPE_DEVICE_CREATE_INFO = 3;
    static final int TYPE_SUBMIT_INFO = 4;
    static final int TYPE_MEMORY_ALLOCATE_INFO = 5;
    static final int TYPE_FENCE_CREATE_INFO = 8;
    static final int TYPE_BUFFER_CREATE_INFO = 12;
    static final int TYPE_COMMAND_POOL_CREATE_INFO = 39;
    static final int TYPE_COMMAND_BUFFER_ALLOCATE_INFO = 40;
    static final int TYPE_COMMAND_BUFFER_BEGIN_INFO = 42;
    static final int TYPE_BUFFER_MEMORY_BARRIER = 44;

    static final int API_VERSION_1_0 = 1 << 22;
    static final int QUEUE_GRAPHICS = 0x1;
    static final int QUEUE_COMPUTE = 0x2;
    static final int QUEUE_TRANSFER = 0x4;
    static final int BUFFER_TRANSFER = 0x1 | 0x2;
    static final int STAGE_TRANSFER = 0x1000;
    static final int ACCESS_TRANSFER_READ = 0x800;
    static final int ACCESS_TRANSFER_WRITE = 0x1000;
    static final int MEMORY_DEVICE_LOCAL = 0x1;
    static final long WAIT_NANOS = 5_000_000_000L;

    static final MemoryLayout APPLICATION_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.ADDRESS.withName("pApplicationName"),
            ValueLayout.JAVA_INT.withName("applicationVersion"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pEngineName"),
            ValueLayout.JAVA_INT.withName("engineVersion"),
            ValueLayout.JAVA_INT.withName("apiVersion"));

    static final MemoryLayout INSTANCE_CREATE_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pApplicationInfo"),
            ValueLayout.JAVA_INT.withName("enabledLayerCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("ppEnabledLayerNames"),
            ValueLayout.JAVA_INT.withName("enabledExtensionCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("ppEnabledExtensionNames"));

    static final MemoryLayout DEVICE_QUEUE_CREATE_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_INT.withName("queueFamilyIndex"),
            ValueLayout.JAVA_INT.withName("queueCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pQueuePriorities"));

    static final MemoryLayout DEVICE_CREATE_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_INT.withName("queueCreateInfoCount"),
            ValueLayout.ADDRESS.withName("pQueueCreateInfos"),
            ValueLayout.JAVA_INT.withName("enabledLayerCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("ppEnabledLayerNames"),
            ValueLayout.JAVA_INT.withName("enabledExtensionCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("ppEnabledExtensionNames"),
            ValueLayout.ADDRESS.withName("pEnabledFeatures"));

    static final MemoryLayout BUFFER_CREATE_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.JAVA_LONG.withName("size"),
            ValueLayout.JAVA_INT.withName("usage"),
            ValueLayout.JAVA_INT.withName("sharingMode"),
            ValueLayout.JAVA_INT.withName("queueFamilyIndexCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pQueueFamilyIndices"));

    static final MemoryLayout MEMORY_ALLOCATE_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.JAVA_LONG.withName("allocationSize"),
            ValueLayout.JAVA_INT.withName("memoryTypeIndex"),
            MemoryLayout.paddingLayout(4));

    static final MemoryLayout MEMORY_REQUIREMENTS = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("size"),
            ValueLayout.JAVA_LONG.withName("alignment"),
            ValueLayout.JAVA_INT.withName("memoryTypeBits"),
            MemoryLayout.paddingLayout(4));

    static final MemoryLayout COMMAND_POOL_CREATE_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_INT.withName("queueFamilyIndex"));

    static final MemoryLayout COMMAND_BUFFER_ALLOCATE_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.ADDRESS.withName("commandPool"),
            ValueLayout.JAVA_INT.withName("level"),
            ValueLayout.JAVA_INT.withName("commandBufferCount"));

    static final MemoryLayout COMMAND_BUFFER_BEGIN_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pInheritanceInfo"));

    static final MemoryLayout BUFFER_COPY = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("srcOffset"),
            ValueLayout.JAVA_LONG.withName("dstOffset"),
            ValueLayout.JAVA_LONG.withName("size"));

    static final MemoryLayout BUFFER_MEMORY_BARRIER = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.JAVA_INT.withName("srcAccessMask"),
            ValueLayout.JAVA_INT.withName("dstAccessMask"),
            ValueLayout.JAVA_INT.withName("srcQueueFamilyIndex"),
            ValueLayout.JAVA_INT.withName("dstQueueFamilyIndex"),
            ValueLayout.ADDRESS.withName("buffer"),
            ValueLayout.JAVA_LONG.withName("offset"),
            ValueLayout.JAVA_LONG.withName("size"));

    static final MemoryLayout SUBMIT_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.JAVA_INT.withName("waitSemaphoreCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pWaitSemaphores"),
            ValueLayout.ADDRESS.withName("pWaitDstStageMask"),
            ValueLayout.JAVA_INT.withName("commandBufferCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pCommandBuffers"),
            ValueLayout.JAVA_INT.withName("signalSemaphoreCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pSignalSemaphores"));

    static final MemoryLayout FENCE_CREATE_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4));

    static final MemoryLayout QUEUE_FAMILY = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("queueFlags"),
            ValueLayout.JAVA_INT.withName("queueCount"),
            ValueLayout.JAVA_INT.withName("timestampValidBits"),
            ValueLayout.JAVA_INT.withName("width"),
            ValueLayout.JAVA_INT.withName("height"),
            ValueLayout.JAVA_INT.withName("depth"));

    private VulkanLayouts() {
    }

    static MemorySegment alloc(java.lang.foreign.Arena arena, MemoryLayout layout) {
        return arena.allocate(layout);
    }

    static void i32(MemorySegment segment, MemoryLayout layout, String name, int value) {
        segment.set(ValueLayout.JAVA_INT, layout.byteOffset(MemoryLayout.PathElement.groupElement(name)), value);
    }

    static void i64(MemorySegment segment, MemoryLayout layout, String name, long value) {
        segment.set(ValueLayout.JAVA_LONG, layout.byteOffset(MemoryLayout.PathElement.groupElement(name)), value);
    }

    static void addr(MemorySegment segment, MemoryLayout layout, String name, MemorySegment value) {
        segment.set(ValueLayout.ADDRESS, layout.byteOffset(MemoryLayout.PathElement.groupElement(name)), value);
    }

    static int i32(MemorySegment segment, MemoryLayout layout, String name) {
        return segment.get(ValueLayout.JAVA_INT, layout.byteOffset(MemoryLayout.PathElement.groupElement(name)));
    }

    static long i64(MemorySegment segment, MemoryLayout layout, String name) {
        return segment.get(ValueLayout.JAVA_LONG, layout.byteOffset(MemoryLayout.PathElement.groupElement(name)));
    }

    static boolean usableDeviceType(int type) {
        return type == 1 || type == 2;
    }
}
