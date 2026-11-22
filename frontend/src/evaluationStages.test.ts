import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  buildStageTimeline,
  evaluationProgressHint,
  evaluationStillRunning,
  showFailureChip,
  type StageTimelineInput,
} from './evaluationStages.ts'

function detail(overrides: Partial<StageTimelineInput> & { evaluation: StageTimelineInput['evaluation'] }): StageTimelineInput {
  return {
    transitions: [],
    testRuns: [],
    patch: null,
    agentInvocations: [],
    ...overrides,
  }
}

test('a completed run marks every stage passed and does not look in progress', () => {
  const stages = buildStageTimeline(
    detail({
      evaluation: { state: 'COMPLETED' },
      patch: { id: 'patch' },
      testRuns: [{ id: 'run' }],
      agentInvocations: [
        { status: 'FAILED', attempt: 1, errorMessage: 'first try missed the diff' },
        { status: 'SUCCEEDED', attempt: 2, provider: 'GROK' },
      ],
    }),
  )
  assert.deepEqual(
    stages.map((stage) => stage.status),
    ['passed', 'passed', 'passed', 'passed', 'passed'],
  )
  assert.equal(stages.some((stage) => stage.error), false)
  assert.equal(evaluationStillRunning(detail({ evaluation: { state: 'COMPLETED' } })), false)
  assert.equal(evaluationProgressHint(detail({ evaluation: { state: 'COMPLETED' } })), '')
})

test('a failed model call shows the provider error and is not still running', () => {
  const input = detail({
    evaluation: {
      state: 'INVOKING_AGENT',
      errorCode: null,
      errorMessage: null,
    },
    transitions: [
      { fromState: 'QUEUED', toState: 'PREPARING_REPO', reason: null },
      { fromState: 'PREPARING_REPO', toState: 'RUNNING_BASELINE', reason: null },
      { fromState: 'RUNNING_BASELINE', toState: 'INVOKING_AGENT', reason: null },
    ],
    agentInvocations: [
      {
        status: 'FAILED',
        attempt: 1,
        maxAttempts: 1,
        provider: 'GROK',
        errorCode: 'AGENT_HTTP_401',
        errorMessage: 'Provider HTTP 401: invalid key',
      },
    ],
  })
  const stages = buildStageTimeline(input)
  const model = stages.find((stage) => stage.id === 'model')
  assert.ok(model)
  assert.equal(model.status, 'failed')
  assert.equal(model.statusLabel, 'Failed')
  assert.equal(model.error, 'Provider HTTP 401: invalid key')
  assert.equal(stages.some((stage) => stage.status === 'running'), false)
  assert.equal(stages.find((stage) => stage.id === 'baseline')?.status, 'passed')
  assert.equal(stages.find((stage) => stage.id === 'patch')?.status, 'skipped')
  assert.equal(evaluationStillRunning(input), false)
  assert.equal(showFailureChip(input), true)
  assert.match(evaluationProgressHint(input), /model call failed/)
})

test('a failed lifecycle with no invocation row still shows the evaluation error on the model stage', () => {
  const input = detail({
    evaluation: {
      state: 'FAILED',
      failureClass: 'AGENT',
      errorCode: 'AGENT_ERROR',
      errorMessage: 'Provider response was not a unified diff',
    },
    transitions: [
      {
        fromState: 'RUNNING_BASELINE',
        toState: 'FAILED',
        reason: 'Stage failed: INVOKING_AGENT',
      },
    ],
  })
  const stages = buildStageTimeline(input)
  const model = stages.find((stage) => stage.id === 'model')
  assert.equal(model?.status, 'failed')
  assert.equal(model?.error, 'Provider response was not a unified diff')
  assert.equal(stages.some((stage) => stage.status === 'running'), false)
  assert.equal(evaluationProgressHint(input), '')
})

