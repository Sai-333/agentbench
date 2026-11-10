# Build from repository root:
#   docker build -f deploy/operator/Dockerfile -t agentbench-operator:dev .
#
# Runtime: Operator Framework helm-operator reconciles AgentBenchInstance
# CRs by install/upgrade/uninstall of deploy/helm/agentbench.
FROM quay.io/operator-framework/helm-operator:v1.37.1

ENV HOME=/opt/helm
COPY deploy/operator/watches.yaml ${HOME}/watches.yaml
COPY deploy/helm/agentbench ${HOME}/helm-charts/agentbench
WORKDIR ${HOME}
