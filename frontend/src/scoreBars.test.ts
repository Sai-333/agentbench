import assert from 'node:assert/strict'
import { test } from 'node:test'
import { metricLabel, weightedScoreBars } from './scoreBars.ts'

test('weighted bars use each metric contribution out of weight × 100', () => {
  const bars = weightedScoreBars({
    weightedTotal: 62.5,
    metrics: [
      { metricKey: 'target_failure_resolution', rawValue: 1, weight: 0.3, weightedValue: 30 },
      { metricKey: 'regression', rawValue: 0.5, weight: 0.25, weightedValue: 12.5 },
      {
        metricKey: 'hidden',
        rawValue: 0,
        weight: 0.2,
        weightedValue: 0,
        meta: { withheld: true, neverF2P: true },
      },
    ],
  })
  assert.equal(bars[0].key, 'total')
  assert.equal(bars[0].percent, 62.5)
  assert.equal(bars[0].max, 100)
  const target = bars.find((bar) => bar.key === 'target_failure_resolution')
  assert.ok(target)
  assert.equal(target.label, 'Target failure resolution')
  assert.equal(target.max, 30)
  assert.equal(target.percent, 100)
  const regression = bars.find((bar) => bar.key === 'regression')
  assert.equal(regression?.percent, 50)
  const hidden = bars.find((bar) => bar.key === 'hidden')
  assert.equal(hidden?.percent, 0)
  assert.match(hidden?.note ?? '', /withheld/)
  assert.match(hidden?.note ?? '', /20% weight/)
})

test('unknown metric keys stay readable and a missing total omits that bar', () => {
  assert.equal(metricLabel('custom_metric'), 'custom metric')
  const bars = weightedScoreBars({
    weightedTotal: null,
    metrics: [{ metricKey: 'custom_metric', rawValue: 0.2, weight: 0.1, weightedValue: 2 }],
  })
  assert.equal(bars.some((bar) => bar.key === 'total'), false)
  assert.equal(bars[0].percent, 20)
})

test('bar width never exceeds the track', () => {
  const bars = weightedScoreBars({
    weightedTotal: 140,
    metrics: [{ metricKey: 'efficiency', rawValue: 2, weight: 0.05, weightedValue: 12 }],
  })
  assert.equal(bars[0].percent, 100)
  assert.equal(bars[1].percent, 100)
})
