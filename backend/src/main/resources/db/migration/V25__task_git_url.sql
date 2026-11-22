-- Task-level GitHub repository for a pinned commit.
-- Publishing a benchmark version copies URL, SHA, branch, digest, and storage URI
-- into the immutable snapshot. The evaluation worker checks that commit out.
-- Forward-only. Do not edit earlier migrations.

ALTER TABLE benchmarks.tasks
    ADD COLUMN git_url TEXT;

ALTER TABLE benchmarks.tasks
    ADD CONSTRAINT tasks_git_url_len CHECK (git_url IS NULL OR char_length(git_url) <= 500);

COMMENT ON COLUMN benchmarks.tasks.git_url IS
    'github.com owner/repo the worker checks out at baseline_commit_sha. Not fixture text.';
