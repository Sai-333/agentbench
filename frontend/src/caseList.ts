/** Case-list cut on an evaluation run. */

export type CaseResultLike = {
  status: string
  message?: string | null
}

export function isFailedCaseStatus(status: string): boolean {
  return status === 'FAIL' || status === 'ERROR'
}

/** Failed cases only keeps FAIL and ERROR. Off leaves PASS and SKIP in the list. */
export function visibleCaseResults<T extends CaseResultLike>(results: T[], failedOnly: boolean): T[] {
  if (!failedOnly) return results
  return results.filter((result) => isFailedCaseStatus(result.status))
}
