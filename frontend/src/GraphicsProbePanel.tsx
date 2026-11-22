import type { GpuGraphicsResult } from './api'
import { EMPTY } from './emptyCopy'
import { graphicsFrameTimeLabel, graphicsFramesLabel, graphicsScoreLabel } from './gpuGraphics'
import { gpuReasonLabel } from './gpuProbe'

type LoadState = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

type Props = {
  allowed: boolean
  results: GpuGraphicsResult[]
  state: LoadState
  error: string | null
  running: boolean
  onRun: () => void
  onRefresh: () => void
}

function formatWhen(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  return date.toLocaleString()
}

export function GraphicsProbePanel({
  allowed,
  results,
  state,
  error,
  running,
  onRun,
  onRefresh,
}: Props) {
  if (!allowed) {
    return null
  }
  return (
    <>
      <h2>Graphics frame time</h2>
      <p className="muted small">
        Local graphics probe on this API process. Each row stores the device, the frame count, and
        the average frame time. When no GPU is present, or the probe cannot run, the result is
        unavailable and has no score.
      </p>
      <button type="button" className="cta" onClick={onRun} disabled={running}>
        {running ? 'Running graphics probe…' : 'Run graphics probe'}
      </button>
      <button type="button" className="cta secondary" onClick={onRefresh} disabled={running}>
        Refresh graphics
      </button>
      {error && <p className="error">{error}</p>}
      {state === 'loading' || state === 'idle' ? (
        <p className="muted">Loading graphics results…</p>
      ) : state === 'empty' ? (
        <p className="empty-state" role="status">
          {EMPTY.gpuGraphics}
        </p>
      ) : (
        <ul className="list">
          {results.map((row) => {
            const score = graphicsScoreLabel(row)
            const frames = graphicsFramesLabel(row)
            const frameTime = graphicsFrameTimeLabel(row)
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
                    <span className="gpu-label">Frames</span> {frames ?? '—'}
                  </p>
                  <p>
                    <span className="gpu-label">Frame time</span>{' '}
                    {frameTime == null ? '—' : `${frameTime} ms`}
                  </p>
                  <p>
                    <span className="gpu-label">Score</span>{' '}
                    {score == null ? 'Unavailable' : `${score} ms`}
                  </p>
                  {score == null ? (
                    <p className="muted small">{gpuReasonLabel(row.unavailableReason)}</p>
                  ) : (
                    <p className="muted small">Average frame time of a small local clear.</p>
                  )}
                </article>
              </li>
            )
          })}
        </ul>
      )}
    </>
  )
}
