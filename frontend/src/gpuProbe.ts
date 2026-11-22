import type { GpuBenchmarkResult } from './api'

const PRODUCT_CLAIM = /3dmark|ul solutions|certification|certified/i

/** A measured transfer score, or null when the row must not show a number. */
export function gpuScoreLabel(
  result: Pick<GpuBenchmarkResult, 'status' | 'score' | 'scoreUnit'>,
): string | null {
  if (result.status !== 'measured' || result.scoreUnit !== 'mib_per_s') {
    return null
  }
  if (result.score == null || !Number.isFinite(Number(result.score))) {
    return null
  }
  const score = Number(result.score)
  if (score <= 0) {
    return null
  }
  return score.toFixed(2)
}

export function gpuReasonLabel(reason: string | null): string {
  if (reason === 'no_gpu') {
    return 'No GPU'
  }
  if (reason === 'probe_failed') {
    return 'Probe could not run'
  }
  return 'Unavailable'
}

export function gpuCopyClaimsProductScore(text: string): boolean {
  return PRODUCT_CLAIM.test(text)
}
