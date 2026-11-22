/** Weighted-score bar model. Each metric's bar is its points out of weight × 100. */

export type ScoreBarMetric = {
  metricKey: string
  rawValue: number
  weight: number
  weightedValue: number
  meta?: Record<string, unknown> | null
}

export type ScoreBarInput = {
  weightedTotal: number | null
  metrics: ScoreBarMetric[]
}

export type ScoreBar = {
  key: string
  label: string
  value: number
  max: number
  percent: number
  note: string | null
}

const METRIC_LABELS: Record<string, string> = {
  target_failure_resolution: 'Target failure resolution',
  regression: 'Regression',
  hidden: 'Hidden tests',
  code_quality: 'Code quality',
  edge_case: 'Edge cases',
  efficiency: 'Efficiency',
}

export function metricLabel(metricKey: string): string {
  return METRIC_LABELS[metricKey] ?? metricKey.replaceAll('_', ' ')
}

function clampPercent(value: number, max: number): number {
  if (!Number.isFinite(value) || !Number.isFinite(max) || max <= 0) return 0
  return Math.min(100, Math.max(0, (value / max) * 100))
}

function metricNote(metric: ScoreBarMetric, weightPercent: number): string {
  const notes = [`${weightPercent}% weight`]
  if (metric.meta?.stub) notes.push('stub')
  if (metric.meta?.withheld) notes.push('withheld')
  if (metric.meta?.neverF2P) notes.push('never F2P')
  return notes.join(' · ')
}

export function weightedScoreBars(score: ScoreBarInput): ScoreBar[] {
  const bars: ScoreBar[] = []
  if (score.weightedTotal != null && Number.isFinite(Number(score.weightedTotal))) {
    const value = Number(score.weightedTotal)
    bars.push({
      key: 'total',
      label: 'Weighted total',
      value,
      max: 100,
      percent: clampPercent(value, 100),
      note: null,
    })
  }
  for (const metric of score.metrics) {
    const weight = Number(metric.weight)
    const value = Number(metric.weightedValue)
    const max = weight > 0 ? weight * 100 : 0
    const withheld = Boolean(metric.meta?.withheld)
    bars.push({
      key: metric.metricKey,
      label: metricLabel(metric.metricKey),
      value: Number.isFinite(value) ? value : 0,
      max,
      percent: withheld ? 0 : clampPercent(value, max),
      note: metricNote(metric, Math.round((Number.isFinite(weight) ? weight : 0) * 100)),
    })
  }
  return bars
}
