-- Local GPU graphics frame-time results (device, frames, frame time). Not tenant data.
-- Single Flyway stream; forward-only; do not edit V1–V26.
--
-- No org_id and no RLS policy. Callers are OWNER or ADMIN of at least one
-- organization, same platform check as the transfer probe.
-- A measured row stores the device, 32 timed frames, and the average frame
-- time in milliseconds. That average is the score. An unavailable row has a
-- null score. CI does not require a GPU.

CREATE TABLE platform.gpu_graphics_results (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status               TEXT NOT NULL,
    device_name          TEXT,
    frames               INTEGER,
    frame_time_ms        NUMERIC(8, 2),
    score                NUMERIC(8, 2),
    score_unit           TEXT,
    unavailable_reason   TEXT,
    probe_name           TEXT NOT NULL,
    detail               TEXT,
    probed_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    probed_by            UUID REFERENCES identity.users (id) ON DELETE SET NULL,
    CONSTRAINT gpu_graphics_status_check CHECK (status IN ('measured', 'unavailable')),
    CONSTRAINT gpu_graphics_probe_name_check CHECK (probe_name = 'agentbench-gpu-graphics-v1'),
    CONSTRAINT gpu_graphics_device_len CHECK (
        device_name IS NULL OR char_length(device_name) BETWEEN 1 AND 200
    ),
    CONSTRAINT gpu_graphics_frames_check CHECK (
        frames IS NULL OR frames = 32
    ),
    CONSTRAINT gpu_graphics_unit_check CHECK (
        score_unit IS NULL OR score_unit = 'ms'
    ),
    CONSTRAINT gpu_graphics_reason_check CHECK (
        unavailable_reason IS NULL OR unavailable_reason IN ('no_gpu', 'probe_failed')
    ),
    CONSTRAINT gpu_graphics_detail_len CHECK (
        detail IS NULL OR char_length(detail) <= 300
    ),
    CONSTRAINT gpu_graphics_score_range CHECK (
        score IS NULL OR (score > 0 AND score <= 10000)
    ),
    CONSTRAINT gpu_graphics_score_match CHECK (
        (score IS NULL AND frame_time_ms IS NULL)
        OR score = frame_time_ms
    ),
    CONSTRAINT gpu_graphics_shape_check CHECK (
        (
            status = 'measured'
            AND device_name IS NOT NULL
            AND frames = 32
            AND frame_time_ms IS NOT NULL
            AND score = frame_time_ms
            AND score_unit = 'ms'
            AND unavailable_reason IS NULL
        )
        OR
        (
            status = 'unavailable'
            AND score IS NULL
            AND frame_time_ms IS NULL
            AND frames IS NULL
            AND score_unit IS NULL
            AND unavailable_reason IS NOT NULL
            AND (unavailable_reason <> 'no_gpu' OR device_name IS NULL)
        )
    )
);

COMMENT ON TABLE platform.gpu_graphics_results IS
    'Platform-scoped local graphics frame-time probe (not org-scoped, no RLS). Score is average frame time in milliseconds. Unavailable rows store a null score.';

CREATE INDEX gpu_graphics_probed_idx
    ON platform.gpu_graphics_results (probed_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON platform.gpu_graphics_results TO agentbench_app;

INSERT INTO platform.schema_marker (migration_label)
VALUES ('gpu_graphics');
