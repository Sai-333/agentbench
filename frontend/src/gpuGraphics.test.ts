import assert from 'node:assert/strict'
import { test } from 'node:test'
import { gpuCopyClaimsProductScore } from './gpuProbe.ts'
import { graphicsFrameTimeLabel, graphicsFramesLabel, graphicsScoreLabel } from './gpuGraphics.ts'

test('measured rows show frames, frame time, and the score', () => {
  const row = {
    status: 'measured' as const,
    frames: 32,
    frameTimeMs: 1.25,
    score: 1.25,
    scoreUnit: 'ms',
  }
  assert.equal(graphicsScoreLabel(row), '1.25')
  assert.equal(graphicsFrameTimeLabel(row), '1.25')
  assert.equal(graphicsFramesLabel(row), '32')
})

test('unavailable rows do not show a number', () => {
  const row = {
    status: 'unavailable' as const,
    frames: 32,
    frameTimeMs: 1.25,
    score: 1.25,
    scoreUnit: null,
  }
  assert.equal(graphicsScoreLabel(row), null)
  assert.equal(graphicsFrameTimeLabel(row), null)
  assert.equal(graphicsFramesLabel(row), null)
  assert.equal(
    graphicsScoreLabel({ status: 'unavailable', score: null, scoreUnit: null }),
    null,
  )
})

test('graphics copy does not claim a product score', () => {
  const copy =
    'Device, frames, and average frame time from this API process. Unavailable means no score.'
  assert.equal(gpuCopyClaimsProductScore(copy), false)
  assert.equal(gpuCopyClaimsProductScore('3DMark score'), true)
})
