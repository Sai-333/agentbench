import assert from 'node:assert/strict'
import { test } from 'node:test'
import { isFailedCaseStatus, visibleCaseResults } from './caseList.ts'

const cases = [
  { caseKey: 'passes', status: 'PASS', message: '' },
  { caseKey: 'skipped', status: 'SKIP', message: 'not run' },
  {
    caseKey: 'testStatusUpdatePerformance',
    status: 'FAIL',
    message: 'expected < 200ms but was 900ms',
  },
  { caseKey: 'boom', status: 'ERROR', message: 'connection reset' },
]

test('failed cases only hides pass and skip and keeps the case message', () => {
  assert.equal(isFailedCaseStatus('FAIL'), true)
  assert.equal(isFailedCaseStatus('ERROR'), true)
  assert.equal(isFailedCaseStatus('PASS'), false)
  assert.equal(isFailedCaseStatus('SKIP'), false)
  const failed = visibleCaseResults(cases, true)
  assert.deepEqual(
    failed.map((item) => item.caseKey),
    ['testStatusUpdatePerformance', 'boom'],
  )
  assert.equal(failed[0]?.message, 'expected < 200ms but was 900ms')
  assert.equal(failed[1]?.message, 'connection reset')
})

test('turning failed cases only off restores pass and skip', () => {
  assert.deepEqual(visibleCaseResults(cases, false), cases)
  assert.deepEqual(visibleCaseResults([], true), [])
})
