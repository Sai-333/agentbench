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
 * Picks the first API that actually measures a physical GPU. Software renderers
 * are ignored. When nothing can run, the result is unavailable and has no score.
 */
@Component
public class LocalGpuBenchmarkProbe implements GpuBenchmarkProbe {

    private static final Logger log = LoggerFactory.getLogger(LocalGpuBenchmarkProbe.class);

    private final GpuDeviceDiscovery discovery;
    private final List<GpuApiProbe> apis;

    @Autowired
    public LocalGpuBenchmarkProbe(HostCommandExecutor commands) {
        this(new GpuDeviceDiscovery(commands, Path.of("/sys/class/drm")), List.of(
                new CudaTransferProbe(),
                new VulkanTransferProbe(),
                new OpenClTransferProbe()));
    }

    LocalGpuBenchmarkProbe(GpuDeviceDiscovery discovery, List<GpuApiProbe> apis) {
        this.discovery = discovery;
        this.apis = List.copyOf(apis);
    }

    @Override
    public GpuBenchmarkResult probe() {
        try {
            return run();
        } catch (RuntimeException ex) {
            log.debug("GPU probe failed closed: {}", SecretRedactor.sanitizeError(ex.getMessage()));
            return GpuBenchmarkResult.unavailable(null, GpuBenchmarkResult.REASON_PROBE_FAILED, "probe_error");
        }
    }

    private GpuBenchmarkResult run() {
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
        for (GpuApiProbe api : apis) {
            GpuApiAttempt attempt;
            try {
                attempt = api.attempt();
            } catch (RuntimeException ex) {
                apiFailed = true;
                failedDetail = "probe_error";
                continue;
            }
            if (attempt.kind() == GpuApiAttempt.Kind.MEASURED && GpuText.software(attempt.device())) {
                continue;
            }
            if (attempt.kind() == GpuApiAttempt.Kind.MEASURED
                    && GpuScores.valid(attempt.score())
                    && GpuText.device(attempt.device()) != null
                    && GpuBenchmarkResult.APIS.contains(attempt.api())) {
                return GpuBenchmarkResult.measured(attempt.device(), attempt.api(), attempt.score());
            }
            if (attempt.kind() == GpuApiAttempt.Kind.FAILED) {
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
            return GpuBenchmarkResult.unavailable(null, GpuBenchmarkResult.REASON_NO_GPU, null);
        }
        String device = failedDevice != null ? failedDevice : discovered;
        String detail = failedDetail != null ? failedDetail : "api_unavailable";
        return GpuBenchmarkResult.unavailable(device, GpuBenchmarkResult.REASON_PROBE_FAILED, detail);
    }
}
