const TOKEN_KEY = 'agentbench.accessToken'
const REFRESH_KEY = 'agentbench.refreshToken'

export type TokenResponse = {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresInSeconds: number
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

export function getAccessToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function clearTokens(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(REFRESH_KEY)
}

export function storeTokens(tokens: TokenResponse): void {
  localStorage.setItem(TOKEN_KEY, tokens.accessToken)
  localStorage.setItem(REFRESH_KEY, tokens.refreshToken)
}

async function readError(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { detail?: string; title?: string }
    return body.detail || body.title || `HTTP ${response.status}`
  } catch {
    return `HTTP ${response.status}`
  }
}

export async function api<T>(path: string, options: RequestInit = {}, auth = true): Promise<T> {
  const headers = new Headers(options.headers)
  if (!headers.has('Content-Type') && options.body) {
    headers.set('Content-Type', 'application/json')
  }
  if (auth) {
    const token = getAccessToken()
    if (token) {
      headers.set('Authorization', `Bearer ${token}`)
    }
  }
  const response = await fetch(path, { ...options, headers })
  if (!response.ok) {
    throw new Error(await readError(response))
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}
