/** Stage timeline for an evaluation detail: baseline, model call, patch, tests, score. */

export const STAGE_IDS = ['baseline', 'model', 'patch', 'tests', 'score'] as const

export type StageId = (typeof STAGE_IDS)[number]

export type StageStatus = 'pending' | 'running' | 'passed' | 'failed' | 'skipped'

export type StageTimelineItem = {
  id: StageId
  label: string
  status: StageStatus
  statusLabel: string
  detail: string | null
  error: string | null
}

export type StageInvocation = {
  status: string
  attempt: number
  maxAttempts?: number
  startedAt?: string
  provider?: string
  errorCode?: string | null
  errorMessage?: string | null
}

export type StageTimelineInput = {
  evaluation: {
    state: string
    failureClass?: string | null
    errorCode?: string | null
    errorMessage?: string | null
  }
  transitions?: Array<{
    fromState?: string | null
    toState: string
    reason?: string | null
  }>
  testRuns?: Array<unknown>
  patch?: unknown | null
  agentInvocations?: StageInvocation[]
}

const LABELS: Record<StageId, string> = {
  baseline: 'Baseline',
  model: 'Model call',
  patch: 'Patch',
  tests: 'Tests',
  score: 'Score',
}

const STATE_STAGE: Record<string, StageId> = {
  PREPARING_REPO: 'baseline',
  RUNNING_BASELINE: 'baseline',
  INVOKING_AGENT: 'model',
  COLLECTING_PATCH: 'patch',
  VALIDATING_PATCH: 'patch',
  APPLYING_PATCH: 'patch',
  RUNNING_VISIBLE_TESTS: 'tests',
  RUNNING_HIDDEN_TESTS: 'tests',
  ANALYZING_QUALITY: 'tests',
  SCORING: 'score',
  COMPLETED: 'score',
}

const STAGE_INDEX: Record<StageId, number> = {
  baseline: 0,
  model: 1,
  patch: 2,
  tests: 3,
  score: 4,
}

const TERMINAL = new Set(['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'])

const FAILED_INVOCATION = new Set(['FAILED', 'TIMED_OUT', 'CANCELLED'])

function stageOfState(state: string | null | undefined): StageId | null {
  if (!state) return null
  return STATE_STAGE[state] ?? null
}

function latestInvocation(invocations: StageInvocation[] | undefined): StageInvocation | null {
  if (!invocations || invocations.length === 0) return null
  return [...invocations].sort((a, b) => {
    if (b.attempt !== a.attempt) return b.attempt - a.attempt
    const aStarted = a.startedAt ?? ''
    const bStarted = b.startedAt ?? ''
    if (aStarted === bStarted) return 0
    return aStarted < bStarted ? 1 : -1
  })[0]
}

function invocationFailed(invocation: StageInvocation | null): boolean {
  return invocation != null && FAILED_INVOCATION.has(invocation.status)
}

function namedFailedStage(
  transitions: StageTimelineInput['transitions'],
): { stage: StageId | null; fromState: string | null } | null {
  if (!transitions) return null
  for (let index = transitions.length - 1; index >= 0; index -= 1) {
    const transition = transitions[index]
    if (
      transition.toState !== 'FAILED' &&
      transition.toState !== 'CANCELLED' &&
      transition.toState !== 'TIMED_OUT'
    ) {
      continue
    }
    const named = /Stage failed:\s*([A-Z0-9_]+)/.exec(transition.reason ?? '')
    return {
      stage: named ? stageOfState(named[1]) : stageOfState(transition.fromState),
      fromState: transition.fromState ?? null,
    }
  }
  return null
}

function stageFromEvidence(input: StageTimelineInput, latest: StageInvocation | null): StageId {
  const code = input.evaluation.errorCode ?? ''
  const failureClass = input.evaluation.failureClass ?? ''
  if (code.startsWith('BASELINE') || failureClass === 'BASELINE') return 'baseline'
  if (code.startsWith('PATCH')) return 'patch'
  if ((code.startsWith('AGENT') || failureClass === 'AGENT' || invocationFailed(latest)) && !input.patch) {
    return 'model'
  }
  if ((input.testRuns?.length ?? 0) > 0) return 'tests'
  if (input.patch) return 'patch'
  if (invocationFailed(latest) || (input.agentInvocations?.length ?? 0) > 0) return 'model'
  return 'baseline'
}

function stageError(stage: StageId, input: StageTimelineInput, latest: StageInvocation | null): string {
  if (stage === 'model' && invocationFailed(latest)) {
    const providerError = latest?.errorMessage?.trim() || latest?.errorCode?.trim()
    if (providerError) return providerError
  }
  const evaluationError = input.evaluation.errorMessage?.trim() || input.evaluation.errorCode?.trim()
  if (evaluationError) return evaluationError
  if (input.evaluation.state === 'CANCELLED') return 'Cancelled before this stage finished'
  if (input.evaluation.state === 'TIMED_OUT') return 'Evaluation exceeded configured max duration'
  if (stage === 'model') return 'Model call failed'
  if (stage === 'baseline') return 'Baseline failed'
  if (stage === 'patch') return 'Patch failed'
  if (stage === 'tests') return 'Tests failed'
  return 'Scoring failed'
}

