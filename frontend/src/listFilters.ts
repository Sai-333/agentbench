/** Query builders and row predicates for list screens. */

export const EVALUATION_STATES = [
  'QUEUED',
  'PREPARING_REPO',
  'RUNNING_BASELINE',
  'INVOKING_AGENT',
  'COLLECTING_PATCH',
  'VALIDATING_PATCH',
  'APPLYING_PATCH',
  'RUNNING_VISIBLE_TESTS',
  'RUNNING_HIDDEN_TESTS',
  'ANALYZING_QUALITY',
  'SCORING',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
  'TIMED_OUT',
] as const

export type EvaluationFilter = {
  q: string
  state: string
  agentId: string
  taskId: string
  benchmarkVersionId: string
}

export const EMPTY_EVALUATION_FILTER: EvaluationFilter = {
  q: '',
  state: '',
  agentId: '',
  taskId: '',
  benchmarkVersionId: '',
}

export type LeaderboardFilter = {
  q: string
  agentId: string
  taskId: string
}

export const EMPTY_LEADERBOARD_FILTER: LeaderboardFilter = {
  q: '',
  agentId: '',
  taskId: '',
}

export type PublicBoardFilter = {
  q: string
  agent: string
  task: string
}

export const EMPTY_PUBLIC_BOARD_FILTER: PublicBoardFilter = {
  q: '',
  agent: '',
  task: '',
}

export type RegressionFilter = {
  q: string
  outcome: '' | 'passed' | 'failed' | 'skipped'
}

export const EMPTY_REGRESSION_FILTER: RegressionFilter = {
  q: '',
  outcome: '',
}

export type ComparisonTableFilter = {
  q: string
  taskId: string
}

export const EMPTY_COMPARISON_FILTER: ComparisonTableFilter = {
  q: '',
  taskId: '',
}

function trimmed(value: string): string {
  return value.trim()
}

export function evaluationFiltersActive(filter: EvaluationFilter): boolean {
  return Boolean(
    trimmed(filter.q) ||
      filter.state ||
      filter.agentId ||
      filter.taskId ||
      filter.benchmarkVersionId,
  )
}

/** Params for GET .../analytics/evaluations. Empty fields are omitted so a clear restores the full page. */
export function evaluationSearchParams(
  filter: EvaluationFilter,
  page: number,
  size = 20,
): URLSearchParams {
  const params = new URLSearchParams({
    page: String(Math.max(0, page)),
    size: String(size),
  })
  const q = trimmed(filter.q)
  if (q) params.set('q', q)
  if (filter.state) params.set('state', filter.state)
  if (filter.agentId) params.set('agentId', filter.agentId)
  if (filter.taskId) params.set('taskId', filter.taskId)
  if (filter.benchmarkVersionId) params.set('benchmarkVersionId', filter.benchmarkVersionId)
  return params
}

export type EvaluationLike = {
  id: string
  state: string
  agentId: string
  agentName: string
  taskId: string
  taskSlug: string
  benchmarkVersionId: string
  benchmarkVersion?: string
  failureClass?: string | null
}

export function matchesEvaluationItem(item: EvaluationLike, filter: EvaluationFilter): boolean {
  if (filter.state && item.state !== filter.state) return false
  if (filter.agentId && item.agentId !== filter.agentId) return false
  if (filter.taskId && item.taskId !== filter.taskId) return false
  if (filter.benchmarkVersionId && item.benchmarkVersionId !== filter.benchmarkVersionId) {
    return false
  }
  const q = trimmed(filter.q).toLowerCase()
  if (!q) return true
  const haystack = [
    item.agentName,
    item.taskSlug,
    item.id,
    item.benchmarkVersion ?? '',
    item.failureClass ?? '',
    item.state,
  ]
    .join(' ')
    .toLowerCase()
  return haystack.includes(q)
}

export function filterEvaluationItems<T extends EvaluationLike>(
  items: T[],
  filter: EvaluationFilter,
): T[] {
  if (!evaluationFiltersActive(filter)) return items
  return items.filter((item) => matchesEvaluationItem(item, filter))
}

export function leaderboardFiltersActive(filter: LeaderboardFilter): boolean {
  return Boolean(trimmed(filter.q) || filter.agentId || filter.taskId)
}

/** Params for the org leaderboard. Page/size always present; blank filters are omitted. */
export function leaderboardSearchParams(
  filter: LeaderboardFilter,
  page = 0,
  size = 50,
): URLSearchParams {
  const params = new URLSearchParams({
    page: String(Math.max(0, page)),
    size: String(size),
  })
  if (filter.taskId) params.set('taskId', filter.taskId)
  if (filter.agentId) params.set('agentId', filter.agentId)
  const q = trimmed(filter.q)
  if (q) params.set('q', q)
  return params
}

