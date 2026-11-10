-- Optional SonarQube/SonarCloud quality path (report import or Web API).
-- Checkstyle / SpotBugs / PMD remain primary; Sonar is never sole scoring authority.
-- Single Flyway stream; forward-only; do not edit prior migrations.

COMMENT ON TABLE evaluations.quality_reports IS
    'Static analysis reports (Checkstyle/SpotBugs/PMD primary; optional Sonar when enabled).';

INSERT INTO platform.schema_marker (migration_label)
VALUES ('sonar_optional_quality');
