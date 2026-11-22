-- Local GPU probe results (device, API, score). Not tenant data.
-- Single Flyway stream; forward-only; do not edit V1–V25.
--
-- No org_id and no RLS policy. Callers are OWNER or ADMIN of at least one
-- organization, same platform check as regression runs.
-- A measured row has a device, an API the probe actually used, and a positive
-- score. An unavailable row has a null score. CI does not require a GPU.

CREATE TABLE platform.gpu_benchmark_results (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status               TEXT NOT NULL,
    device_name          TEXT,
    api_name             TEXT,
    score                NUMERIC(14, 2),
    score_unit           TEXT,
    unavailable_reason   TEXT,
    probe_name           TEXT NOT NULL,
    detail               TEXT,
    probed_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    probed_by            UUID REFERENCES identity.users (id) ON DELETE SET NULL,
    CONSTRAINT gpu_benchmark_status_check CHECK (status IN ('measured', 'unavailable')),
    CONSTRAINT gpu_benchmark_probe_name_check CHECK (probe_name = 'agentbench-gpu-probe-v1'),
    CONSTRAINT gpu_benchmark_device_len CHECK (
        device_name IS NULL OR char_length(device_name) BETWEEN 1 AND 200
    ),
    CONSTRAINT gpu_benchmark_api_check CHECK (
        api_name IS NULL OR api_name IN ('CUDA', 'Vulkan', 'OpenCL')
    ),
    CONSTRAINT gpu_benchmark_unit_check CHECK (
        score_unit IS NULL OR score_unit = 'mib_per_s'
    ),
    CONSTRAINT gpu_benchmark_reason_check CHECK (
        unavailable_reason IS NULL OR unavailable_reason IN ('no_gpu', 'probe_failed')
    ),
    CONSTRAINT gpu_benchmark_detail_len CHECK (
        detail IS NULL OR char_length(detail) <= 300
    ),
    CONSTRAINT gpu_benchmark_score_range CHECK (
        score IS NULL OR (score > 0 AND score <= 1000000)
    ),
    CONSTRAINT gpu_benchmark_shape_check CHECK (
        (
            status = 'measured'
            AND device_name IS NOT NULL
            AND api_name IS NOT NULL
            AND score IS NOT NULL
            AND score_unit = 'mib_per_s'
            AND unavailable_reason IS NULL
        )
        OR
        (
            status = 'unavailable'
            AND score IS NULL
            AND score_unit IS NULL
            AND api_name IS NULL
            AND unavailable_reason IS NOT NULL
            AND (unavailable_reason <> 'no_gpu' OR device_name IS NULL)
        )
    )
);

COMMENT ON TABLE platform.gpu_benchmark_results IS
    'Platform-scoped local GPU probe (not org-scoped, no RLS). Score is MiB/s from the probe that ran. Unavailable rows store a null score.';

CREATE INDEX gpu_benchmark_probed_idx
    ON platform.gpu_benchmark_results (probed_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON platform.gpu_benchmark_results TO agentbench_app;

INSERT INTO platform.schema_marker (migration_label)
VALUES ('gpu_benchmark');
