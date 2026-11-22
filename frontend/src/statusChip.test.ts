import assert from 'node:assert/strict'
import { test } from 'node:test'
import { EVALUATION_STATES } from './listFilters.ts'
import { evaluationStatusTone } from './statusChip.ts'

test('every evaluation state has a chip tone', () => {
  const tones = Object.fromEntries(EVALUATION_STATES.map((state) => [state, evaluationStatusTone(state)]))
  assert.equal(tones.QUEUED, 'neutral')
  assert.equal(tones.COMPLETED, 'success')
  assert.equal(tones.FAILED, 'danger')
  assert.equal(tones.CANCELLED, 'muted')
  assert.equal(tones.TIMED_OUT, 'warning')
  for (const state of EVALUATION_STATES) {
    if (state === 'QUEUED' || state === 'COMPLETED' || state === 'FAILED' || state === 'CANCELLED' || state === 'TIMED_OUT') {
      continue
    }
    assert.equal(tones[state], 'progress', state)
  }
})

test('score statuses use the same chip tones', () => {
  assert.equal(evaluationStatusTone('VALID'), 'success')
  assert.equal(evaluationStatusTone('INVALID_BASELINE'), 'danger')
  assert.equal(evaluationStatusTone('INCOMPLETE'), 'warning')
  assert.equal(evaluationStatusTone('INFRA_FAILURE'), 'danger')
  assert.equal(evaluationStatusTone('SKIPPED'), 'muted')
})
