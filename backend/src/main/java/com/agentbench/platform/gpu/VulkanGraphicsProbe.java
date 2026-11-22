package com.agentbench.platform.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.math.BigDecimal;
import java.nio.file.Path;

/**
 * Vulkan 1.0 clear of a 64×64 color target. Integrated and discrete devices
 * are eligible. CPU and virtual device types are not scored. The timed work is
 * {@link GpuGraphicsScores#FRAMES} submits of that clear. The score is the
 * average host frame time. This is not a product graphics score.
 */
final class VulkanGraphicsProbe implements GraphicsApiProbe {

    @Override
    public GraphicsAttempt attempt() {
        if (!NativeLibrary.supportedAbi()) {
            return GraphicsAttempt.failed(null, "unsupported_abi");
        }
        Path library = GpuLibraries.find("libvulkan.so.1", "libvulkan.so", "libvulkan.dylib", "vulkan-1.dll");
        if (library == null) {
            return GraphicsAttempt.absent();
        }
        try (NativeLibrary nativeLibrary = NativeLibrary.open(library)) {
            return measure(nativeLibrary);
        } catch (IllegalCallerException ex) {
            return GraphicsAttempt.failed(null, "native_access_denied");
        } catch (RuntimeException ex) {
            return GraphicsAttempt.failed(null, "vulkan_error");
        }
    }

    private GraphicsAttempt measure(NativeLibrary nativeLibrary) {
        Procs procs = Procs.load(nativeLibrary);
        if (procs == null) {
            return GraphicsAttempt.failed(null, "missing_symbol");
        }
        Arena arena = nativeLibrary.arena();
        Handles handles = new Handles();
        String deviceName = null;
        try {
            if (!createInstance(procs, arena, handles)) {
                return GraphicsAttempt.failed(null, "vulkan_instance");
            }
            Chosen chosen = pickDevice(procs, arena, handles.instance);
            if (chosen == null) {
                return GraphicsAttempt.failed(null, "vulkan_devices");
            }
            if (chosen.physical == null) {
                return GraphicsAttempt.noDevice();
            }
            deviceName = chosen.name;
            handles.physical = chosen.physical;
            int family = graphicsFamily(procs, arena, chosen.physical);
            if (family < 0) {
                return GraphicsAttempt.failed(deviceName, "vulkan_queue");
            }
            if (!createDevice(procs, arena, handles, chosen.physical, family)) {
                return GraphicsAttempt.failed(deviceName, "vulkan_device");
            }
            MemorySegment queueSlot = arena.allocate(ValueLayout.ADDRESS);
            NativeLibrary.invoke(procs.getQueue, handles.device, family, 0, queueSlot);
            MemorySegment queue = queueSlot.get(ValueLayout.ADDRESS, 0);
            if (!createImage(procs, arena, handles)) {
                return GraphicsAttempt.failed(deviceName, "vulkan_image");
            }
            if (!createView(procs, arena, handles)) {
                return GraphicsAttempt.failed(deviceName, "vulkan_view");
            }
            if (!createRenderPass(procs, arena, handles)) {
                return GraphicsAttempt.failed(deviceName, "vulkan_pass");
            }
            if (!createFramebuffer(procs, arena, handles)) {
                return GraphicsAttempt.failed(deviceName, "vulkan_framebuffer");
            }
            if (!createCommands(procs, arena, handles, family)) {
                return GraphicsAttempt.failed(deviceName, "vulkan_commands");
            }
            if (!recordSetup(procs, arena, handles) || !recordFrame(procs, arena, handles)) {
                return GraphicsAttempt.failed(deviceName, "vulkan_record");
            }
            if (!createFence(procs, arena, handles) || !prepareSubmit(arena, handles)) {
                return GraphicsAttempt.failed(deviceName, "vulkan_fence");
            }
            MemorySegment fenceSlot = arena.allocate(ValueLayout.ADDRESS);
            fenceSlot.set(ValueLayout.ADDRESS, 0, handles.fence);
            if (submit(procs, handles, queue, handles.setupCommand, fenceSlot) != 0) {
                return GraphicsAttempt.failed(deviceName, "vulkan_submit");
            }
            for (int i = 0; i < GpuGraphicsScores.WARMUP_FRAMES; i++) {
                if (submit(procs, handles, queue, handles.frameCommand, fenceSlot) != 0) {
                    return GraphicsAttempt.failed(deviceName, "vulkan_submit");
                }
            }
            long start = System.nanoTime();
            for (int i = 0; i < GpuGraphicsScores.FRAMES; i++) {
                if (submit(procs, handles, queue, handles.frameCommand, fenceSlot) != 0) {
                    return GraphicsAttempt.failed(deviceName, "vulkan_submit");
                }
            }
            BigDecimal frameTime = GpuGraphicsScores.frameTimeMs(
                    System.nanoTime() - start, GpuGraphicsScores.FRAMES);
            if (frameTime == null) {
                return GraphicsAttempt.failed(deviceName, "vulkan_timing");
            }
            return GraphicsAttempt.measured(deviceName, GpuGraphicsScores.FRAMES, frameTime);
        } finally {
            procs.destroy(handles);
        }
    }

