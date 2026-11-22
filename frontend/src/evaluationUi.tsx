import type { EvaluationDetail, EvaluationScore } from './api'
import { buildStageTimeline } from './evaluationStages'
import { weightedScoreBars } from './scoreBars'
import { evaluationStatusTone } from './statusChip'

export function StatusChip({ status }: { status: string }) {
  const tone = evaluationStatusTone(status)
  return (
    <span className="badge status-chip" data-tone={tone} data-status={status}>
      {status}
    </span>
  )
}

export function EmptyState({ children }: { children: string }) {
  return (
    <p className="empty-state" role="status">
      {children}
    </p>
  )
}

export function StageTimeline({ detail }: { detail: EvaluationDetail }) {
  const stages = buildStageTimeline(detail)
  return (
    <>
      <h3>Stage timeline</h3>
      <ol className="stage-timeline" aria-label="Evaluation stages">
        {stages.map((stage) => (
          <li key={stage.id} className="stage-step" data-stage={stage.id} data-status={stage.status}>
            <span className="stage-mark" aria-hidden="true" />
            <div className="stage-body">
              <div className="stage-heading">
                <span className="stage-name">{stage.label}</span>
                <span className="stage-status">{stage.statusLabel}</span>
              </div>
              {stage.detail && <div className="muted small">{stage.detail}</div>}
              {stage.error && (
                <p className="error" role="alert">
                  {stage.error}
                </p>
              )}
            </div>
          </li>
        ))}
      </ol>
    </>
  )
}

export function ScoreBars({ score }: { score: EvaluationScore }) {
  const bars = weightedScoreBars(score)
  if (bars.length === 0) return null
  return (
    <div className="score-bars" aria-label="Weighted score breakdown">
      {bars.map((bar) => (
        <div key={bar.key} className="score-bar">
          <div className="score-bar-label">
            <span>{bar.label}</span>
            <span className="score-bar-value">
              {bar.value.toFixed(2)} / {bar.max.toFixed(bar.max >= 10 ? 0 : 2)}
              {bar.note ? <span className="muted"> · {bar.note}</span> : null}
            </span>
          </div>
          <div
            className="score-bar-track"
            role="meter"
            aria-label={bar.label}
            aria-valuemin={0}
            aria-valuemax={bar.max}
            aria-valuenow={Math.min(bar.max, Math.max(0, bar.value))}
            aria-valuetext={`${bar.value.toFixed(2)} out of ${bar.max.toFixed(2)}`}
          >
            <div className="score-bar-fill" style={{ width: `${bar.percent}%` }} />
          </div>
        </div>
      ))}
    </div>
  )
}
