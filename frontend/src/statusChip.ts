/** Visual tone for an evaluation lifecycle or score status chip. */

export type StatusTone = 'neutral' | 'progress' | 'success' | 'danger' | 'warning' | 'muted'

const PROGRESS = new Set([
  'PREPARING_REPO',
  'RUNNING_BASELINE',
  'INVOKING_AGENT',
  'COLLECTING_PATCH',
  'VALIDATING_PATCH',
  'APPLYING_PATCH',
  'RUNNING_VISIBLE_TESTS',
  'RUNNING_HIDDEN_TESTS',
  'ANALYZING_QUALITY',
  'SCORING',
  'RUNNING',
])

export function evaluationStatusTone(status: string): StatusTone {
  switch (status) {
    case 'COMPLETED':
    case 'SUCCEEDED':
    case 'VALID':
      return 'success'
    case 'FAILED':
    case 'INVALID_BASELINE':
    case 'INFRA_FAILURE':
      return 'danger'
    case 'TIMED_OUT':
    case 'INCOMPLETE':
      return 'warning'
    case 'CANCELLED':
    case 'SKIPPED':
      return 'muted'
    case 'QUEUED':
      return 'neutral'
    default:
      return PROGRESS.has(status) ? 'progress' : 'neutral'
  }
}
