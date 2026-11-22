import {
  createSessionClient,
  isSessionEndedError,
  readProblemDetail,
  sessionEndedForBootstrap,
  signInCopy,
} from './session'

const session = createSessionClient({
  store: {
    getItem: (key) => localStorage.getItem(key),
    setItem: (key, value) => {
      localStorage.setItem(key, value)
    },
    removeItem: (key) => {
      localStorage.removeItem(key)
    },
  },
})

export { isSessionEndedError, sessionEndedForBootstrap, signInCopy }

export type TokenResponse = {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresInSeconds: number
}

export type AuthProviderInfo = {
  id: string
  displayName: string
  type: string
  authorizePath: string | null
}

export type AuthProvidersResponse = {
  providers: AuthProviderInfo[]
}

export type MeResponse = {
  id: string
  email: string
  displayName: string
  memberships: Array<{ orgId: string; orgName: string; orgSlug: string; role: string }>
}

export type Org = { id: string; name: string; slug: string; createdAt: string }
export type Project = {
  id: string
  orgId: string
  name: string
  description: string
  createdBy: string
  createdAt: string
  updatedAt: string
}

export type Benchmark = {
  id: string
  orgId: string
  projectId: string | null
  name: string
  description: string
  createdBy: string
  createdAt: string
  updatedAt: string
}

export type BenchmarkVersion = {
  id: string
  benchmarkId: string
  orgId: string
  version: string
  status: string
  changelog: string
  scoringProfileKey: string
  publishedAt: string | null
  publishedBy: string | null
  hasImmutableSnapshot: boolean
  createdAt: string
  updatedAt: string
}

export type SuiteSummary = {
  id: string
  visibility: string
  framework: string
  caseCount: number | null
  contentRestricted: boolean
  storageUri?: string | null
  entryCommand?: string | null
  contentDigest?: string | null
  fixtureContent?: string | null
  expectedOutcomes?: Record<string, unknown> | null
}

export type Task = {
  id: string
  benchmarkVersionId: string
  orgId: string
  slug: string
  title: string
  prompt: string
  acceptanceCriteria: string
  category: string | null
  difficulty: string | null
  languageTags: string[]
  repoSnapshotId: string | null
  gitUrl: string | null
  baselineCommitSha: string | null
  referencePatch: string
  expectedBehavior: string
  timeoutSec: number
  memoryMb: number
  cpuLimit: number | null
  maxPatchBytes: number
  evalConfig: Record<string, unknown>
  ordinal: number
  hasVisibleSuite: boolean
  hasHiddenSuite: boolean
  visibleSuite: SuiteSummary | null
  hiddenSuite: SuiteSummary | null
  createdAt: string
  updatedAt: string
}

export type Agent = {
  id: string
  orgId: string
  name: string
  adapterType: string
  secretRefId: string | null
  config: Record<string, unknown>
  enabled: boolean
  createdBy: string
  createdAt: string
  updatedAt: string
}

export type AgentInvocation = {
  id: string
  evaluationId: string
  agentId: string
  taskId: string
  provider: string
  model: string | null
  providerVersion: string | null
  prompt: string | null
  status: string
  attempt: number
  maxAttempts: number
  startedAt: string
  finishedAt: string | null
  patchDigest: string | null
  patchByteSize: number | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  estimatedCostUsd: number | null
  errorCode: string | null
  errorMessage: string | null
  meta: Record<string, unknown>
  createdAt: string
}

export type SecretMeta = {
  id: string
  name: string
  purpose: string
  createdAt: string
  rotatedAt: string | null
}

export type Member = {
  userId: string
  email: string
  displayName: string
  role: string
  createdAt: string
}

