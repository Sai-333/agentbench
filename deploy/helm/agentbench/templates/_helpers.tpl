{{/*
Expand the name of the chart.
*/}}
{{- define "agentbench.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "agentbench.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Chart label
*/}}
{{- define "agentbench.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "agentbench.labels" -}}
helm.sh/chart: {{ include "agentbench.chart" . }}
{{ include "agentbench.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: agentbench
{{- end }}

{{/*
Selector labels
*/}}
{{- define "agentbench.selectorLabels" -}}
app.kubernetes.io/name: {{ include "agentbench.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Namespace for resources
*/}}
{{- define "agentbench.namespace" -}}
{{- .Release.Namespace }}
{{- end }}

{{/*
ConfigMap name
*/}}
{{- define "agentbench.configMapName" -}}
{{ include "agentbench.fullname" . }}-config
{{- end }}

{{/*
Secret name (created or existing)
*/}}
{{- define "agentbench.secretName" -}}
{{- if .Values.secrets.existingSecret }}
{{- .Values.secrets.existingSecret }}
{{- else }}
{{- printf "%s-secrets" (include "agentbench.fullname" .) }}
{{- end }}
{{- end }}

{{/*
Backend fullname
*/}}
{{- define "agentbench.backend.fullname" -}}
{{ include "agentbench.fullname" . }}-backend
{{- end }}

{{/*
Worker fullname
*/}}
{{- define "agentbench.worker.fullname" -}}
{{ include "agentbench.fullname" . }}-worker
{{- end }}

{{/*
Frontend fullname
*/}}
{{- define "agentbench.frontend.fullname" -}}
{{ include "agentbench.fullname" . }}-frontend
{{- end }}

{{/*
Postgres service host when in-cluster deps enabled
*/}}
{{- define "agentbench.postgres.host" -}}
{{- if .Values.dependencies.postgres.enabled }}
{{- printf "%s-postgres" (include "agentbench.fullname" .) }}
{{- else }}
{{- /* host embedded in config.datasourceUrl by operator */ -}}
{{- end }}
{{- end }}

{{/*
mTLS secret names (cert-manager or openssl script)
*/}}
{{- define "agentbench.mtls.caSecretName" -}}
{{- default (printf "%s-mtls-ca" (include "agentbench.fullname" .)) .Values.mtls.secrets.ca }}
{{- end }}

{{- define "agentbench.mtls.backendSecretName" -}}
{{- default (printf "%s-mtls-backend" (include "agentbench.fullname" .)) .Values.mtls.secrets.backend }}
{{- end }}

{{- define "agentbench.mtls.workerSecretName" -}}
{{- default (printf "%s-mtls-worker" (include "agentbench.fullname" .)) .Values.mtls.secrets.worker }}
{{- end }}

{{- define "agentbench.mtls.frontendSecretName" -}}
{{- default (printf "%s-mtls-frontend" (include "agentbench.fullname" .)) .Values.mtls.secrets.frontend }}
{{- end }}

{{- define "agentbench.mtls.mountPath" -}}
{{- default "/var/agentbench/tls" .Values.mtls.mountPath }}
{{- end }}

{{/*
Spring Boot PEM SSL env block for API/worker when mtls.enabled
*/}}
{{- define "agentbench.mtls.springSslEnv" -}}
- name: SERVER_SSL_ENABLED
  value: "true"
- name: SERVER_SSL_CERTIFICATE
  value: {{ printf "file:%s/tls.crt" (include "agentbench.mtls.mountPath" .) | quote }}
- name: SERVER_SSL_CERTIFICATE_PRIVATE_KEY
  value: {{ printf "file:%s/tls.key" (include "agentbench.mtls.mountPath" .) | quote }}
- name: SERVER_SSL_TRUST_CERTIFICATE
  value: {{ printf "file:%s/ca.crt" (include "agentbench.mtls.mountPath" .) | quote }}
- name: SERVER_SSL_CLIENT_AUTH
  value: {{ .Values.mtls.clientAuth | quote }}
{{- end }}
