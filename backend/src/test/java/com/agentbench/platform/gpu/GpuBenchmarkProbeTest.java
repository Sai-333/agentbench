package com.agentbench.platform.gpu;

import com.agentbench.execution.service.HostCommandExecutor;
import com.agentbench.execution.service.ProcessHostCommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

class GpuBenchmarkProbeTest {

    @TempDir
    Path temp;

    @Test
    void measuredResultRequiresDeviceApiAndPositiveScore() {
        GpuBenchmarkResult measured = GpuBenchmarkResult.measured(
                "NVIDIA example", "CUDA", new BigDecimal("12.5"));
        assertEquals("measured", measured.status());
        assertEquals("CUDA", measured.api());
        assertEquals(new BigDecimal("12.50"), measured.score());
        assertEquals("mib_per_s", measured.scoreUnit());
        assertNull(measured.unavailableReason());
        assertThrows(IllegalArgumentException.class,
                () -> GpuBenchmarkResult.measured("GPU", "CUDA", null));
        assertThrows(IllegalArgumentException.class,
                () -> GpuBenchmarkResult.measured("GPU", "CUDA", BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuBenchmarkResult(
                        "unavailable", null, null, BigDecimal.ONE, "no_gpu", null));
        assertThrows(IllegalArgumentException.class,
                () -> GpuBenchmarkResult.unavailable("GPU", "no_gpu", null));
    }

    @Test
    void scoreRejectsAClockGlitchAndKeepsARealDuration() {
        assertNull(GpuScores.mibPerSec(0));
        assertNull(GpuScores.mibPerSec(-1));
        assertNull(GpuScores.mibPerSec(1_000L));
        assertEquals(new BigDecimal("64.00"), GpuScores.mibPerSec(1_000_000_000L));
    }

    @Test
    void softwareRendererNamesAreNotGpus() {
        assertTrue(GpuText.software("llvmpipe (LLVM 15, 256 bits)"));
        assertTrue(GpuText.software("lavapipe"));
        assertFalse(GpuText.software("NVIDIA GeForce RTX 4090"));
        assertFalse(GpuText.software("Intel UHD Graphics 770"));
    }

    @Test
    void vulkanLayoutsMatchTheLp64Abi() {
        assertEquals(48, VulkanLayouts.APPLICATION_INFO.byteSize());
        assertEquals(64, VulkanLayouts.INSTANCE_CREATE_INFO.byteSize());
        assertEquals(40, VulkanLayouts.DEVICE_QUEUE_CREATE_INFO.byteSize());
        assertEquals(72, VulkanLayouts.DEVICE_CREATE_INFO.byteSize());
        assertEquals(56, VulkanLayouts.BUFFER_CREATE_INFO.byteSize());
        assertEquals(32, VulkanLayouts.MEMORY_ALLOCATE_INFO.byteSize());
        assertEquals(24, VulkanLayouts.MEMORY_REQUIREMENTS.byteSize());
        assertEquals(24, VulkanLayouts.COMMAND_POOL_CREATE_INFO.byteSize());
        assertEquals(32, VulkanLayouts.COMMAND_BUFFER_ALLOCATE_INFO.byteSize());
        assertEquals(32, VulkanLayouts.COMMAND_BUFFER_BEGIN_INFO.byteSize());
        assertEquals(24, VulkanLayouts.BUFFER_COPY.byteSize());
        assertEquals(56, VulkanLayouts.BUFFER_MEMORY_BARRIER.byteSize());
        assertEquals(32, VulkanLayouts.BUFFER_MEMORY_BARRIER.byteOffset(
                java.lang.foreign.MemoryLayout.PathElement.groupElement("buffer")));
        assertEquals(72, VulkanLayouts.SUBMIT_INFO.byteSize());
        assertEquals(48, VulkanLayouts.SUBMIT_INFO.byteOffset(
                java.lang.foreign.MemoryLayout.PathElement.groupElement("pCommandBuffers")));
        assertEquals(24, VulkanLayouts.FENCE_CREATE_INFO.byteSize());
        assertEquals(24, VulkanLayouts.QUEUE_FAMILY.byteSize());
        assertFalse(VulkanLayouts.usableDeviceType(0));
        assertTrue(VulkanLayouts.usableDeviceType(1));
        assertTrue(VulkanLayouts.usableDeviceType(2));
        assertFalse(VulkanLayouts.usableDeviceType(3));
        assertFalse(VulkanLayouts.usableDeviceType(4));
    }

    @Test
    void discoverySkipsVirtualAdaptersAndRedactsSecrets() throws Exception {
        writeCard(temp.resolve("card0"), "0x1234", "0x1111", "bochs-drm");
        writeCard(temp.resolve("card1"), "0x1af4", "0x1050", "virtio-gpu");
        writeCard(temp.resolve("card2"), "0x10de", "0x2684", "nvidia");
        writeCard(temp.resolve("card3"), "0x8086", "0x56a5", "i915");
        Files.createDirectories(temp.resolve("card2-DP-1"));
        FakeCommands commands = new FakeCommands();
        commands.next = new HostCommandExecutor.Result(
                0,
                "sk-supersecretkey\n",
                "sk-stderrsecretkey\n",
                false,
                true);
        GpuDeviceDiscovery discovery = new GpuDeviceDiscovery(commands, temp);
        List<GpuDeviceDiscovery.Device> devices = discovery.discover();
        assertEquals(2, devices.size());
        assertFalse(devices.get(0).name().contains("sk-supersecretkey"));
        assertFalse(devices.get(0).name().contains("sk-stderrsecretkey"));
        assertTrue(devices.get(0).nvidiaFamily());
        assertEquals("Intel 56a5", devices.get(1).name());
        assertTrue(commands.last.contains("nvidia-smi"));
        assertFalse(String.join(" ", commands.last).contains("sk-"));
    }

    @Test
    void noGpuIsStoredWithoutAScore() {
        GpuBenchmarkResult result = probe(temp.resolve("missing"), List.of(
                new Scripted(GpuApiAttempt.absent("CUDA")),
                new Scripted(GpuApiAttempt.absent("Vulkan")),
                new Scripted(GpuApiAttempt.noDevice("OpenCL")))).probe();
        assertEquals("unavailable", result.status());
        assertEquals("no_gpu", result.unavailableReason());
        assertNull(result.score());
        assertNull(result.api());
        assertNull(result.device());
        assertNull(result.scoreUnit());
    }

    @Test
    void seenDeviceWithoutARunnableApiIsExplicitFailure() throws Exception {
        writeCard(temp.resolve("card0"), "0x10de", "0x2684", "nvidia");
        GpuBenchmarkResult result = probe(temp, List.of(
                new Scripted(GpuApiAttempt.absent("CUDA")),
                new Scripted(GpuApiAttempt.absent("Vulkan")),
                new Scripted(GpuApiAttempt.absent("OpenCL")))).probe();
        assertEquals("unavailable", result.status());
        assertEquals("probe_failed", result.unavailableReason());
        assertEquals("NVIDIA 2684", result.device());
        assertNull(result.score());
        assertNull(result.api());
    }

    @Test
    void firstRealMeasurementWinsAndSoftwareNamesDoNot() {
        GpuBenchmarkResult software = probe(temp.resolve("missing"), List.of(
                new Scripted(GpuApiAttempt.measured("Vulkan", "llvmpipe (LLVM 15, 256 bits)", new BigDecimal("99"))))).probe();
        assertEquals("no_gpu", software.unavailableReason());
        assertNull(software.score());

        GpuBenchmarkResult measured = probe(temp.resolve("missing"), List.of(
                new Scripted(GpuApiAttempt.absent("CUDA")),
                new Scripted(GpuApiAttempt.measured("Vulkan", "AMD Radeon example", new BigDecimal("80"))),
                new Scripted(GpuApiAttempt.measured("OpenCL", "Should not win", new BigDecimal("10"))))).probe();
        assertEquals("measured", measured.status());
        assertEquals("Vulkan", measured.api());
        assertEquals("AMD Radeon example", measured.device());
        assertEquals(new BigDecimal("80.00"), measured.score());
        assertFalse(String.valueOf(measured.score()).contains("3DMark"));
    }

    @Test
    void publicNamesDoNotClaimAProductScore() {
        String blob = GpuBenchmarkResult.PROBE_NAME + " " + String.join(" ", GpuBenchmarkResult.APIS);
        String lower = blob.toLowerCase();
        assertFalse(lower.contains("3dmark"));
        assertFalse(lower.contains("ul solutions"));
        assertFalse(lower.contains("certified"));
        assertFalse(lower.contains("certification"));
    }

    @Test
    void liveProbeDoesNotInventAScore() {
        GpuBenchmarkResult result = new LocalGpuBenchmarkProbe(new ProcessHostCommandExecutor()).probe();
        String blob = (result.status() + " " + result.device() + " " + result.api()
                + " " + result.detail() + " " + result.score()).toLowerCase();
        assertFalse(blob.contains("3dmark"));
        assertFalse(blob.contains("ul solutions"));
        assertFalse(blob.contains("certified"));
        assertFalse(blob.contains("certification"));
        if ("measured".equals(result.status())) {
            assertTrue(GpuBenchmarkResult.APIS.contains(result.api()));
            assertFalse(result.device().isBlank());
            assertFalse(GpuText.software(result.device()));
            assertTrue(result.score().signum() > 0);
            assertNull(result.unavailableReason());
            assertEquals("mib_per_s", result.scoreUnit());
        } else {
            assertEquals("unavailable", result.status());
            assertNull(result.score());
            assertNull(result.api());
            assertNull(result.scoreUnit());
            assertTrue("no_gpu".equals(result.unavailableReason())
                    || "probe_failed".equals(result.unavailableReason()));
            if ("no_gpu".equals(result.unavailableReason())) {
                assertNull(result.device());
            }
        }
    }

    private LocalGpuBenchmarkProbe probe(Path sysfs, List<GpuApiProbe> apis) {
        return new LocalGpuBenchmarkProbe(new GpuDeviceDiscovery(new FakeCommands(), sysfs), apis);
    }

    private static void writeCard(Path card, String vendor, String device, String driver) throws Exception {
        Path deviceDir = card.resolve("device");
        Files.createDirectories(deviceDir);
        Files.writeString(deviceDir.resolve("vendor"), vendor + "\n");
        Files.writeString(deviceDir.resolve("device"), device + "\n");
        Files.writeString(deviceDir.resolve("uevent"), "DRIVER=" + driver + "\n");
    }

    private static final class Scripted implements GpuApiProbe {
        private final GpuApiAttempt attempt;

        private Scripted(GpuApiAttempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public String api() {
            return attempt.api();
        }

        @Override
        public GpuApiAttempt attempt() {
            return attempt;
        }
    }

    private static final class FakeCommands implements HostCommandExecutor {
        private Result next = Result.failedToStart("missing");
        private List<String> last = List.of();

        @Override
        public Result run(List<String> command, Duration timeout) {
            last = command;
            return next;
        }
    }
}
