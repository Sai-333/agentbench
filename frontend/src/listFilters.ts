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

export const FAILURE_CLASSES = [
  'INFRASTRUCTURE',
  'AGENT',
  'USER',
  'BASELINE',
  'INTERNAL',
] as const

/** Evaluations whose stored failure class is null. */
export const NO_FAILURE_CLASS = 'NONE'

export type EvaluationFilter = {
  q: string
  state: string
  agentId: string
  taskId: string
  benchmarkVersionId: string
  failureClass: string
  minScore: string
  maxScore: string
  createdFrom: string
  createdTo: string
}

export const EMPTY_EVALUATION_FILTER: EvaluationFilter = {
  q: '',
  state: '',
  agentId: '',
  taskId: '',
  benchmarkVersionId: '',
  failureClass: '',
  minScore: '',
  maxScore: '',
  createdFrom: '',
  createdTo: '',
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

function trimmed(value: string | null | undefined): string {
  return (value ?? '').trim()
}

function scoreBound(value: string | null | undefined): number | null {
  const text = scoreQueryValue(value)
  if (text == null) return null
  return Number(text)
}

export function evaluationFiltersActive(filter: EvaluationFilter): boolean {
  return Boolean(
    trimmed(filter.q) ||
      filter.state ||
      filter.agentId ||
      filter.taskId ||
      filter.benchmarkVersionId ||
      filter.failureClass ||
      trimmed(filter.minScore) ||
      trimmed(filter.maxScore) ||
      trimmed(filter.createdFrom) ||
      trimmed(filter.createdTo),
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
  if (filter.failureClass) params.set('failureClass', filter.failureClass)
  const minScore = scoreQueryValue(filter.minScore)
  const maxScore = scoreQueryValue(filter.maxScore)
  if (minScore != null) params.set('minScore', minScore)
  if (maxScore != null) params.set('maxScore', maxScore)
  const createdFrom = createdQueryValue(filter.createdFrom, false)
  const createdTo = createdQueryValue(filter.createdTo, true)
  if (createdFrom) params.set('createdFrom', createdFrom)
  if (createdTo) params.set('createdTo', createdTo)
  return params
}

function scoreQueryValue(value: string | null | undefined): string | null {
  const text = trimmed(value)
  if (!text) return null
  const score = Number(text)
  if (!Number.isFinite(score)) return null
  return text
}

/** Minute-precision "to" includes that whole minute. */
export function createdInstantMs(value: string | null | undefined, end: boolean): number | null {
  const text = trimmed(value)
  if (!text) return null
  const parsed = Date.parse(text)
  if (Number.isNaN(parsed)) return null
  if (end && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(text)) return parsed + 60_000 - 1
  return parsed
}

function createdQueryValue(value: string | null | undefined, end: boolean): string | null {
  const ms = createdInstantMs(value, end)
  if (ms == null) return null
  return new Date(ms).toISOString()
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
  weightedTotal?: number | null
  createdAt?: string | null
}

export function matchesEvaluationItem(item: EvaluationLike, filter: EvaluationFilter): boolean {
  if (filter.state && item.state !== filter.state) return false
  if (filter.agentId && item.agentId !== filter.agentId) return false
  if (filter.taskId && item.taskId !== filter.taskId) return false
  if (filter.benchmarkVersionId && item.benchmarkVersionId !== filter.benchmarkVersionId) {
    return false
  }
  if (filter.failureClass === NO_FAILURE_CLASS) {
    if (item.failureClass) return false
  } else if (filter.failureClass && item.failureClass !== filter.failureClass) {
    return false
  }
  const minScore = scoreBound(filter.minScore)
  const maxScore = scoreBound(filter.maxScore)
  if (minScore != null || maxScore != null) {
    if (item.weightedTotal == null || !Number.isFinite(Number(item.weightedTotal))) return false
    const score = Number(item.weightedTotal)
    if (minScore != null && score < minScore) return false
    if (maxScore != null && score > maxScore) return false
  }
  const createdFrom = createdInstantMs(filter.createdFrom, false)
  const createdTo = createdInstantMs(filter.createdTo, true)
  if (createdFrom != null || createdTo != null) {
    const created = item.createdAt ? Date.parse(item.createdAt) : Number.NaN
    if (Number.isNaN(created)) return false
    if (createdFrom != null && created < createdFrom) return false
    if (createdTo != null && created > createdTo) return false
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

/** Invocations with no tokens and no estimated cost are not cost records. */
export function costsWereReported(totalTokens: number, totalEstimatedCostUsd: number): boolean {
  return Number(totalTokens || 0) > 0 || Number(totalEstimatedCostUsd || 0) > 0
}

export function costViewState(
  reported: boolean,
  filterActive: boolean,
  matchingAgents: number,
): 'ready' | 'empty' | 'filtered' {
  if (filterActive && matchingAgents === 0) return 'filtered'
  if (!reported) return 'empty'
  return 'ready'
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