function resolveFailure(
  input: StageTimelineInput,
): { stage: StageId; error: string } | null {
  const state = input.evaluation.state
  const latest = latestInvocation(input.agentInvocations)
  const terminal = TERMINAL.has(state)
  if (terminal && state !== 'COMPLETED') {
    const named = namedFailedStage(input.transitions)
    const stage =
      named?.stage ??
      stageOfState(named?.fromState) ??
      stageOfState(state) ??
      stageFromEvidence(input, latest)
    return { stage, error: stageError(stage, input, latest) }
  }
  if (invocationFailed(latest)) {
    const current = stageOfState(state)
    const currentIndex = current ? STAGE_INDEX[current] : -1
    if (currentIndex <= STAGE_INDEX.model) {
      return { stage: 'model', error: stageError('model', input, latest) }
    }
  }
  return null
}

function statusLabel(status: StageStatus, state: string): string {
  if (status === 'failed' && state === 'CANCELLED') return 'Cancelled'
  if (status === 'failed' && state === 'TIMED_OUT') return 'Timed out'
  if (status === 'failed') return 'Failed'
  if (status === 'running') return 'Running'
  if (status === 'passed') return 'Passed'
  if (status === 'skipped') return 'Skipped'
  return 'Pending'
}

function runningDetail(id: StageId, input: StageTimelineInput, latest: StageInvocation | null): string | null {
  const state = input.evaluation.state
  if (id === 'baseline' && state === 'PREPARING_REPO') return 'Preparing repository'
  if (id === 'baseline' && state === 'QUEUED') return 'Queued'
  if (id === 'model' && latest && latest.status === 'RUNNING') {
    const provider = latest.provider || 'provider'
    const max = latest.maxAttempts ?? latest.attempt
    return `${provider} · attempt ${latest.attempt}/${max}`
  }
  if (id === 'patch') {
    if (state === 'COLLECTING_PATCH') return 'Collecting patch'
    if (state === 'VALIDATING_PATCH') return 'Validating patch'
    if (state === 'APPLYING_PATCH') return 'Applying patch'
  }
  if (id === 'tests') {
    if (state === 'RUNNING_HIDDEN_TESTS') return 'Hidden tests'
    if (state === 'ANALYZING_QUALITY') return 'Analyzing quality'
    if (state === 'RUNNING_VISIBLE_TESTS') return 'Visible tests'
  }
  if (id === 'score' && state === 'SCORING') return 'Computing weighted score'
  return null
}

export function buildStageTimeline(input: StageTimelineInput): StageTimelineItem[] {
  const state = input.evaluation.state
  const failure = resolveFailure(input)
  const failedIndex = failure ? STAGE_INDEX[failure.stage] : null
  const current = stageOfState(state)
  const currentIndex = current ? STAGE_INDEX[current] : -1
  const completed = state === 'COMPLETED'
  const latest = latestInvocation(input.agentInvocations)

  return STAGE_IDS.map((id) => {
    const index = STAGE_INDEX[id]
    let status: StageStatus
    if (completed) {
      status = 'passed'
    } else if (failedIndex != null) {
      if (index < failedIndex) status = 'passed'
      else if (index === failedIndex) status = 'failed'
      else status = 'skipped'
    } else if (currentIndex < 0) {
      status = 'pending'
    } else if (index < currentIndex) {
      status = 'passed'
    } else if (index === currentIndex) {
      status = 'running'
    } else {
      status = 'pending'
    }
    return {
      id,
      label: LABELS[id],
      status,
      statusLabel: statusLabel(status, state),
      detail: status === 'running' ? runningDetail(id, input, latest) : null,
      error: status === 'failed' ? failure?.error ?? null : null,
    }
  })
}

export function evaluationStillRunning(input: StageTimelineInput): boolean {
  if (TERMINAL.has(input.evaluation.state)) return false
  return !buildStageTimeline(input).some((stage) => stage.status === 'failed')
}

/** Extra FAILED chip while the lifecycle state has not caught up with a failed stage. */
export function showFailureChip(input: StageTimelineInput): boolean {
  return !evaluationStillRunning(input) && !TERMINAL.has(input.evaluation.state)
}

/** Hint next to the evaluations list. A failed stage never claims the run is still polling. */
export function evaluationProgressHint(input: StageTimelineInput): string {
  const failed = buildStageTimeline(input).find((stage) => stage.status === 'failed')
  const terminal = TERMINAL.has(input.evaluation.state)
  if (failed && !terminal) {
    return failed.id === 'model'
      ? ' · model call failed — see the error in the detail'
      : ' · run failed — see the error in the detail'
  }
  if (!terminal) return ' · live polling every 3s'
  return ''
}
