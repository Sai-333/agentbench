package com.agentbench.platform.gpu;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;

/**
 * LP64 Vulkan 1.0 layouts for a clear-only color target. Field offsets match
 * the C ABI. A mismatch fails the layout test rather than producing a score.
 */
final class VulkanGraphicsLayouts {

    static final int TYPE_IMAGE_CREATE_INFO = 14;
    static final int TYPE_IMAGE_VIEW_CREATE_INFO = 15;
    static final int TYPE_FRAMEBUFFER_CREATE_INFO = 37;
    static final int TYPE_RENDER_PASS_CREATE_INFO = 38;
    static final int TYPE_RENDER_PASS_BEGIN_INFO = 43;
    static final int TYPE_IMAGE_MEMORY_BARRIER = 45;

    static final int FORMAT_RGBA8 = 37;
    static final int IMAGE_TYPE_2D = 1;
    static final int VIEW_TYPE_2D = 1;
    static final int SAMPLE_1 = 1;
    static final int TILING_OPTIMAL = 0;
    static final int USAGE_COLOR = 0x10;
    static final int SHARING_EXCLUSIVE = 0;
    static final int LAYOUT_UNDEFINED = 0;
    static final int LAYOUT_COLOR = 2;
    static final int ASPECT_COLOR = 1;
    static final int LOAD_CLEAR = 1;
    static final int STORE_STORE = 0;
    static final int LOAD_DONT_CARE = 2;
    static final int STORE_DONT_CARE = 1;
    static final int BIND_GRAPHICS = 0;
    static final int CONTENTS_INLINE = 0;
    static final int STAGE_TOP = 0x1;
    static final int STAGE_COLOR_OUTPUT = 0x400;
    static final int ACCESS_COLOR_WRITE = 0x100;

    static final MemoryLayout EXTENT_3D = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("width"),
            ValueLayout.JAVA_INT.withName("height"),
            ValueLayout.JAVA_INT.withName("depth"));

    static final MemoryLayout COMPONENTS = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("r"),
            ValueLayout.JAVA_INT.withName("g"),
            ValueLayout.JAVA_INT.withName("b"),
            ValueLayout.JAVA_INT.withName("a"));

    static final MemoryLayout SUBRESOURCE = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("aspectMask"),
            ValueLayout.JAVA_INT.withName("baseMipLevel"),
            ValueLayout.JAVA_INT.withName("levelCount"),
            ValueLayout.JAVA_INT.withName("baseArrayLayer"),
            ValueLayout.JAVA_INT.withName("layerCount"));

    static final MemoryLayout OFFSET_2D = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("x"),
            ValueLayout.JAVA_INT.withName("y"));

    static final MemoryLayout EXTENT_2D = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("width"),
            ValueLayout.JAVA_INT.withName("height"));

    static final MemoryLayout RECT_2D = MemoryLayout.structLayout(
            OFFSET_2D.withName("offset"),
            EXTENT_2D.withName("extent"));

    static final MemoryLayout IMAGE_CREATE_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_INT.withName("imageType"),
            ValueLayout.JAVA_INT.withName("format"),
            EXTENT_3D.withName("extent"),
            ValueLayout.JAVA_INT.withName("mipLevels"),
            ValueLayout.JAVA_INT.withName("arrayLayers"),
            ValueLayout.JAVA_INT.withName("samples"),
            ValueLayout.JAVA_INT.withName("tiling"),
            ValueLayout.JAVA_INT.withName("usage"),
            ValueLayout.JAVA_INT.withName("sharingMode"),
            ValueLayout.JAVA_INT.withName("queueFamilyIndexCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pQueueFamilyIndices"),
            ValueLayout.JAVA_INT.withName("initialLayout"),
            MemoryLayout.paddingLayout(4));

    static final MemoryLayout IMAGE_VIEW_CREATE_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("image"),
            ValueLayout.JAVA_INT.withName("viewType"),
            ValueLayout.JAVA_INT.withName("format"),
            COMPONENTS.withName("components"),
            SUBRESOURCE.withName("subresourceRange"),
            MemoryLayout.paddingLayout(4));

    static final MemoryLayout ATTACHMENT_DESCRIPTION = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_INT.withName("format"),
            ValueLayout.JAVA_INT.withName("samples"),
            ValueLayout.JAVA_INT.withName("loadOp"),
            ValueLayout.JAVA_INT.withName("storeOp"),
            ValueLayout.JAVA_INT.withName("stencilLoadOp"),
            ValueLayout.JAVA_INT.withName("stencilStoreOp"),
            ValueLayout.JAVA_INT.withName("initialLayout"),
            ValueLayout.JAVA_INT.withName("finalLayout"));

    static final MemoryLayout ATTACHMENT_REFERENCE = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("attachment"),
            ValueLayout.JAVA_INT.withName("layout"));

    static final MemoryLayout SUBPASS = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_INT.withName("pipelineBindPoint"),
            ValueLayout.JAVA_INT.withName("inputAttachmentCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pInputAttachments"),
            ValueLayout.JAVA_INT.withName("colorAttachmentCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pColorAttachments"),
            ValueLayout.ADDRESS.withName("pResolveAttachments"),
            ValueLayout.ADDRESS.withName("pDepthStencilAttachment"),
            ValueLayout.JAVA_INT.withName("preserveAttachmentCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pPreserveAttachments"));

    static final MemoryLayout RENDER_PASS_CREATE_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_INT.withName("attachmentCount"),
            ValueLayout.ADDRESS.withName("pAttachments"),
            ValueLayout.JAVA_INT.withName("subpassCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pSubpasses"),
            ValueLayout.JAVA_INT.withName("dependencyCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pDependencies"));

    static final MemoryLayout FRAMEBUFFER_CREATE_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("renderPass"),
            ValueLayout.JAVA_INT.withName("attachmentCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pAttachments"),
            ValueLayout.JAVA_INT.withName("width"),
            ValueLayout.JAVA_INT.withName("height"),
            ValueLayout.JAVA_INT.withName("layers"),
            MemoryLayout.paddingLayout(4));

    static final MemoryLayout RENDER_PASS_BEGIN_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.ADDRESS.withName("renderPass"),
            ValueLayout.ADDRESS.withName("framebuffer"),
            RECT_2D.withName("renderArea"),
            ValueLayout.JAVA_INT.withName("clearValueCount"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pClearValues"));

    static final MemoryLayout IMAGE_MEMORY_BARRIER = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pNext"),
            ValueLayout.JAVA_INT.withName("srcAccessMask"),
            ValueLayout.JAVA_INT.withName("dstAccessMask"),
            ValueLayout.JAVA_INT.withName("oldLayout"),
            ValueLayout.JAVA_INT.withName("newLayout"),
            ValueLayout.JAVA_INT.withName("srcQueueFamilyIndex"),
            ValueLayout.JAVA_INT.withName("dstQueueFamilyIndex"),
            ValueLayout.ADDRESS.withName("image"),
            SUBRESOURCE.withName("subresourceRange"),
            MemoryLayout.paddingLayout(4));

    static final MemoryLayout CLEAR_COLOR = MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT);

    private VulkanGraphicsLayouts() {
    }
}
