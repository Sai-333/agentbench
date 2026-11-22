package com.agentbench.platform.gpu;

import com.agentbench.agents.infra.SecretRedactor;
import com.agentbench.execution.service.HostCommandExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Times a small graphics clear on the first physical GPU that can run it.
 * Software renderers are ignored. When nothing can run, the result is
 * unavailable and has no score.
 */
@Component
public class LocalGpuGraphicsProbe implements GpuGraphicsProbe {

    private static final Logger log = LoggerFactory.getLogger(LocalGpuGraphicsProbe.class);

    private final GpuDeviceDiscovery discovery;
    private final List<GraphicsApiProbe> apis;

    @Autowired
    public LocalGpuGraphicsProbe(HostCommandExecutor commands) {
        this(new GpuDeviceDiscovery(commands, Path.of("/sys/class/drm")), List.of(new VulkanGraphicsProbe()));
    }

    LocalGpuGraphicsProbe(GpuDeviceDiscovery discovery, List<GraphicsApiProbe> apis) {
        this.discovery = discovery;
        this.apis = List.copyOf(apis);
    }

    @Override
    public GpuGraphicsResult probe() {
        try {
            return run();
        } catch (RuntimeException ex) {
            log.debug("GPU graphics probe failed closed: {}", SecretRedactor.sanitizeError(ex.getMessage()));
            return GpuGraphicsResult.unavailable(null, GpuGraphicsResult.REASON_PROBE_FAILED, "probe_error");
        }
    }

    private GpuGraphicsResult run() {
        List<GpuDeviceDiscovery.Device> devices = discovery.discover();
        String discovered = null;
        for (GpuDeviceDiscovery.Device device : devices) {
            if (device.name() != null && !GpuText.software(device.name())) {
                discovered = device.name();
                break;
            }
        }
        boolean apiFailed = false;
        String failedDevice = null;
        String failedDetail = null;
        for (GraphicsApiProbe api : apis) {
            GraphicsAttempt attempt;
            try {
                attempt = api.attempt();
            } catch (RuntimeException ex) {
                apiFailed = true;
                failedDetail = "probe_error";
                continue;
            }
            if (attempt.kind() == GraphicsAttempt.Kind.MEASURED && GpuText.software(attempt.device())) {
                continue;
            }
            if (attempt.kind() == GraphicsAttempt.Kind.MEASURED
                    && attempt.frames() != null
                    && attempt.frames() == GpuGraphicsScores.FRAMES
                    && GpuGraphicsScores.valid(attempt.frameTimeMs())
                    && GpuText.device(attempt.device()) != null) {
                return GpuGraphicsResult.measured(
                        attempt.device(), attempt.frames(), attempt.frameTimeMs());
            }
            if (attempt.kind() == GraphicsAttempt.Kind.MEASURED) {
                apiFailed = true;
                if (failedDetail == null) {
                    failedDetail = "graphics_timing";
                }
                String named = GpuText.device(attempt.device());
                if (failedDevice == null && named != null && !GpuText.software(named)) {
                    failedDevice = named;
                }
            }
            if (attempt.kind() == GraphicsAttempt.Kind.FAILED) {
                apiFailed = true;
                if (failedDetail == null) {
                    failedDetail = attempt.detail();
                }
                String named = GpuText.device(attempt.device());
                if (failedDevice == null && named != null && !GpuText.software(named)) {
                    failedDevice = named;
                }
            }
        }
        if (!apiFailed && discovered == null) {
            return GpuGraphicsResult.unavailable(null, GpuGraphicsResult.REASON_NO_GPU, null);
        }
        String device = failedDevice != null ? failedDevice : discovered;
        String detail = failedDetail != null ? failedDetail : "api_unavailable";
        return GpuGraphicsResult.unavailable(device, GpuGraphicsResult.REASON_PROBE_FAILED, detail);
    }
}
