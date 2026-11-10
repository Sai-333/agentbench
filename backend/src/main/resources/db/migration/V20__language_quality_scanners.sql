-- Optional Python (ruff) + Node (eslint) quality scanners.
-- Checkstyle / SpotBugs / PMD remain primary for Java; Sonar optional; language scanners opt-in.
-- Single Flyway stream; forward-only; do not edit prior migrations.

COMMENT ON TABLE evaluations.quality_reports IS
    'Static analysis reports (Checkstyle/SpotBugs/PMD primary; optional Sonar / ruff / eslint when enabled).';

INSERT INTO platform.schema_marker (migration_label)
VALUES ('language_quality_scanners_optional');
