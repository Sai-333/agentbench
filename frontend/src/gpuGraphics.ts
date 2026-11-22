import type { GpuGraphicsResult } from './api'

/** Average frame time in milliseconds, or null when the row must not show a number. */
export function graphicsScoreLabel(
  result: Pick<GpuGraphicsResult, 'status' | 'score' | 'scoreUnit'>,
): string | null {
  if (result.status !== 'measured' || result.scoreUnit !== 'ms') {
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

export function graphicsFramesLabel(
  result: Pick<GpuGraphicsResult, 'status' | 'frames'>,
): string | null {
  if (result.status !== 'measured' || result.frames == null) {
    return null
  }
  if (!Number.isInteger(result.frames) || result.frames <= 0) {
    return null
  }
  return String(result.frames)
}

export function graphicsFrameTimeLabel(
  result: Pick<GpuGraphicsResult, 'status' | 'frameTimeMs' | 'scoreUnit'>,
): string | null {
  if (result.status !== 'measured' || result.scoreUnit !== 'ms') {
    return null
  }
  if (result.frameTimeMs == null || !Number.isFinite(Number(result.frameTimeMs))) {
    return null
  }
  const frameTime = Number(result.frameTimeMs)
  if (frameTime <= 0) {
    return null
  }
  return frameTime.toFixed(2)
}