    private static boolean createInstance(Procs procs, Arena arena, Handles handles) {
        MemorySegment appName = arena.allocateUtf8String("agentbench");
        MemorySegment engine = arena.allocateUtf8String(GpuGraphicsResult.PROBE_NAME);
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

    private static int graphicsFamily(Procs procs, Arena arena, MemorySegment physical) {
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
        for (int i = 0; i < written; i++) {
            int flags = families.get(ValueLayout.JAVA_INT, i * stride);
            int queues = families.get(ValueLayout.JAVA_INT, i * stride + 4);
            if (queues > 0 && (flags & VulkanLayouts.QUEUE_GRAPHICS) != 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean createDevice(
            Procs procs, Arena arena, Handles handles, MemorySegment physical, int family) {
        MemorySegment priority = arena.allocate(ValueLayout.JAVA_FLOAT);
        priority.set(ValueLayout.JAVA_FLOAT, 0, 1.0f);
        MemorySegment queueInfo = VulkanLayouts.alloc(arena, VulkanLayouts.DEVICE_QUEUE_CREATE_INFO);
        VulkanLayouts.i32(queueInfo, VulkanLayouts.DEVICE_QUEUE_CREATE_INFO, "sType",
                VulkanLayouts.TYPE_DEVICE_QUEUE_CREATE_INFO);
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

    private static boolean createImage(Procs procs, Arena arena, Handles handles) {
        MemorySegment info = VulkanLayouts.alloc(arena, VulkanGraphicsLayouts.IMAGE_CREATE_INFO);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "sType",
                VulkanGraphicsLayouts.TYPE_IMAGE_CREATE_INFO);
        VulkanLayouts.addr(info, VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "flags", 0);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "imageType",
                VulkanGraphicsLayouts.IMAGE_TYPE_2D);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "format",
                VulkanGraphicsLayouts.FORMAT_RGBA8);
        long extent = VulkanGraphicsLayouts.IMAGE_CREATE_INFO.byteOffset(
                MemoryLayout.PathElement.groupElement("extent"));
        info.set(ValueLayout.JAVA_INT, extent, GpuGraphicsScores.WIDTH);
        info.set(ValueLayout.JAVA_INT, extent + 4, GpuGraphicsScores.HEIGHT);
        info.set(ValueLayout.JAVA_INT, extent + 8, 1);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "mipLevels", 1);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "arrayLayers", 1);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "samples",
                VulkanGraphicsLayouts.SAMPLE_1);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "tiling",
                VulkanGraphicsLayouts.TILING_OPTIMAL);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "usage",
                VulkanGraphicsLayouts.USAGE_COLOR);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "sharingMode",
                VulkanGraphicsLayouts.SHARING_EXCLUSIVE);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "queueFamilyIndexCount", 0);
        VulkanLayouts.addr(info, VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "pQueueFamilyIndices", MemorySegment.NULL);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "initialLayout",
                VulkanGraphicsLayouts.LAYOUT_UNDEFINED);
        MemorySegment imageOut = arena.allocate(ValueLayout.ADDRESS);
        if (NativeLibrary.invoke(procs.createImage, handles.device, info, MemorySegment.NULL, imageOut) != 0) {
            return false;
        }
        handles.image = imageOut.get(ValueLayout.ADDRESS, 0);
        if (handles.image == null || handles.image.equals(MemorySegment.NULL)) {
            return false;
        }
        MemorySegment requirements = VulkanLayouts.alloc(arena, VulkanLayouts.MEMORY_REQUIREMENTS);
        NativeLibrary.invoke(procs.imageRequirements, handles.device, handles.image, requirements);
        MemorySegment memProps = arena.allocate(1024);
        NativeLibrary.invoke(procs.memoryProps, handles.physical, memProps);
        int typeBits = VulkanLayouts.i32(requirements, VulkanLayouts.MEMORY_REQUIREMENTS, "memoryTypeBits");
        int typeIndex = memoryType(memProps, typeBits);
        long bytes = VulkanLayouts.i64(requirements, VulkanLayouts.MEMORY_REQUIREMENTS, "size");
        if (typeIndex < 0 || bytes <= 0) {
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
        handles.memory = memoryOut.get(ValueLayout.ADDRESS, 0);
        return NativeLibrary.invoke(procs.bindImageMemory, handles.device, handles.image, handles.memory, 0L) == 0;
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

    private static boolean createView(Procs procs, Arena arena, Handles handles) {
        MemorySegment info = VulkanLayouts.alloc(arena, VulkanGraphicsLayouts.IMAGE_VIEW_CREATE_INFO);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_VIEW_CREATE_INFO, "sType",
                VulkanGraphicsLayouts.TYPE_IMAGE_VIEW_CREATE_INFO);
        VulkanLayouts.addr(info, VulkanGraphicsLayouts.IMAGE_VIEW_CREATE_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_VIEW_CREATE_INFO, "flags", 0);
        VulkanLayouts.addr(info, VulkanGraphicsLayouts.IMAGE_VIEW_CREATE_INFO, "image", handles.image);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_VIEW_CREATE_INFO, "viewType",
                VulkanGraphicsLayouts.VIEW_TYPE_2D);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.IMAGE_VIEW_CREATE_INFO, "format",
                VulkanGraphicsLayouts.FORMAT_RGBA8);
        long sub = VulkanGraphicsLayouts.IMAGE_VIEW_CREATE_INFO.byteOffset(
                MemoryLayout.PathElement.groupElement("subresourceRange"));
        info.set(ValueLayout.JAVA_INT, sub, VulkanGraphicsLayouts.ASPECT_COLOR);
        info.set(ValueLayout.JAVA_INT, sub + 8, 1);
        info.set(ValueLayout.JAVA_INT, sub + 16, 1);
        MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
        if (NativeLibrary.invoke(procs.createView, handles.device, info, MemorySegment.NULL, out) != 0) {
            return false;
        }
        handles.view = out.get(ValueLayout.ADDRESS, 0);
        return handles.view != null && !handles.view.equals(MemorySegment.NULL);
    }

    private static boolean createRenderPass(Procs procs, Arena arena, Handles handles) {
        MemorySegment attachment = VulkanLayouts.alloc(arena, VulkanGraphicsLayouts.ATTACHMENT_DESCRIPTION);
        VulkanLayouts.i32(attachment, VulkanGraphicsLayouts.ATTACHMENT_DESCRIPTION, "format",
                VulkanGraphicsLayouts.FORMAT_RGBA8);
        VulkanLayouts.i32(attachment, VulkanGraphicsLayouts.ATTACHMENT_DESCRIPTION, "samples",
                VulkanGraphicsLayouts.SAMPLE_1);
        VulkanLayouts.i32(attachment, VulkanGraphicsLayouts.ATTACHMENT_DESCRIPTION, "loadOp",
                VulkanGraphicsLayouts.LOAD_CLEAR);
        VulkanLayouts.i32(attachment, VulkanGraphicsLayouts.ATTACHMENT_DESCRIPTION, "storeOp",
                VulkanGraphicsLayouts.STORE_STORE);
        VulkanLayouts.i32(attachment, VulkanGraphicsLayouts.ATTACHMENT_DESCRIPTION, "stencilLoadOp",
                VulkanGraphicsLayouts.LOAD_DONT_CARE);
        VulkanLayouts.i32(attachment, VulkanGraphicsLayouts.ATTACHMENT_DESCRIPTION, "stencilStoreOp",
                VulkanGraphicsLayouts.STORE_DONT_CARE);
        VulkanLayouts.i32(attachment, VulkanGraphicsLayouts.ATTACHMENT_DESCRIPTION, "initialLayout",
                VulkanGraphicsLayouts.LAYOUT_COLOR);
        VulkanLayouts.i32(attachment, VulkanGraphicsLayouts.ATTACHMENT_DESCRIPTION, "finalLayout",
                VulkanGraphicsLayouts.LAYOUT_COLOR);
        MemorySegment reference = VulkanLayouts.alloc(arena, VulkanGraphicsLayouts.ATTACHMENT_REFERENCE);
        VulkanLayouts.i32(reference, VulkanGraphicsLayouts.ATTACHMENT_REFERENCE, "attachment", 0);
        VulkanLayouts.i32(reference, VulkanGraphicsLayouts.ATTACHMENT_REFERENCE, "layout",
                VulkanGraphicsLayouts.LAYOUT_COLOR);
        MemorySegment subpass = VulkanLayouts.alloc(arena, VulkanGraphicsLayouts.SUBPASS);
        VulkanLayouts.i32(subpass, VulkanGraphicsLayouts.SUBPASS, "pipelineBindPoint",
                VulkanGraphicsLayouts.BIND_GRAPHICS);
        VulkanLayouts.i32(subpass, VulkanGraphicsLayouts.SUBPASS, "colorAttachmentCount", 1);
        VulkanLayouts.addr(subpass, VulkanGraphicsLayouts.SUBPASS, "pColorAttachments", reference);
        VulkanLayouts.addr(subpass, VulkanGraphicsLayouts.SUBPASS, "pInputAttachments", MemorySegment.NULL);
        VulkanLayouts.addr(subpass, VulkanGraphicsLayouts.SUBPASS, "pResolveAttachments", MemorySegment.NULL);
        VulkanLayouts.addr(subpass, VulkanGraphicsLayouts.SUBPASS, "pDepthStencilAttachment", MemorySegment.NULL);
        VulkanLayouts.addr(subpass, VulkanGraphicsLayouts.SUBPASS, "pPreserveAttachments", MemorySegment.NULL);
        MemorySegment info = VulkanLayouts.alloc(arena, VulkanGraphicsLayouts.RENDER_PASS_CREATE_INFO);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.RENDER_PASS_CREATE_INFO, "sType",
                VulkanGraphicsLayouts.TYPE_RENDER_PASS_CREATE_INFO);
        VulkanLayouts.addr(info, VulkanGraphicsLayouts.RENDER_PASS_CREATE_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.RENDER_PASS_CREATE_INFO, "flags", 0);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.RENDER_PASS_CREATE_INFO, "attachmentCount", 1);
        VulkanLayouts.addr(info, VulkanGraphicsLayouts.RENDER_PASS_CREATE_INFO, "pAttachments", attachment);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.RENDER_PASS_CREATE_INFO, "subpassCount", 1);
        VulkanLayouts.addr(info, VulkanGraphicsLayouts.RENDER_PASS_CREATE_INFO, "pSubpasses", subpass);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.RENDER_PASS_CREATE_INFO, "dependencyCount", 0);
        VulkanLayouts.addr(info, VulkanGraphicsLayouts.RENDER_PASS_CREATE_INFO, "pDependencies", MemorySegment.NULL);
        MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
        if (NativeLibrary.invoke(procs.createPass, handles.device, info, MemorySegment.NULL, out) != 0) {
            return false;
        }
        handles.renderPass = out.get(ValueLayout.ADDRESS, 0);
        return handles.renderPass != null && !handles.renderPass.equals(MemorySegment.NULL);
    }

    private static boolean createFramebuffer(Procs procs, Arena arena, Handles handles) {
        MemorySegment viewSlot = arena.allocate(ValueLayout.ADDRESS);
        viewSlot.set(ValueLayout.ADDRESS, 0, handles.view);
        MemorySegment info = VulkanLayouts.alloc(arena, VulkanGraphicsLayouts.FRAMEBUFFER_CREATE_INFO);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.FRAMEBUFFER_CREATE_INFO, "sType",
                VulkanGraphicsLayouts.TYPE_FRAMEBUFFER_CREATE_INFO);
        VulkanLayouts.addr(info, VulkanGraphicsLayouts.FRAMEBUFFER_CREATE_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.FRAMEBUFFER_CREATE_INFO, "flags", 0);
        VulkanLayouts.addr(info, VulkanGraphicsLayouts.FRAMEBUFFER_CREATE_INFO, "renderPass", handles.renderPass);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.FRAMEBUFFER_CREATE_INFO, "attachmentCount", 1);
        VulkanLayouts.addr(info, VulkanGraphicsLayouts.FRAMEBUFFER_CREATE_INFO, "pAttachments", viewSlot);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.FRAMEBUFFER_CREATE_INFO, "width", GpuGraphicsScores.WIDTH);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.FRAMEBUFFER_CREATE_INFO, "height", GpuGraphicsScores.HEIGHT);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.FRAMEBUFFER_CREATE_INFO, "layers", 1);
        MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
        if (NativeLibrary.invoke(procs.createFramebuffer, handles.device, info, MemorySegment.NULL, out) != 0) {
            return false;
        }
        handles.framebuffer = out.get(ValueLayout.ADDRESS, 0);
        return handles.framebuffer != null && !handles.framebuffer.equals(MemorySegment.NULL);
    }

    private static boolean createCommands(Procs procs, Arena arena, Handles handles, int family) {
        MemorySegment poolInfo = VulkanLayouts.alloc(arena, VulkanLayouts.COMMAND_POOL_CREATE_INFO);
        VulkanLayouts.i32(poolInfo, VulkanLayouts.COMMAND_POOL_CREATE_INFO, "sType",
                VulkanLayouts.TYPE_COMMAND_POOL_CREATE_INFO);
        VulkanLayouts.addr(poolInfo, VulkanLayouts.COMMAND_POOL_CREATE_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(poolInfo, VulkanLayouts.COMMAND_POOL_CREATE_INFO, "flags", 0);
        VulkanLayouts.i32(poolInfo, VulkanLayouts.COMMAND_POOL_CREATE_INFO, "queueFamilyIndex", family);
        MemorySegment poolOut = arena.allocate(ValueLayout.ADDRESS);
        if (NativeLibrary.invoke(procs.createPool, handles.device, poolInfo, MemorySegment.NULL, poolOut) != 0) {
            return false;
        }
        handles.pool = poolOut.get(ValueLayout.ADDRESS, 0);
        handles.setupCommand = allocateCommand(procs, arena, handles.device, handles.pool);
        handles.frameCommand = allocateCommand(procs, arena, handles.device, handles.pool);
        return live(handles.setupCommand) && live(handles.frameCommand);
    }

    private static MemorySegment allocateCommand(Procs procs, Arena arena, MemorySegment device, MemorySegment pool) {
        MemorySegment info = VulkanLayouts.alloc(arena, VulkanLayouts.COMMAND_BUFFER_ALLOCATE_INFO);
        VulkanLayouts.i32(info, VulkanLayouts.COMMAND_BUFFER_ALLOCATE_INFO, "sType",
                VulkanLayouts.TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
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

    private static boolean recordSetup(Procs procs, Arena arena, Handles handles) {
        if (!begin(procs, arena, handles.setupCommand)) {
            return false;
        }
        MemorySegment barrier = VulkanLayouts.alloc(arena, VulkanGraphicsLayouts.IMAGE_MEMORY_BARRIER);
        VulkanLayouts.i32(barrier, VulkanGraphicsLayouts.IMAGE_MEMORY_BARRIER, "sType",
                VulkanGraphicsLayouts.TYPE_IMAGE_MEMORY_BARRIER);
        VulkanLayouts.addr(barrier, VulkanGraphicsLayouts.IMAGE_MEMORY_BARRIER, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(barrier, VulkanGraphicsLayouts.IMAGE_MEMORY_BARRIER, "srcAccessMask", 0);
        VulkanLayouts.i32(barrier, VulkanGraphicsLayouts.IMAGE_MEMORY_BARRIER, "dstAccessMask",
                VulkanGraphicsLayouts.ACCESS_COLOR_WRITE);
        VulkanLayouts.i32(barrier, VulkanGraphicsLayouts.IMAGE_MEMORY_BARRIER, "oldLayout",
                VulkanGraphicsLayouts.LAYOUT_UNDEFINED);
        VulkanLayouts.i32(barrier, VulkanGraphicsLayouts.IMAGE_MEMORY_BARRIER, "newLayout",
                VulkanGraphicsLayouts.LAYOUT_COLOR);
        VulkanLayouts.i32(barrier, VulkanGraphicsLayouts.IMAGE_MEMORY_BARRIER, "srcQueueFamilyIndex", -1);
        VulkanLayouts.i32(barrier, VulkanGraphicsLayouts.IMAGE_MEMORY_BARRIER, "dstQueueFamilyIndex", -1);
        VulkanLayouts.addr(barrier, VulkanGraphicsLayouts.IMAGE_MEMORY_BARRIER, "image", handles.image);
        long sub = VulkanGraphicsLayouts.IMAGE_MEMORY_BARRIER.byteOffset(
                MemoryLayout.PathElement.groupElement("subresourceRange"));
        barrier.set(ValueLayout.JAVA_INT, sub, VulkanGraphicsLayouts.ASPECT_COLOR);
        barrier.set(ValueLayout.JAVA_INT, sub + 8, 1);
        barrier.set(ValueLayout.JAVA_INT, sub + 16, 1);
        NativeLibrary.invoke(
                procs.barrier,
                handles.setupCommand,
                VulkanGraphicsLayouts.STAGE_TOP,
                VulkanGraphicsLayouts.STAGE_COLOR_OUTPUT,
                0,
                0,
                MemorySegment.NULL,
                0,
                MemorySegment.NULL,
                1,
                barrier);
        return NativeLibrary.invoke(procs.end, handles.setupCommand) == 0;
    }

    private static boolean recordFrame(Procs procs, Arena arena, Handles handles) {
        if (!begin(procs, arena, handles.frameCommand)) {
            return false;
        }
        MemorySegment clear = arena.allocate(VulkanGraphicsLayouts.CLEAR_COLOR);
        clear.set(ValueLayout.JAVA_FLOAT, 0, 0.15f);
        clear.set(ValueLayout.JAVA_FLOAT, 4, 0.35f);
        clear.set(ValueLayout.JAVA_FLOAT, 8, 0.75f);
        clear.set(ValueLayout.JAVA_FLOAT, 12, 1.0f);
        MemorySegment info = VulkanLayouts.alloc(arena, VulkanGraphicsLayouts.RENDER_PASS_BEGIN_INFO);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.RENDER_PASS_BEGIN_INFO, "sType",
                VulkanGraphicsLayouts.TYPE_RENDER_PASS_BEGIN_INFO);
        VulkanLayouts.addr(info, VulkanGraphicsLayouts.RENDER_PASS_BEGIN_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.addr(info, VulkanGraphicsLayouts.RENDER_PASS_BEGIN_INFO, "renderPass", handles.renderPass);
        VulkanLayouts.addr(info, VulkanGraphicsLayouts.RENDER_PASS_BEGIN_INFO, "framebuffer", handles.framebuffer);
        long area = VulkanGraphicsLayouts.RENDER_PASS_BEGIN_INFO.byteOffset(
                MemoryLayout.PathElement.groupElement("renderArea"));
        info.set(ValueLayout.JAVA_INT, area + 8, GpuGraphicsScores.WIDTH);
        info.set(ValueLayout.JAVA_INT, area + 12, GpuGraphicsScores.HEIGHT);
        VulkanLayouts.i32(info, VulkanGraphicsLayouts.RENDER_PASS_BEGIN_INFO, "clearValueCount", 1);
        VulkanLayouts.addr(info, VulkanGraphicsLayouts.RENDER_PASS_BEGIN_INFO, "pClearValues", clear);
        NativeLibrary.invoke(procs.beginPass, handles.frameCommand, info, VulkanGraphicsLayouts.CONTENTS_INLINE);
        NativeLibrary.invoke(procs.endPass, handles.frameCommand);
        return NativeLibrary.invoke(procs.end, handles.frameCommand) == 0;
    }

    private static boolean begin(Procs procs, Arena arena, MemorySegment command) {
        MemorySegment info = VulkanLayouts.alloc(arena, VulkanLayouts.COMMAND_BUFFER_BEGIN_INFO);
        VulkanLayouts.i32(info, VulkanLayouts.COMMAND_BUFFER_BEGIN_INFO, "sType",
                VulkanLayouts.TYPE_COMMAND_BUFFER_BEGIN_INFO);
        VulkanLayouts.addr(info, VulkanLayouts.COMMAND_BUFFER_BEGIN_INFO, "pNext", MemorySegment.NULL);
        VulkanLayouts.i32(info, VulkanLayouts.COMMAND_BUFFER_BEGIN_INFO, "flags", 0);
        VulkanLayouts.addr(info, VulkanLayouts.COMMAND_BUFFER_BEGIN_INFO, "pInheritanceInfo", MemorySegment.NULL);
        return NativeLibrary.invoke(procs.begin, command, info) == 0;
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
        return live(handles.fence);
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

    private static boolean live(MemorySegment segment) {
        return segment != null && !segment.equals(MemorySegment.NULL);
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
        MemorySegment physical = MemorySegment.NULL;
        MemorySegment image = MemorySegment.NULL;
        MemorySegment memory = MemorySegment.NULL;
        MemorySegment view = MemorySegment.NULL;
        MemorySegment renderPass = MemorySegment.NULL;
        MemorySegment framebuffer = MemorySegment.NULL;
        MemorySegment pool = MemorySegment.NULL;
        MemorySegment setupCommand = MemorySegment.NULL;
        MemorySegment frameCommand = MemorySegment.NULL;
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
        final MethodHandle createImage;
        final MethodHandle destroyImage;
        final MethodHandle imageRequirements;
        final MethodHandle memoryProps;
        final MethodHandle allocateMemory;
        final MethodHandle freeMemory;
        final MethodHandle bindImageMemory;
        final MethodHandle createView;
        final MethodHandle destroyView;
        final MethodHandle createPass;
        final MethodHandle destroyPass;
        final MethodHandle createFramebuffer;
        final MethodHandle destroyFramebuffer;
        final MethodHandle createPool;
        final MethodHandle destroyPool;
        final MethodHandle allocateCommand;
        final MethodHandle begin;
        final MethodHandle end;
        final MethodHandle barrier;
        final MethodHandle beginPass;
        final MethodHandle endPass;
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
                MethodHandle createImage,
                MethodHandle destroyImage,
                MethodHandle imageRequirements,
                MethodHandle memoryProps,
                MethodHandle allocateMemory,
                MethodHandle freeMemory,
                MethodHandle bindImageMemory,
                MethodHandle createView,
                MethodHandle destroyView,
                MethodHandle createPass,
                MethodHandle destroyPass,
                MethodHandle createFramebuffer,
                MethodHandle destroyFramebuffer,
                MethodHandle createPool,
                MethodHandle destroyPool,
                MethodHandle allocateCommand,
                MethodHandle begin,
                MethodHandle end,
                MethodHandle barrier,
                MethodHandle beginPass,
                MethodHandle endPass,
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
            this.createImage = createImage;
            this.destroyImage = destroyImage;
            this.imageRequirements = imageRequirements;
            this.memoryProps = memoryProps;
            this.allocateMemory = allocateMemory;
            this.freeMemory = freeMemory;
            this.bindImageMemory = bindImageMemory;
            this.createView = createView;
            this.destroyView = destroyView;
            this.createPass = createPass;
            this.destroyPass = destroyPass;
            this.createFramebuffer = createFramebuffer;
            this.destroyFramebuffer = destroyFramebuffer;
            this.createPool = createPool;
            this.destroyPool = destroyPool;
            this.allocateCommand = allocateCommand;
            this.begin = begin;
            this.end = end;
            this.barrier = barrier;
            this.beginPass = beginPass;
            this.endPass = endPass;
            this.createFence = createFence;
            this.destroyFence = destroyFence;
            this.resetFences = resetFences;
            this.queueSubmit = queueSubmit;
            this.waitFences = waitFences;
        }

        static Procs load(NativeLibrary nativeLibrary) {
            Procs procs = new Procs(
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkCreateInstance"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkDestroyInstance"),
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkEnumeratePhysicalDevices"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkGetPhysicalDeviceProperties"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkGetPhysicalDeviceQueueFamilyProperties"),
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkCreateDevice"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkDestroyDevice"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                            "vkGetDeviceQueue"),
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkCreateImage"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkDestroyImage"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkGetImageMemoryRequirements"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkGetPhysicalDeviceMemoryProperties"),
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkAllocateMemory"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkFreeMemory"),
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                            "vkBindImageMemory"),
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkCreateImageView"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkDestroyImageView"),
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkCreateRenderPass"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkDestroyRenderPass"),
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkCreateFramebuffer"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkDestroyFramebuffer"),
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkCreateCommandPool"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkDestroyCommandPool"),
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkAllocateCommandBuffers"),
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkBeginCommandBuffer"),
                    down(nativeLibrary, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                            "vkEndCommandBuffer"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                            "vkCmdPipelineBarrier"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                            "vkCmdBeginRenderPass"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
                            "vkCmdEndRenderPass"),
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkCreateFence"),
                    down(nativeLibrary, FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkDestroyFence"),
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                            "vkResetFences"),
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            "vkQueueSubmit"),
                    down(nativeLibrary, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
                            "vkWaitForFences"));
            if (procs.createInstance == null || procs.destroyInstance == null || procs.enumerate == null
                    || procs.deviceProps == null || procs.queueProps == null || procs.createDevice == null
                    || procs.destroyDevice == null || procs.getQueue == null || procs.createImage == null
                    || procs.destroyImage == null || procs.imageRequirements == null || procs.memoryProps == null
                    || procs.allocateMemory == null || procs.freeMemory == null || procs.bindImageMemory == null
                    || procs.createView == null || procs.destroyView == null || procs.createPass == null
                    || procs.destroyPass == null || procs.createFramebuffer == null || procs.destroyFramebuffer == null
                    || procs.createPool == null || procs.destroyPool == null || procs.allocateCommand == null
                    || procs.begin == null || procs.end == null || procs.barrier == null
                    || procs.beginPass == null || procs.endPass == null || procs.createFence == null
                    || procs.destroyFence == null || procs.resetFences == null || procs.queueSubmit == null
                    || procs.waitFences == null) {
                return null;
            }
            return procs;
        }

        void destroy(Handles handles) {
            quiet(destroyFramebuffer, handles.device, handles.framebuffer);
            quiet(destroyPass, handles.device, handles.renderPass);
            quiet(destroyView, handles.device, handles.view);
            quiet(destroyImage, handles.device, handles.image);
            quiet(freeMemory, handles.device, handles.memory);
            quiet(destroyFence, handles.device, handles.fence);
            quiet(destroyPool, handles.device, handles.pool);
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
