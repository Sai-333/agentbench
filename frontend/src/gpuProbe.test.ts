import assert from 'node:assert/strict'
import { test } from 'node:test'
import { gpuCopyClaimsProductScore, gpuReasonLabel, gpuScoreLabel } from './gpuProbe.ts'

test('measured rows show the transfer score', () => {
  assert.equal(
    gpuScoreLabel({ status: 'measured', score: 18420.5, scoreUnit: 'mib_per_s' }),
    '18420.50',
  )
  assert.equal(gpuReasonLabel(null), 'Unavailable')
})

test('unavailable rows do not show a number', () => {
  assert.equal(
    gpuScoreLabel({ status: 'unavailable', score: 99, scoreUnit: null }),
    null,
  )
  assert.equal(
    gpuScoreLabel({ status: 'unavailable', score: null, scoreUnit: null }),
    null,
  )
  assert.equal(gpuReasonLabel('no_gpu'), 'No GPU')
  assert.equal(gpuReasonLabel('probe_failed'), 'Probe could not run')
})

test('probe copy does not claim a product score', () => {
  const copy =
    'Device, API, and transfer score from this API process. Unavailable means no score.'
  assert.equal(gpuCopyClaimsProductScore(copy), false)
  assert.equal(gpuCopyClaimsProductScore('3DMark score'), true)
})
