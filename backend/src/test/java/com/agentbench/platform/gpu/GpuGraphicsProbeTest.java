package com.agentbench.platform.gpu;

import com.agentbench.execution.service.HostCommandExecutor;
import com.agentbench.execution.service.ProcessHostCommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemoryLayout;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpuGraphicsProbeTest {

    @TempDir
    Path temp;

    @Test
    void measuredResultStoresFramesFrameTimeAndDevice() {
        GpuGraphicsResult measured = GpuGraphicsResult.measured(
                "NVIDIA sk-supersecretkey example", GpuGraphicsScores.FRAMES, new BigDecimal("1.25"));
        assertEquals("measured", measured.status());
        assertEquals(32, measured.frames());
        assertEquals(new BigDecimal("1.25"), measured.frameTimeMs());
        assertEquals(new BigDecimal("1.25"), measured.score());
        assertEquals("ms", measured.scoreUnit());
        assertFalse(measured.device().contains("sk-supersecretkey"));
        assertNull(measured.unavailableReason());
        assertThrows(IllegalArgumentException.class,
                () -> GpuGraphicsResult.measured("GPU", GpuGraphicsScores.FRAMES, null));
        assertThrows(IllegalArgumentException.class,
                () -> GpuGraphicsResult.measured("GPU", 1, new BigDecimal("1.25")));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuGraphicsResult(
                        "unavailable", null, 32, new BigDecimal("1.25"), "no_gpu", null));
        assertThrows(IllegalArgumentException.class,
                () -> GpuGraphicsResult.unavailable("GPU", "no_gpu", null));
    }

    @Test
    void frameTimeRejectsAClockGlitchAndKeepsARealDuration() {
        assertNull(GpuGraphicsScores.frameTimeMs(0, GpuGraphicsScores.FRAMES));
        assertNull(GpuGraphicsScores.frameTimeMs(-1, GpuGraphicsScores.FRAMES));
        assertNull(GpuGraphicsScores.frameTimeMs(1L, GpuGraphicsScores.FRAMES));
        assertNull(GpuGraphicsScores.frameTimeMs(32_000_000L, 1));
        assertEquals(new BigDecimal("1.00"), GpuGraphicsScores.frameTimeMs(32_000_000L, 32));
        assertEquals(new BigDecimal("0.50"), GpuGraphicsScores.frameTimeMs(16_000_000L, 32));
        assertNull(GpuGraphicsScores.frameTimeMs(320_016_000_000L, 32));
    }

    @Test
    void graphicsLayoutsMatchTheLp64Abi() {
        assertEquals(88, VulkanGraphicsLayouts.IMAGE_CREATE_INFO.byteSize());
        assertEquals(16, offset(VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "flags"));
        assertEquals(28, offset(VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "extent"));
        assertEquals(72, offset(VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "pQueueFamilyIndices"));
        assertEquals(80, offset(VulkanGraphicsLayouts.IMAGE_CREATE_INFO, "initialLayout"));
        assertEquals(80, VulkanGraphicsLayouts.IMAGE_VIEW_CREATE_INFO.byteSize());
        assertEquals(24, offset(VulkanGraphicsLayouts.IMAGE_VIEW_CREATE_INFO, "image"));
        assertEquals(40, offset(VulkanGraphicsLayouts.IMAGE_VIEW_CREATE_INFO, "components"));
        assertEquals(56, offset(VulkanGraphicsLayouts.IMAGE_VIEW_CREATE_INFO, "subresourceRange"));
        assertEquals(36, VulkanGraphicsLayouts.ATTACHMENT_DESCRIPTION.byteSize());
        assertEquals(8, VulkanGraphicsLayouts.ATTACHMENT_REFERENCE.byteSize());
        assertEquals(72, VulkanGraphicsLayouts.SUBPASS.byteSize());
        assertEquals(32, offset(VulkanGraphicsLayouts.SUBPASS, "pColorAttachments"));
        assertEquals(64, VulkanGraphicsLayouts.RENDER_PASS_CREATE_INFO.byteSize());
        assertEquals(24, offset(VulkanGraphicsLayouts.RENDER_PASS_CREATE_INFO, "pAttachments"));
        assertEquals(40, offset(VulkanGraphicsLayouts.RENDER_PASS_CREATE_INFO, "pSubpasses"));
        assertEquals(64, VulkanGraphicsLayouts.FRAMEBUFFER_CREATE_INFO.byteSize());
        assertEquals(24, offset(VulkanGraphicsLayouts.FRAMEBUFFER_CREATE_INFO, "renderPass"));
        assertEquals(40, offset(VulkanGraphicsLayouts.FRAMEBUFFER_CREATE_INFO, "pAttachments"));
        assertEquals(48, offset(VulkanGraphicsLayouts.FRAMEBUFFER_CREATE_INFO, "width"));
        assertEquals(16, VulkanGraphicsLayouts.CLEAR_COLOR.byteSize());
        assertEquals(64, VulkanGraphicsLayouts.RENDER_PASS_BEGIN_INFO.byteSize());
        assertEquals(32, offset(VulkanGraphicsLayouts.RENDER_PASS_BEGIN_INFO, "renderArea"));
        assertEquals(56, offset(VulkanGraphicsLayouts.RENDER_PASS_BEGIN_INFO, "pClearValues"));
        assertEquals(72, VulkanGraphicsLayouts.IMAGE_MEMORY_BARRIER.byteSize());
        assertEquals(16, offset(VulkanGraphicsLayouts.IMAGE_MEMORY_BARRIER, "srcAccessMask"));
        assertEquals(40, offset(VulkanGraphicsLayouts.IMAGE_MEMORY_BARRIER, "image"));
        assertEquals(48, offset(VulkanGraphicsLayouts.IMAGE_MEMORY_BARRIER, "subresourceRange"));
    }

    @Test
    void noGpuIsStoredWithoutAScore() {
        GpuGraphicsResult result = probe(temp.resolve("missing"), List.of(
                new Scripted(GraphicsAttempt.absent()))).probe();
        assertEquals("unavailable", result.status());
        assertEquals("no_gpu", result.unavailableReason());
        assertNull(result.score());
        assertNull(result.frameTimeMs());
        assertNull(result.frames());
        assertNull(result.device());
        assertNull(result.scoreUnit());
    }

    @Test
    void seenDeviceWithoutARunnableProbeIsExplicitFailure() throws Exception {
        writeCard(temp.resolve("card0"), "0x10de", "0x2684", "nvidia");
        GpuGraphicsResult result = probe(temp, List.of(
                new Scripted(GraphicsAttempt.absent()))).probe();
        assertEquals("unavailable", result.status());
        assertEquals("probe_failed", result.unavailableReason());
        assertEquals("NVIDIA 2684", result.device());
        assertNull(result.score());
        assertNull(result.frames());
        assertNull(result.frameTimeMs());
    }

    @Test
    void realFrameTimeWinsAndSoftwareNamesDoNot() {
        GpuGraphicsResult software = probe(temp.resolve("missing"), List.of(
                new Scripted(GraphicsAttempt.measured(
                        "llvmpipe (LLVM 15, 256 bits)", 32, new BigDecimal("1.50"))))).probe();
        assertEquals("no_gpu", software.unavailableReason());
        assertNull(software.score());
        assertNull(software.frames());

        GpuGraphicsResult measured = probe(temp.resolve("missing"), List.of(
                new Scripted(GraphicsAttempt.measured(
                        "AMD Radeon example", 32, new BigDecimal("0.80"))))).probe();
        assertEquals("measured", measured.status());
        assertEquals("AMD Radeon example", measured.device());
        assertEquals(32, measured.frames());
        assertEquals(new BigDecimal("0.80"), measured.frameTimeMs());
        assertEquals(new BigDecimal("0.80"), measured.score());
        assertFalse(String.valueOf(measured.score()).contains("3DMark"));
    }

    @Test
    void publicNamesDoNotClaimAProductScore() {
        String blob = GpuGraphicsResult.PROBE_NAME + " " + GpuGraphicsResult.SCORE_UNIT;
        String lower = blob.toLowerCase();
        assertFalse(lower.contains("3dmark"));
        assertFalse(lower.contains("ul solutions"));
        assertFalse(lower.contains("certified"));
        assertFalse(lower.contains("certification"));
    }

    @Test
    void liveProbeDoesNotInventAScore() {
        GpuGraphicsResult result = new LocalGpuGraphicsProbe(new ProcessHostCommandExecutor()).probe();
        String blob = (result.status() + " " + result.device() + " " + result.detail()
                + " " + result.frames() + " " + result.frameTimeMs() + " " + result.score()).toLowerCase();
        assertFalse(blob.contains("3dmark"));
        assertFalse(blob.contains("ul solutions"));
        assertFalse(blob.contains("certified"));
        assertFalse(blob.contains("certification"));
        if ("measured".equals(result.status())) {
            assertEquals(GpuGraphicsScores.FRAMES, result.frames());
            assertFalse(result.device().isBlank());
            assertFalse(GpuText.software(result.device()));
            assertEquals(result.frameTimeMs(), result.score());
            assertTrue(result.score().signum() > 0);
            assertNull(result.unavailableReason());
            assertEquals("ms", result.scoreUnit());
        } else {
            assertEquals("unavailable", result.status());
            assertNull(result.score());
            assertNull(result.frameTimeMs());
            assertNull(result.frames());
            assertNull(result.scoreUnit());
            assertTrue("no_gpu".equals(result.unavailableReason())
                    || "probe_failed".equals(result.unavailableReason()));
            if ("no_gpu".equals(result.unavailableReason())) {
                assertNull(result.device());
            }
        }
    }

    private static long offset(MemoryLayout layout, String name) {
        return layout.byteOffset(MemoryLayout.PathElement.groupElement(name));
    }

    private LocalGpuGraphicsProbe probe(Path sysfs, List<GraphicsApiProbe> apis) {
        return new LocalGpuGraphicsProbe(new GpuDeviceDiscovery(new FakeCommands(), sysfs), apis);
    }

    private static void writeCard(Path card, String vendor, String device, String driver) throws Exception {
        Path deviceDir = card.resolve("device");
        Files.createDirectories(deviceDir);
        Files.writeString(deviceDir.resolve("vendor"), vendor + "\n");
        Files.writeString(deviceDir.resolve("device"), device + "\n");
        Files.writeString(deviceDir.resolve("uevent"), "DRIVER=" + driver + "\n");
    }

    private static final class Scripted implements GraphicsApiProbe {
        private final GraphicsAttempt attempt;

        private Scripted(GraphicsAttempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public GraphicsAttempt attempt() {
            return attempt;
        }
    }

    private static final class FakeCommands implements HostCommandExecutor {
        @Override
        public HostCommandExecutor.Result run(List<String> command, Duration timeout) {
            return HostCommandExecutor.Result.failedToStart("missing");
        }
    }
}
