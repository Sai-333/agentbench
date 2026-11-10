# AgentBench Jest suite runner + optional eslint quality scanner image.
# Build: docker build -t agentbench/runner-node:22 deploy/runners/node
# Used via AGENTBENCH_NODE_RUNNER_IMAGE / agentbench.execution.node-image.
# Network is off by default in RuntimeIsolator — jest/eslint must be preinstalled.
FROM node:22-alpine

WORKDIR /opt/agentbench-runner

# Global jest + junit reporter + eslint so workspaces without node_modules still run.
COPY eslint.config.mjs /opt/agentbench-runner/eslint.config.mjs
RUN npm install -g jest@29.7.0 jest-junit@16.0.0 eslint@9.22.0 \
    && mkdir -p /tmp/npm-cache \
    && chown -R 65534:65534 /tmp/npm-cache /opt/agentbench-runner

ENV PATH="/usr/local/lib/node_modules/.bin:${PATH}"
ENV npm_config_cache=/tmp/npm-cache
ENV NPM_CONFIG_CACHE=/tmp/npm-cache
ENV JEST_JUNIT_OUTPUT_DIR=reports
ENV JEST_JUNIT_OUTPUT_NAME=junit.xml

WORKDIR /workspace
USER 65534:65534

CMD ["jest", "--version"]
