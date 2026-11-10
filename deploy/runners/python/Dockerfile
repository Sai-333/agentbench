# AgentBench pytest suite runner + optional ruff quality scanner image.
# Build: docker build -t agentbench/runner-python:3.12 deploy/runners/python
# Used via AGENTBENCH_PYTHON_RUNNER_IMAGE / agentbench.execution.python-image.
# Network is off by default in RuntimeIsolator — pytest/ruff must be preinstalled.
FROM python:3.12-alpine

RUN apk add --no-cache bash \
    && pip install --no-cache-dir "pytest==8.3.5" "ruff==0.9.10" \
    && adduser -D -u 65534 -g 65534 -H nobody 2>/dev/null || true

WORKDIR /workspace
USER 65534:65534

# Default entry is overridden by RuntimeIsolator command argv.
CMD ["pytest", "--version"]
