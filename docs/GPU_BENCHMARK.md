# GPU probe

A platform-scoped record of a **local** GPU probe on the API process that served the request. These rows are not consumer / organization data. They have no `org_id` and no Postgres RLS policy.

The probe names the device, the API that actually ran, and a numeric transfer score. The APIs are `CUDA`, `Vulkan`, and `OpenCL`. The score is mebibytes per second of an 8 MiB buffer moved in both directions, timed on that API. CUDA and OpenCL time driver copies between host and device. Vulkan times a device copy of the same size. The number is only meaningful together with the recorded API.

This is not a 3DMark score, not a UL score, and not a certification.

CI does not install a GPU or a driver. A machine with no GPU, or a probe that cannot run, stores `status: unavailable`, a null `score`, and a null `api`. `unavailableReason` is `no_gpu` or `probe_failed`. The product does not fill in a number for that case.

Software renderers (`llvmpipe`, `lavapipe`, and the same family) and virtual display adapters are not scored.

## Who can run and read

Same check as regression runs: the caller is authenticated and holds **OWNER or ADMIN on at least one organization**.

| Caller | Run, list, detail |
|---|---|
| No token | 401 |
| Signed in, no org, or only DEVELOPER / VIEWER | 403 |
| OWNER or ADMIN of any org | allowed |

`POST /api/v1/gpu-benchmarks` runs the probe and stores one row. `GET /api/v1/gpu-benchmarks` lists the newest rows. `GET /api/v1/gpu-benchmarks/{id}` returns one row.

Shape of a measured row (the numbers below illustrate the fields; they are not a recorded run):

```json
{
  "status": "measured",
  "device": "NVIDIA GeForce RTX 4090",
  "api": "CUDA",
  "score": 18420.50,
  "scoreUnit": "mib_per_s",
  "unavailableReason": null,
  "probe": "agentbench-gpu-probe-v1"
}
```

A machine with no GPU:

```json
{
  "status": "unavailable",
  "device": null,
  "api": null,
  "score": null,
  "scoreUnit": null,
  "unavailableReason": "no_gpu",
  "probe": "agentbench-gpu-probe-v1"
}
```

The UI entry is the top-bar **GPU probe** control, next to **Regression runs**. The score on that page is the stored row. It is not an evaluation weighted total.

The JVM needs `--enable-preview` (JDK 21.0.13 still marks `java.lang.foreign` as preview) and `--enable-native-access=ALL-UNNAMED` before it can call a driver library. The backend image sets both flags. Without them, a present GPU is stored as `probe_failed` and still has no invented score.

Mac Compose does not gain a GPU. A GPU attached only to the worker is not what this probe measures.

## Graphics frame time

A second platform-scoped result, separate from the transfer score. `POST /api/v1/gpu-graphics` runs a small Vulkan clear on this API process: 32 timed frames of a 64×64 color target, after a short warmup. The row stores the device, the frame count, and the average frame time in milliseconds. That average is the score (`scoreUnit: ms`).

This is not a 3DMark score, not a UL score, and not a certification.

CI does not install a GPU or a driver. A machine with no GPU, or a probe that cannot run, stores `status: unavailable`, a null `score`, a null `frameTimeMs`, and a null `frames`. `unavailableReason` is `no_gpu` or `probe_failed`. The product does not fill in a number for that case. Software renderers are not scored.

`GET /api/v1/gpu-graphics` lists the newest rows. `GET /api/v1/gpu-graphics/{id}` returns one row. The caller check is the same OWNER or ADMIN rule as the transfer probe.

Shape of a measured row (the numbers below illustrate the fields; they are not a recorded run):

```json
{
  "status": "measured",
  "device": "NVIDIA GeForce RTX 4090",
  "frames": 32,
  "frameTimeMs": 1.25,
  "score": 1.25,
  "scoreUnit": "ms",
  "unavailableReason": null,
  "probe": "agentbench-gpu-graphics-v1"
}
```

A machine with no GPU:

```json
{
  "status": "unavailable",
  "device": null,
  "frames": null,
  "frameTimeMs": null,
  "score": null,
  "scoreUnit": null,
  "unavailableReason": "no_gpu",
  "probe": "agentbench-gpu-graphics-v1"
}
```

The same top-bar **GPU probe** page shows this result under **Graphics frame time**. The number on that page is the stored row. It is not an evaluation weighted total.
