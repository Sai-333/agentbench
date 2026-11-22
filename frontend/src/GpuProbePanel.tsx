import type { GpuBenchmarkResult } from './api'
import { EMPTY } from './emptyCopy'
import { gpuReasonLabel, gpuScoreLabel } from './gpuProbe'

type LoadState = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

type Props = {
  allowed: boolean
  results: GpuBenchmarkResult[]
  state: LoadState
  error: string | null
  running: boolean
  onRun: () => void
  onRefresh: () => void
  onBackToOrgs?: () => void
}

function formatWhen(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  return date.toLocaleString()
}

export function GpuProbePanel({
  allowed,
  results,
  state,
  error,
  running,
  onRun,
  onRefresh,
  onBackToOrgs,
}: Props) {
  return (
    <>
      <h2>GPU probe</h2>
      <p className="muted small">
        Local probe on this API process. Each row names the device, the API that ran, and the
        transfer score. Not consumer / organization data. When no GPU is present, or the probe
        cannot run, the result is unavailable and has no score.
      </p>
      {onBackToOrgs ? (
        <button type="button" className="cta secondary" onClick={onBackToOrgs}>
          Back to organizations
        </button>
      ) : null}
      {!allowed ? (
        <p className="empty-state" role="status">
          {EMPTY.gpuProbeForbidden}
        </p>
      ) : (
        <>
          <button type="button" className="cta" onClick={onRun} disabled={running}>
            {running ? 'Running probe…' : 'Run probe'}
          </button>
          <button type="button" className="cta secondary" onClick={onRefresh} disabled={running}>
            Refresh
          </button>
          {error && <p className="error">{error}</p>}
          {state === 'loading' || state === 'idle' ? (
            <p className="muted">Loading GPU probes…</p>
          ) : state === 'empty' ? (
            <p className="empty-state" role="status">
              {EMPTY.gpuProbe}
            </p>
          ) : (
            <ul className="list">
              {results.map((row) => {
                const score = gpuScoreLabel(row)
                return (
                  <li key={row.id}>
                    <article className="detail-block gpu-result">
                      <p className="muted small">
                        {formatWhen(row.probedAt)} · {row.probe}
                      </p>
                      <p>
                        <span className="gpu-label">Device</span> {row.device ?? '—'}
                      </p>
                      <p>
                        <span className="gpu-label">API</span> {row.api ?? '—'}
                      </p>
                      <p>
                        <span className="gpu-label">Score</span>{' '}
                        {score == null ? 'Unavailable' : `${score} MiB/s`}
                      </p>
                      {score == null ? (
                        <p className="muted small">{gpuReasonLabel(row.unavailableReason)}</p>
                      ) : (
                        <p className="muted small">Local transfer measurement on the API above.</p>
                      )}
                    </article>
                  </li>
                )
              })}
            </ul>
          )}
        </>
      )}
    </>
  )
}