export type LeaderboardLike = {
  agentId: string
  agentName: string
  adapterType?: string
  taskId: string
  taskSlug: string
}

export function matchesLeaderboardRow(row: LeaderboardLike, filter: LeaderboardFilter): boolean {
  if (filter.taskId && row.taskId !== filter.taskId) return false
  if (filter.agentId && row.agentId !== filter.agentId) return false
  const q = trimmed(filter.q).toLowerCase()
  if (!q) return true
  return `${row.agentName} ${row.adapterType ?? ''} ${row.taskSlug}`.toLowerCase().includes(q)
}

export function filterLeaderboardRows<T extends LeaderboardLike>(
  rows: T[],
  filter: LeaderboardFilter,
): T[] {
  if (!leaderboardFiltersActive(filter)) return rows
  return rows.filter((row) => matchesLeaderboardRow(row, filter))
}

export function publicBoardFiltersActive(filter: PublicBoardFilter): boolean {
  return Boolean(trimmed(filter.q) || filter.agent || filter.task)
}

export type PublicEntryLike = {
  agentName: string
  adapterType: string
  taskSlug: string
}

export function matchesPublicEntry(entry: PublicEntryLike, filter: PublicBoardFilter): boolean {
  if (filter.agent && entry.agentName !== filter.agent) return false
  if (filter.task && entry.taskSlug !== filter.task) return false
  const q = trimmed(filter.q).toLowerCase()
  if (!q) return true
  return `${entry.agentName} ${entry.adapterType} ${entry.taskSlug}`.toLowerCase().includes(q)
}

export function filterPublicEntries<T extends PublicEntryLike>(
  entries: T[],
  filter: PublicBoardFilter,
): T[] {
  if (!publicBoardFiltersActive(filter)) return entries
  return entries.filter((entry) => matchesPublicEntry(entry, filter))
}

export function regressionFiltersActive(filter: RegressionFilter): boolean {
  return Boolean(trimmed(filter.q) || filter.outcome)
}

export type RegressionLike = {
  suiteName: string
  gitBranch: string
  gitSha: string
  gitRepo: string
  passed: number
  failed: number
  skipped: number
}

export function matchesRegressionRun(run: RegressionLike, filter: RegressionFilter): boolean {
  if (filter.outcome === 'failed' && run.failed === 0) return false
  if (filter.outcome === 'passed' && run.failed !== 0) return false
  if (filter.outcome === 'skipped' && run.skipped === 0) return false
  const q = trimmed(filter.q).toLowerCase()
  if (!q) return true
  return `${run.suiteName} ${run.gitBranch} ${run.gitSha} ${run.gitRepo}`.toLowerCase().includes(q)
}

export function filterRegressionRuns<T extends RegressionLike>(
  runs: T[],
  filter: RegressionFilter,
): T[] {
  if (!regressionFiltersActive(filter)) return runs
  return runs.filter((run) => matchesRegressionRun(run, filter))
}

export function comparisonFiltersActive(filter: ComparisonTableFilter): boolean {
  return Boolean(trimmed(filter.q) || filter.taskId)
}

export function comparisonSearchParams(
  versionId: string,
  agentIds: string[],
  taskId: string,
): URLSearchParams {
  const params = new URLSearchParams({
    benchmarkVersionId: versionId,
    agentIds: agentIds.join(','),
  })
  if (taskId) params.set('taskId', taskId)
  return params
}

export type ComparisonLike = {
  agentId: string
  agentName: string
  taskId: string
  taskSlug: string
}

export function matchesComparisonRow(row: ComparisonLike, filter: ComparisonTableFilter): boolean {
  if (filter.taskId && row.taskId !== filter.taskId) return false
  const q = trimmed(filter.q).toLowerCase()
  if (!q) return true
  return `${row.agentName} ${row.taskSlug}`.toLowerCase().includes(q)
}

export function filterComparisonRows<T extends ComparisonLike>(
  rows: T[],
  filter: ComparisonTableFilter,
): T[] {
  if (!comparisonFiltersActive(filter)) return rows
  return rows.filter((row) => matchesComparisonRow(row, filter))
}

export function costSearchParams(agentId: string): URLSearchParams {
  const params = new URLSearchParams()
  if (agentId) params.set('agentId', agentId)
  return params
}

export type CostAgentLike = {
  agentId: string
  agentName: string
}

export function filterCostAgents<T extends CostAgentLike>(rows: T[], agentId: string, q: string): T[] {
  const query = trimmed(q).toLowerCase()
  if (!agentId && !query) return rows
  return rows.filter((row) => {
    if (agentId && row.agentId !== agentId) return false
    if (!query) return true
    return row.agentName.toLowerCase().includes(query)
  })
}
