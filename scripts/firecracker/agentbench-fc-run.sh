#!/usr/bin/env bash
# AgentBench Firecracker wrapper (documented RuntimeIsolator entry point).
#
# Install as `agentbench-fc-run` on PATH (symlink or copy), or set:
#   AGENTBENCH_FIRECRACKER_WRAPPER=/path/to/agentbench-fc-run.sh
#
# Requirements for a live microVM run:
#   - Linux host with KVM (/dev/kvm)
#   - `firecracker` binary (and optionally `jailer`)
#   - Kernel + rootfs configured via AGENTBENCH_FC_KERNEL / AGENTBENCH_FC_ROOTFS
#
# Mac Compose and default CI stay on AGENTBENCH_EXECUTION_MODE=docker|local.
# Never claim a live microVM without Firecracker+KVM detection (Java probes first).
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  agentbench-fc-run --help
  agentbench-fc-run cleanup --name <run-name>
  agentbench-fc-run run --name <n> --workspace <dir> --memory-mb <n> --cpus <x> \
      --pids-limit <n> --network none|bridge --timeout-sec <n> --user <uid:gid> \
      --firecracker <bin> --kvm-device <path> [--use-jailer] [--jailer <bin>] \
      [--hidden-fixtures <dir>] [--read-only-rootfs] --image <ref> \
      [--env KEY=VAL ...] -- <command...>

Environment (live VM assets):
  AGENTBENCH_FC_KERNEL   path to guest kernel (vmlinux)
  AGENTBENCH_FC_ROOTFS   path to ext4 rootfs image
  AGENTBENCH_FC_WORKDIR  scratch dir for socket/config (default: /tmp/agentbench-fc)

This script is the documented contract between AgentBench and Firecracker/jailer.
EOF
}

die() { echo "agentbench-fc-run: $*" >&2; exit 1; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

cmd="${1:-}"
if [[ -z "$cmd" || "$cmd" == "--help" || "$cmd" == "-h" || "$cmd" == "help" ]]; then
  usage
  exit 0
fi

if [[ "$cmd" == "cleanup" ]]; then
  shift
  name=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --name) name="${2:-}"; shift 2 ;;
      *) die "unknown cleanup arg: $1" ;;
    esac
  done
  [[ -n "$name" ]] || die "cleanup requires --name"
  work="${AGENTBENCH_FC_WORKDIR:-/tmp/agentbench-fc}"
  rm -rf "${work}/${name}" 2>/dev/null || true
  # Best-effort: kill any leftover firecracker started with this run name in argv.
  if command -v pkill >/dev/null 2>&1; then
    pkill -f "firecracker.*${name}" 2>/dev/null || true
  fi
  exit 0
fi

if [[ "$cmd" != "run" ]]; then
  die "unknown command '$cmd' (expected run|cleanup|--help)"
fi
shift

NAME=""
WORKSPACE=""
MEMORY_MB="512"
CPUS="1.00"
PIDS_LIMIT="128"
NETWORK="none"
TIMEOUT_SEC="120"
USER_SPEC="65534:65534"
FIRECRACKER_BIN="firecracker"
JAILER_BIN="jailer"
KVM_DEVICE="/dev/kvm"
USE_JAILER=0
HIDDEN=""
READ_ONLY=0
IMAGE=""
ENV_VARS=()
GUEST_CMD=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --name) NAME="${2:-}"; shift 2 ;;
    --workspace) WORKSPACE="${2:-}"; shift 2 ;;
    --memory-mb) MEMORY_MB="${2:-}"; shift 2 ;;
    --cpus) CPUS="${2:-}"; shift 2 ;;
    --pids-limit) PIDS_LIMIT="${2:-}"; shift 2 ;;
    --network) NETWORK="${2:-}"; shift 2 ;;
    --timeout-sec) TIMEOUT_SEC="${2:-}"; shift 2 ;;
    --user) USER_SPEC="${2:-}"; shift 2 ;;
    --firecracker) FIRECRACKER_BIN="${2:-}"; shift 2 ;;
    --jailer) JAILER_BIN="${2:-}"; shift 2 ;;
    --kvm-device) KVM_DEVICE="${2:-}"; shift 2 ;;
    --use-jailer) USE_JAILER=1; shift ;;
    --hidden-fixtures) HIDDEN="${2:-}"; shift 2 ;;
    --read-only-rootfs) READ_ONLY=1; shift ;;
    --image) IMAGE="${2:-}"; shift 2 ;;
    --env) ENV_VARS+=("${2:-}"); shift 2 ;;
    --) shift; GUEST_CMD=("$@"); break ;;
    *) die "unknown run arg: $1" ;;
  esac
done