export type SamlIdp = {
  id: string
  orgId: string
  registrationId: string
  displayName: string
  metadataUrl: string | null
  hasMetadataXml: boolean
  attrEmail: string
  attrDisplayName: string
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export type Evaluation = {
  id: string
  orgId: string
  benchmarkVersionId: string
  taskId: string
  agentId: string
  state: string
  attempt: number
  failureClass: string | null
  errorCode: string | null
  errorMessage: string | null
  cancelRequested: boolean
  createdBy: string
  createdAt: string
  updatedAt: string
  startedAt: string | null
  completedAt: string | null
}

export type EvaluationTransition = {
  id: number
  fromState: string | null
  toState: string
  reason: string
  actor: string
  attempt: number
  createdAt: string
}

export type ScoreMetric = {
  metricKey: string
  rawValue: number
  weight: number
  weightedValue: number
  meta: Record<string, unknown>
}

export type EvaluationScore = {
  id: string
  scoreStatus: string
  weightedTotal: number | null
  profileKey: string
  transitionSummary: Record<string, number>
  rawSummary: Record<string, unknown>
  warnings: string[]
  metrics: ScoreMetric[]
  computedAt: string
}

export type QualityFinding = {
  tool?: string
  ruleId: string
  severity: string
  filePath: string
  line: number
  message: string
  introduced: boolean
}

export type QualityToolSummary = {
  tool: string
  stub: boolean
  notes: string
  totalFindings: number
  newFindings: number
  preExistingFindings: number
  findingsSample: QualityFinding[]
}

export type QualityReport = {
  id: string
  tool: string
  normalizedScore: number | null
  stub: boolean
  newFindingCount: number
  preExistingFindingCount: number
  tools: QualityToolSummary[]
  metrics: Record<string, unknown>
  createdAt: string
}

export type EvaluationDetail = {
  evaluation: Evaluation
  transitions: EvaluationTransition[]
  testRuns: Array<{
    id: string
    phase: string
    status: string
    results: Array<{
      caseKey: string
      status: string
      durationMs: number
      transition: string | null
      message: string
      category?: string | null
      flaky?: boolean
      quarantined?: boolean
      attemptCount?: number
    }>
  }>
  patch: {
    id: string
    contentDigest: string
    format: string
    byteSize: number
    sealed: boolean
  } | null
  agentInvocations: AgentInvocation[]
  score: EvaluationScore | null
  qualityReports?: QualityReport[]
}

export type PageMeta = { page: number; size: number; total: number }

export type OverviewResponse = {
  totalEvaluations: number
  completed: number
  failed: number
  cancelled: number
  timedOut: number
  inFlight: number
  queued: number
  averageWeightedTotal: number | null
  totalEstimatedCostUsd: number
  totalTokens: number | null
  countsByState: Record<string, number>
  recent: Array<{
    id: string
    state: string
    agentId: string
    agentName: string
    taskId: string
    taskSlug: string
    benchmarkVersionId: string
    weightedTotal: number | null
    createdAt: string
    completedAt: string | null
  }>
  volumeByDay: Array<{ day: string; started: number; completed: number }>
}

export type LeaderboardEntry = {
  rank: number
  agentId: string
  agentName: string
  adapterType: string
  taskId: string
  taskSlug: string
  evaluationId: string
  weightedTotal: number
  scoreStatus: string
  transitionSummary: Record<string, number>
  completedAt: string | null
  durationMs: number | null
  totalTokens: number | null
  estimatedCostUsd: number | null
}

export type LeaderboardResponse = {
  benchmarkVersionId: string
  scoringProfileKey: string
  note: string
  entries: LeaderboardEntry[]
  page: PageMeta
}

export type LeaderboardPublicationStatus = {
  published: boolean
  publicSlug: string | null
  title: string | null
  publishedAt: string | null
  publishedBy: string | null
  unpublishedAt: string | null
  publicPath: string | null
}

export type MintEmbedTokenResponse = {
  token: string
  publicSlug: string
  issuedAt: string
  expiresAt: string
  ttlSeconds: number
  embedJsonPath: string
  embedHtmlPath: string
  iframeSnippet: string
  scriptSnippet: string
}

export type PublicLeaderboardEntry = {
  rank: number
  agentName: string
  adapterType: string
  taskSlug: string
  weightedTotal: number
  scoreStatus: string
  transitionSummary: Record<string, number>
  completedAt: string | null
  durationMs: number | null
}

export type PublicLeaderboardResponse = {
  publicSlug: string
  title: string
  orgSlug: string
  orgName: string
  benchmarkName: string
  benchmarkVersion: string
  scoringProfileKey: string
  note: string
  publishedAt: string
  entries: PublicLeaderboardEntry[]
  page: PageMeta
}

export type PublicComment = {
  id: string
  body: string
  displayName: string
  authenticatedAuthor: boolean
  createdAt: string
}

export type PublicCommentListResponse = {
  publicSlug: string
  comments: PublicComment[]
  page: PageMeta
}

export type PublicVoteSummaryResponse = {
  publicSlug: string
  upCount: number
  downCount: number
  score: number
  myVote: number | null
}

export type ComparisonResponse = {
  benchmarkVersionId: string
  scoringProfileKey: string
  agentIds: string[]
  rows: Array<{
    agentId: string
    agentName: string
    adapterType: string
    taskId: string
    taskSlug: string
    evaluationId: string
    weightedTotal: number
    metricRaws: Record<string, number>
    transitionSummary: Record<string, number>
    completedAt: string | null
  }>
  metricKeys: string[]
}

export type CostAnalyticsResponse = {
  totalEstimatedCostUsd: number
  totalPromptTokens: number
  totalCompletionTokens: number
  totalTokens: number
  invocationCount: number
  byAgent: Array<{
    agentId: string
    agentName: string
    adapterType: string
    invocationCount: number
    totalTokens: number
    estimatedCostUsd: number
  }>
  byDay: Array<{ day: string; invocationCount: number; totalTokens: number; estimatedCostUsd: number }>
  page: PageMeta
}

export type EvaluationSearchResponse = {
  items: Array<{
    id: string
    state: string
    agentId: string
    agentName: string
    taskId: string
    taskSlug: string
    benchmarkVersionId: string
    benchmarkVersion: string
    weightedTotal: number | null
    failureClass: string | null
    createdAt: string
    updatedAt: string
    completedAt: string | null
  }>
  page: PageMeta
}

export type SystemHealthResponse = {
  status: string
  checkedAt: string
  components: Record<string, { status: string; detail: string }>
  counts: Record<string, number>
}

export type RegressionCase = {
  name: string
  status: 'passed' | 'failed' | 'skipped' | string
  durationMs: number
  message: string | null
}

export type RegressionRunSummary = {
  id: string
  gitRepo: string
  gitBranch: string
  gitSha: string
  suiteName: string
  startedAt: string
  finishedAt: string
  passed: number
  failed: number
  skipped: number
  caseCount: number
  scope: string
  ingestedBy: string | null
}

export type RegressionRunDetail = RegressionRunSummary & {
  cases: RegressionCase[]
}

export type GpuBenchmarkResult = {
  id: string
  status: 'measured' | 'unavailable' | string
  device: string | null
  api: string | null
  score: number | null
  scoreUnit: string | null
  unavailableReason: string | null
  probe: string
  detail: string | null
  probedAt: string
}

export type GpuGraphicsResult = {
  id: string
  status: 'measured' | 'unavailable' | string
  device: string | null
  frames: number | null
  frameTimeMs: number | null
  score: number | null
  scoreUnit: string | null
  unavailableReason: string | null
  probe: string
  detail: string | null
  probedAt: string
}

export function getAccessToken(): string | null {
  return session.getAccessToken()
}

export function getRefreshToken(): string | null {
  return session.getRefreshToken()
}

export function clearTokens(): void {
  session.clearTokens()
}

export function storeTokens(tokens: TokenResponse): void {
  session.storeTokens(tokens)
}

export function onSessionEnded(listener: () => void): () => void {
  return session.onSessionEnded(listener)
}

export function startSessionKeeper(): () => void {
  return session.startKeeper()
}

export async function api<T>(path: string, options: RequestInit = {}, auth = true): Promise<T> {
  return session.api<T>(path, options, auth)
}

/**
 * Same-origin POST that finishes before the caller returns.
 * Used when the result is shown with window.alert, which has to be open
 * before the click that triggered it returns to the browser.
 */
export function postJsonSync<T>(path: string): T {
  const xhr = new XMLHttpRequest()
  xhr.open('POST', path, false)
  const token = getAccessToken()
  if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`)
  xhr.send()
  if (xhr.status < 200 || xhr.status >= 300) {
    let detail = `HTTP ${xhr.status}`
    try {
      const body = JSON.parse(xhr.responseText) as { detail?: string; title?: string }
      detail = body.detail || body.title || detail
    } catch {
      // keep the status line
    }
    throw new Error(detail)
  }
  return JSON.parse(xhr.responseText) as T
}

export async function downloadAuthenticated(path: string, filename: string): Promise<void> {
  const response = await session.authorizedFetch(path, { method: 'GET' })
  if (!response.ok) {
    throw new Error(await readProblemDetail(response))
  }
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}