test('a baseline failure stays on baseline and does not blame the model call', () => {
  const stages = buildStageTimeline(
    detail({
      evaluation: {
        state: 'FAILED',
        failureClass: 'BASELINE',
        errorCode: 'BASELINE_NOT_REPRODUCIBLE',
        errorMessage: 'Target bug was not observed at baseline',
      },
      transitions: [
        { fromState: 'RUNNING_BASELINE', toState: 'FAILED', reason: 'Stage failed: RUNNING_BASELINE' },
      ],
    }),
  )
  assert.equal(stages.find((stage) => stage.id === 'baseline')?.status, 'failed')
  assert.equal(stages.find((stage) => stage.id === 'baseline')?.error, 'Target bug was not observed at baseline')
  assert.equal(stages.find((stage) => stage.id === 'model')?.status, 'skipped')
  assert.equal(stages.find((stage) => stage.id === 'model')?.error, null)
})

test('an in-progress model call stays running and keeps the polling hint', () => {
  const input = detail({
    evaluation: { state: 'INVOKING_AGENT' },
    agentInvocations: [
      { status: 'RUNNING', attempt: 1, maxAttempts: 2, provider: 'GROK', startedAt: '2026-11-22T00:00:00Z' },
    ],
  })
  const model = buildStageTimeline(input).find((stage) => stage.id === 'model')
  assert.equal(model?.status, 'running')
  assert.equal(model?.error, null)
  assert.match(model?.detail ?? '', /GROK/)
  assert.equal(evaluationStillRunning(input), true)
  assert.match(evaluationProgressHint(input), /live polling/)
})

test('a later running attempt supersedes an earlier failed model call', () => {
  const stages = buildStageTimeline(
    detail({
      evaluation: { state: 'INVOKING_AGENT' },
      agentInvocations: [
        { status: 'FAILED', attempt: 1, errorMessage: 'timeout talking to provider', startedAt: '2026-11-22T00:00:00Z' },
        { status: 'RUNNING', attempt: 2, provider: 'GROK', startedAt: '2026-11-22T00:01:00Z' },
      ],
    }),
  )
  assert.equal(stages.find((stage) => stage.id === 'model')?.status, 'running')
  assert.equal(stages.find((stage) => stage.id === 'model')?.error, null)
})

test('patch validation failure marks patch failed and leaves the model call passed', () => {
  const stages = buildStageTimeline(
    detail({
      evaluation: {
        state: 'FAILED',
        failureClass: 'AGENT',
        errorCode: 'PATCH_INVALID',
        errorMessage: 'Patch touches a forbidden path',
      },
      patch: { id: 'patch' },
      agentInvocations: [{ status: 'SUCCEEDED', attempt: 1, provider: 'GROK' }],
      transitions: [
        { fromState: 'VALIDATING_PATCH', toState: 'FAILED', reason: 'Stage failed: VALIDATING_PATCH' },
      ],
    }),
  )
  assert.equal(stages.find((stage) => stage.id === 'model')?.status, 'passed')
  assert.equal(stages.find((stage) => stage.id === 'patch')?.status, 'failed')
  assert.equal(stages.find((stage) => stage.id === 'patch')?.error, 'Patch touches a forbidden path')
  assert.equal(stages.find((stage) => stage.id === 'tests')?.status, 'skipped')
  assert.equal(stages.some((stage) => stage.status === 'running'), false)
})

test('queued and scoring runs place the active stage without inventing an error', () => {
  const queued = buildStageTimeline(detail({ evaluation: { state: 'QUEUED' } }))
  assert.deepEqual(
    queued.map((stage) => stage.status),
    ['pending', 'pending', 'pending', 'pending', 'pending'],
  )
  const scoring = buildStageTimeline(
    detail({
      evaluation: { state: 'SCORING' },
      patch: { id: 'patch' },
      testRuns: [{ id: 'visible' }],
    }),
  )
  assert.equal(scoring.find((stage) => stage.id === 'tests')?.status, 'passed')
  assert.equal(scoring.find((stage) => stage.id === 'score')?.status, 'running')
  assert.equal(scoring.find((stage) => stage.id === 'score')?.detail, 'Computing weighted score')
})