[[ -n "$NAME" ]] || die "--name required"
[[ -n "$WORKSPACE" ]] || die "--workspace required"
[[ -d "$WORKSPACE" ]] || die "workspace not a directory: $WORKSPACE"
[[ ${#GUEST_CMD[@]} -gt 0 ]] || die "guest command required after --"
[[ -c "$KVM_DEVICE" ]] || die "KVM device missing or not a character device: $KVM_DEVICE (need Linux+KVM)"
require_cmd "$FIRECRACKER_BIN"
if [[ "$USE_JAILER" -eq 1 ]]; then
  require_cmd "$JAILER_BIN"
fi

KERNEL="${AGENTBENCH_FC_KERNEL:-}"
ROOTFS="${AGENTBENCH_FC_ROOTFS:-}"
[[ -n "$KERNEL" && -f "$KERNEL" ]] || die "set AGENTBENCH_FC_KERNEL to a guest kernel (vmlinux) for live microVM runs"
[[ -n "$ROOTFS" && -f "$ROOTFS" ]] || die "set AGENTBENCH_FC_ROOTFS to an ext4 rootfs image for live microVM runs"

WORKDIR="${AGENTBENCH_FC_WORKDIR:-/tmp/agentbench-fc}/${NAME}"
mkdir -p "$WORKDIR"
API_SOCK="${WORKDIR}/firecracker.sock"
CONFIG_JSON="${WORKDIR}/vm_config.json"
rm -f "$API_SOCK"

# Minimal Firecracker config: boot kernel/rootfs, mem/vcpu from isolator, no docker.sock.
# Guest command + workspace mount strategy is host/operator-specific; this reference
# passes the command via boot-args for operators who bake a matching init.
BOOT_ARGS="console=ttyS0 reboot=k panic=1 pci=off"
BOOT_ARGS+=" agentbench.workspace=${WORKSPACE}"
BOOT_ARGS+=" agentbench.image=${IMAGE}"
BOOT_ARGS+=" agentbench.user=${USER_SPEC}"
BOOT_ARGS+=" agentbench.network=${NETWORK}"
BOOT_ARGS+=" agentbench.pids_limit=${PIDS_LIMIT}"
BOOT_ARGS+=" agentbench.read_only_rootfs=${READ_ONLY}"
if [[ -n "$HIDDEN" ]]; then
  BOOT_ARGS+=" agentbench.hidden=${HIDDEN}"
fi
for ev in "${ENV_VARS[@]+"${ENV_VARS[@]}"}"; do
  BOOT_ARGS+=" agentbench.env.${ev}"
done
# Encode guest argv as a single boot arg (space-safe via base64 when available).
if command -v base64 >/dev/null 2>&1; then
  CMD_B64=$(printf '%s\0' "${GUEST_CMD[@]}" | base64 -w0 2>/dev/null || printf '%s\0' "${GUEST_CMD[@]}" | base64)
  BOOT_ARGS+=" agentbench.cmd_b64=${CMD_B64}"
else
  BOOT_ARGS+=" agentbench.cmd=${GUEST_CMD[*]}"
fi

VCPU_COUNT=$(python3 - <<PY
cpus=float("${CPUS}")
print(max(1, int(cpus + 0.999)))
PY
)

cat >"$CONFIG_JSON" <<EOF
{
  "boot-source": {
    "kernel_image_path": "${KERNEL}",
    "boot_args": "${BOOT_ARGS}"
  },
  "drives": [
    {
      "drive_id": "rootfs",
      "path_on_host": "${ROOTFS}",
      "is_root_device": true,
      "is_read_only": false
    }
  ],
  "machine-config": {
    "vcpu_count": ${VCPU_COUNT},
    "mem_size_mib": ${MEMORY_MB}
  }
}
EOF

echo "agentbench-fc-run: starting Firecracker name=${NAME} mem=${MEMORY_MB}MiB vcpus=${VCPU_COUNT} jailer=${USE_JAILER}" >&2

FC_ARGV=()
if [[ "$USE_JAILER" -eq 1 ]]; then
  # Jailer chroot/uid mapping is environment-specific; pass through api-sock + config.
  FC_ARGV=(
    "$JAILER_BIN" --id "$NAME" --exec-file "$FIRECRACKER_BIN" --uid 0 --gid 0
    --chroot-base-dir "${AGENTBENCH_FC_WORKDIR:-/tmp/agentbench-fc}" --
    --api-sock "$API_SOCK" --config-file "$CONFIG_JSON"
  )
else
  FC_ARGV=("$FIRECRACKER_BIN" --api-sock "$API_SOCK" --config-file "$CONFIG_JSON")
fi

# Enforce isolator timeout around the VMM process when GNU timeout is available.
set +e
if command -v timeout >/dev/null 2>&1; then
  timeout --signal=KILL "${TIMEOUT_SEC}s" "${FC_ARGV[@]}"
  rc=$?
else
  "${FC_ARGV[@]}"
  rc=$?
fi
set -e
rm -rf "$WORKDIR" 2>/dev/null || true
exit "$rc"
