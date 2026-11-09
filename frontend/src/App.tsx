import { useCallback, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import {
  api,
  clearTokens,
  getAccessToken,
  storeTokens,
} from './api'
import type {
  Agent,
  Benchmark,
  BenchmarkVersion,
  Evaluation,
  EvaluationDetail,
  MeResponse,
  Org,
  Project,
  SecretMeta,
  Task,
  TokenResponse,
} from './api'
import './App.css'

type Mode = 'login' | 'register'
type WorkspaceTab = 'projects' | 'benchmarks' | 'evaluations' | 'agents'

function slugify(name: string): string {
  return name
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 63)
}

function App() {
  const [me, setMe] = useState<MeResponse | null>(null)
  const [orgs, setOrgs] = useState<Org[]>([])
  const [selectedOrgId, setSelectedOrgId] = useState<string>('')
  const [projects, setProjects] = useState<Project[]>([])
  const [benchmarks, setBenchmarks] = useState<Benchmark[]>([])
  const [selectedBenchmarkId, setSelectedBenchmarkId] = useState<string>('')
  const [versions, setVersions] = useState<BenchmarkVersion[]>([])
  const [selectedVersionId, setSelectedVersionId] = useState<string>('')
  const [tasks, setTasks] = useState<Task[]>([])
  const [selectedTask, setSelectedTask] = useState<Task | null>(null)
  const [agents, setAgents] = useState<Agent[]>([])
  const [secrets, setSecrets] = useState<SecretMeta[]>([])
  const [evaluations, setEvaluations] = useState<Evaluation[]>([])
  const [selectedEvaluation, setSelectedEvaluation] = useState<EvaluationDetail | null>(null)
  const [selectedAgent, setSelectedAgent] = useState<Agent | null>(null)
  const [tab, setTab] = useState<WorkspaceTab>('benchmarks')
  const [mode, setMode] = useState<Mode>('login')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [bootstrapping, setBootstrapping] = useState(true)

  const loadBenchmarks = useCallback(async (orgId: string) => {
    const list = await api<Benchmark[]>(`/api/v1/orgs/${orgId}/benchmarks`)
    setBenchmarks(list)
    const preferred =
      list.find((b) => b.id === selectedBenchmarkId)?.id || list[0]?.id || ''
    setSelectedBenchmarkId(preferred)
    if (preferred) {
      await loadVersions(orgId, preferred)
    } else {
      setVersions([])
      setSelectedVersionId('')
      setTasks([])
      setSelectedTask(null)
    }
  }, [selectedBenchmarkId])

  const loadVersions = useCallback(async (orgId: string, benchmarkId: string) => {
    const list = await api<BenchmarkVersion[]>(
      `/api/v1/orgs/${orgId}/benchmarks/${benchmarkId}/versions`,
    )
    setVersions(list)
    const preferred =
      list.find((v) => v.id === selectedVersionId)?.id || list[0]?.id || ''
    setSelectedVersionId(preferred)
    if (preferred) {
      const taskList = await api<Task[]>(
        `/api/v1/orgs/${orgId}/benchmark-versions/${preferred}/tasks`,
      )
      setTasks(taskList)
      setSelectedTask(taskList[0] || null)
    } else {
      setTasks([])
      setSelectedTask(null)
    }
  }, [selectedVersionId])

  const loadAgents = useCallback(async (orgId: string) => {
    const agentList = await api<Agent[]>(`/api/v1/orgs/${orgId}/agents`)
    setAgents(agentList)
    setSelectedAgent((prev) => agentList.find((a) => a.id === prev?.id) || agentList[0] || null)
    try {
      const secretList = await api<SecretMeta[]>(`/api/v1/orgs/${orgId}/secrets`)
      setSecrets(secretList)
    } catch {
      setSecrets([])
    }
  }, [])

  const loadEvaluations = useCallback(async (orgId: string) => {
    const [agentList, evalList] = await Promise.all([
      api<Agent[]>(`/api/v1/orgs/${orgId}/agents`),
      api<Evaluation[]>(`/api/v1/orgs/${orgId}/evaluations?limit=50`),
    ])
    setAgents(agentList)
    setEvaluations(evalList)
  }, [])

  const loadWorkspace = useCallback(async () => {
    const profile = await api<MeResponse>('/api/v1/me')
    const orgList = await api<Org[]>('/api/v1/orgs')
    setMe(profile)
    setOrgs(orgList)
    const preferred =
      orgList.find((o) => o.id === selectedOrgId)?.id ||
      profile.memberships[0]?.orgId ||
      orgList[0]?.id ||
      ''
    setSelectedOrgId(preferred)
    if (preferred) {
      const projectList = await api<Project[]>(`/api/v1/orgs/${preferred}/projects`)
      setProjects(projectList)
      await loadBenchmarks(preferred)
      await loadEvaluations(preferred)
      await loadAgents(preferred)
    } else {
      setProjects([])
      setBenchmarks([])
      setVersions([])
      setTasks([])
      setSelectedTask(null)
      setAgents([])
      setSecrets([])
      setEvaluations([])
      setSelectedEvaluation(null)
      setSelectedAgent(null)
    }
  }, [selectedOrgId, loadBenchmarks, loadEvaluations, loadAgents])

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      if (!getAccessToken()) {
        setBootstrapping(false)
        return
      }
      try {
        await loadWorkspace()
      } catch {
        clearTokens()
        if (!cancelled) {
          setMe(null)
        }
      } finally {
        if (!cancelled) {
          setBootstrapping(false)
        }
      }
    })()
    return () => {
      cancelled = true
    }
    // intentionally run once on mount
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function handleAuth(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    const form = new FormData(event.currentTarget)
    const email = String(form.get('email') || '')
    const password = String(form.get('password') || '')
    const displayName = String(form.get('displayName') || '')
    try {
      const path = mode === 'register' ? '/api/v1/auth/register' : '/api/v1/auth/login'
      const body =
        mode === 'register'
          ? { email, password, displayName }
          : { email, password, provider: 'local' }
      const tokens = await api<TokenResponse>(
        path,
        { method: 'POST', body: JSON.stringify(body) },
        false,
      )
      storeTokens(tokens)
      await loadWorkspace()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Authentication failed')
    } finally {
      setBusy(false)
    }
  }

  async function handleLogout() {
    setBusy(true)
    try {
      await api('/api/v1/auth/logout', { method: 'POST', body: '{}' })
    } catch {
      // ignore network logout failures
    } finally {
      clearTokens()
      setMe(null)
      setOrgs([])
      setProjects([])
      setBenchmarks([])
      setVersions([])
      setTasks([])
      setSelectedTask(null)
      setAgents([])
      setSecrets([])
      setEvaluations([])
      setSelectedEvaluation(null)
      setSelectedAgent(null)
      setSelectedOrgId('')
      setBusy(false)
    }
  }

  async function handleCreateOrg(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    const form = new FormData(event.currentTarget)
    const name = String(form.get('name') || '')
    const slug = String(form.get('slug') || '') || slugify(name)
    try {
      const org = await api<Org>('/api/v1/orgs', {
        method: 'POST',
        body: JSON.stringify({ name, slug }),
      })
      event.currentTarget.reset()
      setSelectedOrgId(org.id)
      await loadWorkspace()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create organization')
    } finally {
      setBusy(false)
    }
  }

  async function handleSelectOrg(orgId: string) {
    setSelectedOrgId(orgId)
    setError(null)
    setBusy(true)
    try {
      const projectList = await api<Project[]>(`/api/v1/orgs/${orgId}/projects`)
      setProjects(projectList)
      await loadBenchmarks(orgId)
      await loadEvaluations(orgId)
      await loadAgents(orgId)
      setSelectedEvaluation(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load organization')
      setProjects([])
      setBenchmarks([])
      setEvaluations([])
      setAgents([])
      setSecrets([])
    } finally {
      setBusy(false)
    }
  }

  async function handleCreateAgent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedOrgId) {
      setError('Create or select an organization first')
      return
    }
    setError(null)
    setBusy(true)
    const formEl = event.currentTarget
    const form = new FormData(formEl)
    const name = String(form.get('name') || '')
    const adapterType = String(form.get('adapterType') || 'FAKE')
    const patch = String(form.get('patch') || '')
    const model = String(form.get('model') || '')
    const baseUrl = String(form.get('baseUrl') || '')
    const endpoint = String(form.get('endpoint') || '')
    const secretRefId = String(form.get('secretRefId') || '')
    const config: Record<string, unknown> = {}
    if (patch) config.patch = patch
    if (model) config.model = model
    if (baseUrl) config.baseUrl = baseUrl
    if (endpoint) config.endpoint = endpoint
    if (adapterType === 'OPENAI_COMPATIBLE' || adapterType === 'HTTP') {
      config.maxRetries = Number(form.get('maxRetries') || 2)
      config.timeoutSec = Number(form.get('timeoutSec') || 60)
    }
    try {
      const created = await api<Agent>(`/api/v1/orgs/${selectedOrgId}/agents`, {
        method: 'POST',
        body: JSON.stringify({
          name,
          adapterType,
          secretRefId: secretRefId || null,
          config,
        }),
      })
      formEl.reset()
      await loadAgents(selectedOrgId)
      await loadEvaluations(selectedOrgId)
      setSelectedAgent(created)
      setTab('agents')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create agent')
    } finally {
      setBusy(false)
    }
  }

  async function handleCreateSecret(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedOrgId) return
    setError(null)
    setBusy(true)
    const formEl = event.currentTarget
    const form = new FormData(formEl)
    try {
      await api<SecretMeta>(`/api/v1/orgs/${selectedOrgId}/secrets`, {
        method: 'POST',
        body: JSON.stringify({
          name: String(form.get('name') || ''),
          purpose: 'AGENT',
          plaintext: String(form.get('plaintext') || ''),
        }),
      })
      formEl.reset()
      await loadAgents(selectedOrgId)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create secret')
    } finally {
      setBusy(false)
    }
  }

  async function handleTestAgentConnection(agentId: string) {
    if (!selectedOrgId) return
    setError(null)
    setBusy(true)
    try {
      const result = await api<{ ok: boolean; provider: string; detail: string }>(
        `/api/v1/orgs/${selectedOrgId}/agents/${agentId}/test-connection`,
        { method: 'POST' },
      )
      setError(result.ok ? null : result.detail)
      if (result.ok) {
        window.alert(`${result.provider}: ${result.detail}`)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Connection test failed')
    } finally {
      setBusy(false)
    }
  }

  async function handleToggleAgent(agent: Agent) {
    if (!selectedOrgId) return
    setError(null)
    setBusy(true)
    try {
      await api<Agent>(`/api/v1/orgs/${selectedOrgId}/agents/${agent.id}`, {
        method: 'PATCH',
        body: JSON.stringify({ enabled: !agent.enabled }),
      })
      await loadAgents(selectedOrgId)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not update agent')
    } finally {
      setBusy(false)
    }
  }

  async function handleCreateEvaluation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedOrgId) {
      setError('Create or select an organization first')
      return
    }
    setError(null)
    setBusy(true)
    const formEl = event.currentTarget
    const form = new FormData(formEl)
    const benchmarkVersionId = String(form.get('benchmarkVersionId') || '')
    const taskId = String(form.get('taskId') || '')
    const agentId = String(form.get('agentId') || '')
    try {
      const created = await api<Evaluation>(`/api/v1/orgs/${selectedOrgId}/evaluations`, {
        method: 'POST',
        body: JSON.stringify({ benchmarkVersionId, taskId, agentId }),
      })
      formEl.reset()
      await loadEvaluations(selectedOrgId)
      const detail = await api<EvaluationDetail>(
        `/api/v1/orgs/${selectedOrgId}/evaluations/${created.id}`,
      )
      setSelectedEvaluation(detail)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create evaluation')
    } finally {
      setBusy(false)
    }
  }

  async function handleSelectEvaluation(evaluationId: string) {
    if (!selectedOrgId) return
    setBusy(true)
    setError(null)
    try {
      const detail = await api<EvaluationDetail>(
        `/api/v1/orgs/${selectedOrgId}/evaluations/${evaluationId}`,
      )
      setSelectedEvaluation(detail)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load evaluation')
    } finally {
      setBusy(false)
    }
  }

  async function handleCancelEvaluation() {
    if (!selectedOrgId || !selectedEvaluation) return
    setBusy(true)
    setError(null)
    try {
      await api(
        `/api/v1/orgs/${selectedOrgId}/evaluations/${selectedEvaluation.evaluation.id}/cancel`,
        { method: 'POST' },
      )
      await handleSelectEvaluation(selectedEvaluation.evaluation.id)
      await loadEvaluations(selectedOrgId)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not cancel evaluation')
    } finally {
      setBusy(false)
    }
  }

  async function handleRefreshEvaluation() {
    if (!selectedEvaluation) return
    await handleSelectEvaluation(selectedEvaluation.evaluation.id)
    if (selectedOrgId) {
      await loadEvaluations(selectedOrgId)
    }
  }

  async function handleCreateProject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedOrgId) {
      setError('Create or select an organization first')
      return
    }
    setError(null)
    setBusy(true)
    const form = new FormData(event.currentTarget)
    const name = String(form.get('name') || '')
    const description = String(form.get('description') || '')
    try {
      await api<Project>(`/api/v1/orgs/${selectedOrgId}/projects`, {
        method: 'POST',
        body: JSON.stringify({ name, description }),
      })
      event.currentTarget.reset()
      const projectList = await api<Project[]>(`/api/v1/orgs/${selectedOrgId}/projects`)
      setProjects(projectList)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create project')
    } finally {
      setBusy(false)
    }
  }

  async function handleCreateBenchmark(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedOrgId) {
      setError('Create or select an organization first')
      return
    }
    setError(null)
    setBusy(true)
    const formEl = event.currentTarget
    const form = new FormData(formEl)
    const name = String(form.get('name') || '')
    const description = String(form.get('description') || '')
    const projectId = String(form.get('projectId') || '')
    try {
      const created = await api<Benchmark>(`/api/v1/orgs/${selectedOrgId}/benchmarks`, {
        method: 'POST',
        body: JSON.stringify({
          name,
          description,
          projectId: projectId || null,
        }),
      })
      formEl.reset()
      setSelectedBenchmarkId(created.id)
      await loadBenchmarks(selectedOrgId)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create benchmark')
    } finally {
      setBusy(false)
    }
  }

  async function handleSelectBenchmark(benchmarkId: string) {
    if (!selectedOrgId) return
    setSelectedBenchmarkId(benchmarkId)
    setError(null)
    setBusy(true)
    try {
      await loadVersions(selectedOrgId, benchmarkId)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load versions')
    } finally {
      setBusy(false)
    }
  }

  async function handleCreateVersion(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedOrgId || !selectedBenchmarkId) {
      setError('Select a benchmark first')
      return
    }
    setError(null)
    setBusy(true)
    const formEl = event.currentTarget
    const form = new FormData(formEl)
    const version = String(form.get('version') || '')
    const changelog = String(form.get('changelog') || '')
    try {
      const created = await api<BenchmarkVersion>(
        `/api/v1/orgs/${selectedOrgId}/benchmarks/${selectedBenchmarkId}/versions`,
        {
          method: 'POST',
          body: JSON.stringify({ version, changelog }),
        },
      )
      formEl.reset()
      setSelectedVersionId(created.id)
      const list = await api<BenchmarkVersion[]>(
        `/api/v1/orgs/${selectedOrgId}/benchmarks/${selectedBenchmarkId}/versions`,
      )
      setVersions(list)
      setSelectedVersionId(created.id)
      setTasks([])
      setSelectedTask(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create version')
    } finally {
      setBusy(false)
    }
  }

  async function handleSelectVersion(versionId: string) {
    if (!selectedOrgId) return
    setSelectedVersionId(versionId)
    setBusy(true)
    setError(null)
    try {
      const taskList = await api<Task[]>(
        `/api/v1/orgs/${selectedOrgId}/benchmark-versions/${versionId}/tasks`,
      )
      setTasks(taskList)
      setSelectedTask(taskList[0] || null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load tasks')
    } finally {
      setBusy(false)
    }
  }

  async function handlePublishVersion() {
    if (!selectedOrgId || !selectedVersionId) return
    setBusy(true)
    setError(null)
    try {
      await api(`/api/v1/orgs/${selectedOrgId}/benchmark-versions/${selectedVersionId}/publish`, {
        method: 'POST',
      })
      if (selectedBenchmarkId) {
        await loadVersions(selectedOrgId, selectedBenchmarkId)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not publish version')
    } finally {
      setBusy(false)
    }
  }

  async function handleCreateTask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedOrgId || !selectedVersionId) {
      setError('Select a draft version first')
      return
    }
    setError(null)
    setBusy(true)
    const formEl = event.currentTarget
    const form = new FormData(formEl)
    const slug = String(form.get('slug') || '')
    const title = String(form.get('title') || '')
    const prompt = String(form.get('prompt') || '')
    const acceptanceCriteria = String(form.get('acceptanceCriteria') || '')
    const category = String(form.get('category') || '')
    const difficulty = String(form.get('difficulty') || '')
    const languageTags = String(form.get('languageTags') || '')
      .split(',')
      .map((t) => t.trim())
      .filter(Boolean)
    const baselineCommitSha = String(form.get('baselineCommitSha') || '')
    const expectedBehavior = String(form.get('expectedBehavior') || '')
    const referencePatch = String(form.get('referencePatch') || '')
    const timeoutSec = Number(form.get('timeoutSec') || 1800)
    const memoryMb = Number(form.get('memoryMb') || 2048)
    try {
      const task = await api<Task>(
        `/api/v1/orgs/${selectedOrgId}/benchmark-versions/${selectedVersionId}/tasks`,
        {
          method: 'POST',
          body: JSON.stringify({
            slug,
            title,
            prompt,
            acceptanceCriteria,
            category: category || null,
            difficulty: difficulty || null,
            languageTags,
            baselineCommitSha: baselineCommitSha || null,
            expectedBehavior,
            referencePatch,
            timeoutSec,
            memoryMb,
          }),
        },
      )
      formEl.reset()
      const taskList = await api<Task[]>(
        `/api/v1/orgs/${selectedOrgId}/benchmark-versions/${selectedVersionId}/tasks`,
      )
      setTasks(taskList)
      setSelectedTask(task)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create task')
    } finally {
      setBusy(false)
    }
  }

  async function handleUpsertSuite(event: FormEvent<HTMLFormElement>, visibility: 'visible' | 'hidden') {
    event.preventDefault()
    if (!selectedOrgId || !selectedTask) {
      setError('Select a task first')
      return
    }
    setError(null)
    setBusy(true)
    const form = new FormData(event.currentTarget)
    const framework = String(form.get('framework') || 'junit')
    const entryCommand = String(form.get('entryCommand') || '')
    const fixtureContent = String(form.get('fixtureContent') || '')
    const caseCount = Number(form.get('caseCount') || 0)
    try {
      await api(`/api/v1/orgs/${selectedOrgId}/tasks/${selectedTask.id}/suites/${visibility}`, {
        method: 'PUT',
        body: JSON.stringify({
          framework,
          entryCommand,
          fixtureContent,
          caseCount: caseCount || null,
          expectedOutcomes: {},
        }),
      })
      const refreshed = await api<Task>(`/api/v1/orgs/${selectedOrgId}/tasks/${selectedTask.id}`)
      setSelectedTask(refreshed)
      setTasks((prev) => prev.map((t) => (t.id === refreshed.id ? refreshed : t)))
    } catch (err) {
      setError(err instanceof Error ? err.message : `Could not save ${visibility} suite`)
    } finally {
      setBusy(false)
    }
  }

  if (bootstrapping) {
    return (
      <main className="shell">
        <h1>AgentBench</h1>
        <p className="muted">Loading session…</p>
      </main>
    )
  }

  if (!me) {
    return (
      <main className="shell">
        <p className="eyebrow">Milestone 4</p>
        <h1>AgentBench</h1>
        <p className="lede">Sign in to manage organizations, benchmarks, and evaluations.</p>
        <div className="tabs">
          <button
            type="button"
            className={mode === 'login' ? 'tab active' : 'tab'}
            onClick={() => setMode('login')}
          >
            Log in
          </button>
          <button
            type="button"
            className={mode === 'register' ? 'tab active' : 'tab'}
            onClick={() => setMode('register')}
          >
            Register
          </button>
        </div>
        <form className="panel form" onSubmit={handleAuth}>
          {mode === 'register' && (
            <label>
              Display name
              <input name="displayName" required minLength={1} maxLength={120} />
            </label>
          )}
          <label>
            Email
            <input name="email" type="email" required autoComplete="username" />
          </label>
          <label>
            Password
            <input
              name="password"
              type="password"
              required
              minLength={8}
              autoComplete={mode === 'register' ? 'new-password' : 'current-password'}
            />
          </label>
          {error && <p className="error">{error}</p>}
          <button className="cta" type="submit" disabled={busy}>
            {busy ? 'Working…' : mode === 'register' ? 'Create account' : 'Log in'}
          </button>
        </form>
        <p className="muted footer-links">
          <a href="/swagger-ui.html">Swagger UI</a>
          {' · '}
          <a href="/actuator/health">Health</a>
        </p>
      </main>
    )
  }

  const selectedOrg = orgs.find((o) => o.id === selectedOrgId)
  const role = me.memberships.find((m) => m.orgId === selectedOrgId)?.role
  const selectedBenchmark = benchmarks.find((b) => b.id === selectedBenchmarkId)
  const selectedVersion = versions.find((v) => v.id === selectedVersionId)
  const canEdit = role === 'OWNER' || role === 'ADMIN' || role === 'DEVELOPER'
  const draftSelected = selectedVersion?.status === 'DRAFT'

  return (
    <main className="shell wide">
      <header className="topbar">
        <div>
          <p className="eyebrow">Milestone 4</p>
          <h1>AgentBench</h1>
        </div>
        <div className="userbox">
          <div>
            <strong>{me.displayName}</strong>
            <div className="muted small">{me.email}</div>
          </div>
          <button type="button" className="cta secondary" onClick={handleLogout} disabled={busy}>
            Log out
          </button>
        </div>
      </header>

      {error && <p className="error banner">{error}</p>}

      <section className="grid">
        <div className="panel">
          <h2>Organizations</h2>
          {orgs.length === 0 ? (
            <p className="muted">No organizations yet. Create one to get started.</p>
          ) : (
            <ul className="list">
              {orgs.map((org) => (
                <li key={org.id}>
                  <button
                    type="button"
                    className={org.id === selectedOrgId ? 'list-item active' : 'list-item'}
                    onClick={() => handleSelectOrg(org.id)}
                  >
                    <span>{org.name}</span>
                    <span className="muted small">{org.slug}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          <form className="form compact" onSubmit={handleCreateOrg}>
            <h3>Create organization</h3>
            <label>
              Name
              <input name="name" required minLength={2} maxLength={120} />
            </label>
            <label>
              Slug
              <input name="slug" placeholder="auto-from-name" pattern="[a-z0-9]+(?:-[a-z0-9]+)*" />
            </label>
            <button className="cta" type="submit" disabled={busy}>
              Create org
            </button>
          </form>
        </div>

        <div className="panel">
          <div className="tabs">
            <button
              type="button"
              className={tab === 'benchmarks' ? 'tab active' : 'tab'}
              onClick={() => setTab('benchmarks')}
            >
              Benchmarks
            </button>
            <button
              type="button"
              className={tab === 'evaluations' ? 'tab active' : 'tab'}
              onClick={() => setTab('evaluations')}
            >
              Evaluations
            </button>
            <button
              type="button"
              className={tab === 'agents' ? 'tab active' : 'tab'}
              onClick={() => setTab('agents')}
            >
              Agents
            </button>
            <button
              type="button"
              className={tab === 'projects' ? 'tab active' : 'tab'}
              onClick={() => setTab('projects')}
            >
              Projects
            </button>
          </div>

          {tab === 'agents' ? (
            <>
              <h2>Agents {selectedOrg ? `· ${selectedOrg.name}` : ''}</h2>
              {selectedOrg ? (
                <>
                  <p className="muted small">
                    Provider-independent adapters. API keys live in org secrets — never in agent config.
                  </p>
                  {agents.length === 0 ? (
                    <p className="muted">No agents registered yet.</p>
                  ) : (
                    <ul className="list">
                      {agents.map((agent) => (
                        <li key={agent.id}>
                          <button
                            type="button"
                            className={
                              selectedAgent?.id === agent.id ? 'list-item active' : 'list-item'
                            }
                            onClick={() => setSelectedAgent(agent)}
                          >
                            <span>
                              <strong>{agent.name}</strong>{' '}
                              <span className="badge">{agent.adapterType}</span>{' '}
                              {!agent.enabled && <span className="badge">disabled</span>}
                            </span>
                            <span className="muted small">
                              {agent.secretRefId ? 'secret linked' : 'no secret'} ·{' '}
                              {agent.id.slice(0, 8)}
                            </span>
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                  {selectedAgent && (
                    <div className="detail-block">
                      <h3>{selectedAgent.name}</h3>
                      <p className="muted small">
                        {selectedAgent.adapterType}
                        {selectedAgent.config?.model
                          ? ` · model ${String(selectedAgent.config.model)}`
                          : ''}
                        {selectedAgent.config?.baseUrl
                          ? ` · ${String(selectedAgent.config.baseUrl)}`
                          : ''}
                      </p>
                      {canEdit && (
                        <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
                          <button
                            type="button"
                            className="cta secondary"
                            disabled={busy}
                            onClick={() => handleTestAgentConnection(selectedAgent.id)}
                          >
                            Test connection
                          </button>
                          <button
                            type="button"
                            className="cta secondary"
                            disabled={busy}
                            onClick={() => handleToggleAgent(selectedAgent)}
                          >
                            {selectedAgent.enabled ? 'Disable' : 'Enable'}
                          </button>
                        </div>
                      )}
                    </div>
                  )}
                  {canEdit && (
                    <>
                      <form className="form compact" onSubmit={handleCreateSecret}>
                        <h3>Store agent API key</h3>
                        <label>
                          Secret name
                          <input name="name" required maxLength={100} placeholder="openai-prod" />
                        </label>
                        <label>
                          Plaintext (write-only)
                          <input name="plaintext" required type="password" autoComplete="off" />
                        </label>
                        <button className="cta" type="submit" disabled={busy}>
                          Save encrypted secret
                        </button>
                      </form>
                      <form className="form compact" onSubmit={handleCreateAgent}>
                        <h3>Register agent</h3>
                        <label>
                          Name
                          <input name="name" required maxLength={120} placeholder="OpenAI fixer" />
                        </label>
                        <label>
                          Adapter
                          <select name="adapterType" defaultValue="FAKE">
                            <option value="FAKE">FAKE (deterministic CI)</option>
                            <option value="OPENAI_COMPATIBLE">OPENAI_COMPATIBLE</option>
                            <option value="HTTP">HTTP webhook</option>
                            <option value="CODING_CLI">CODING_CLI (skeleton)</option>
                          </select>
                        </label>
                        <label>
                          Secret ref
                          <select name="secretRefId" defaultValue="">
                            <option value="">None</option>
                            {secrets.map((secret) => (
                              <option key={secret.id} value={secret.id}>
                                {secret.name} ({secret.purpose})
                              </option>
                            ))}
                          </select>
                        </label>
                        <label>
                          Model
                          <input name="model" placeholder="gpt-4o-mini" />
                        </label>
                        <label>
                          Base URL (OpenAI-compatible)
                          <input name="baseUrl" placeholder="https://api.openai.com" />
                        </label>
                        <label>
                          HTTP endpoint
                          <input name="endpoint" placeholder="https://agent.example/invoke" />
                        </label>
                        <label>
                          Max retries
                          <input name="maxRetries" type="number" min={0} max={5} defaultValue={2} />
                        </label>
                        <label>
                          Timeout (sec)
                          <input name="timeoutSec" type="number" min={5} max={600} defaultValue={60} />
                        </label>
                        <label>
                          Fixture patch (FAKE / CLI demos)
                          <textarea name="patch" rows={3} placeholder="Optional unified diff" />
                        </label>
                        <button className="cta" type="submit" disabled={busy}>
                          Create agent
                        </button>
                      </form>
                    </>
                  )}
                </>
              ) : (
                <p className="muted">Select or create an organization to manage agents.</p>
              )}
            </>
          ) : tab === 'evaluations' ? (
            <>
              <h2>Evaluations {selectedOrg ? `· ${selectedOrg.name}` : ''}</h2>
              {selectedOrg ? (
                <>
                  <p className="muted small">Your role: {role || 'unknown'}</p>
                  {evaluations.length === 0 ? (
                    <p className="muted">No evaluations yet.</p>
                  ) : (
                    <ul className="list">
                      {evaluations.map((evaluation) => (
                        <li key={evaluation.id}>
                          <button
                            type="button"
                            className={
                              selectedEvaluation?.evaluation.id === evaluation.id
                                ? 'list-item active'
                                : 'list-item'
                            }
                            onClick={() => handleSelectEvaluation(evaluation.id)}
                          >
                            <span>
                              <span className="badge">{evaluation.state}</span>{' '}
                              <span className="muted small">{evaluation.id.slice(0, 8)}</span>
                            </span>
                            <span className="muted small">
                              task {evaluation.taskId.slice(0, 8)} · agent{' '}
                              {evaluation.agentId.slice(0, 8)}
                            </span>
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                  {canEdit && (
                    <>
                      <p className="muted small">
                        Configure providers on the Agents tab. FAKE agents still work for local demos.
                      </p>
                      <form className="form compact" onSubmit={handleCreateEvaluation}>
                        <h3>Enqueue evaluation</h3>
                        <label>
                          Published version ID
                          <input
                            name="benchmarkVersionId"
                            required
                            defaultValue={
                              versions.find((v) => v.status === 'PUBLISHED')?.id || selectedVersionId
                            }
                          />
                        </label>
                        <label>
                          Task ID
                          <input name="taskId" required defaultValue={selectedTask?.id || ''} />
                        </label>
                        <label>
                          Agent
                          <select name="agentId" required defaultValue={agents[0]?.id || ''}>
                            {agents.map((agent) => (
                              <option key={agent.id} value={agent.id}>
                                {agent.name} ({agent.adapterType})
                              </option>
                            ))}
                          </select>
                        </label>
                        <button className="cta" type="submit" disabled={busy || agents.length === 0}>
                          Create evaluation
                        </button>
                      </form>
                    </>
                  )}
                </>
              ) : (
                <p className="muted">Select or create an organization to manage evaluations.</p>
              )}
            </>
          ) : tab === 'projects' ? (
            <>
              <h2>Projects {selectedOrg ? `· ${selectedOrg.name}` : ''}</h2>
              {selectedOrg ? (
                <>
                  <p className="muted small">Your role: {role || 'unknown'}</p>
                  {projects.length === 0 ? (
                    <p className="muted">No projects in this organization yet.</p>
                  ) : (
                    <ul className="list plain">
                      {projects.map((project) => (
                        <li key={project.id}>
                          <strong>{project.name}</strong>
                          <div className="muted small">{project.description || 'No description'}</div>
                        </li>
                      ))}
                    </ul>
                  )}
                  {canEdit && (
                    <form className="form compact" onSubmit={handleCreateProject}>
                      <h3>Create project</h3>
                      <label>
                        Name
                        <input name="name" required minLength={1} maxLength={120} />
                      </label>
                      <label>
                        Description
                        <input name="description" maxLength={2000} />
                      </label>
                      <button className="cta" type="submit" disabled={busy}>
                        Create project
                      </button>
                    </form>
                  )}
                </>
              ) : (
                <p className="muted">Select or create an organization to manage projects.</p>
              )}
            </>
          ) : (
            <>
              <h2>Benchmarks {selectedOrg ? `· ${selectedOrg.name}` : ''}</h2>
              {selectedOrg ? (
                <>
                  <p className="muted small">Your role: {role || 'unknown'}</p>
                  {benchmarks.length === 0 ? (
                    <p className="muted">No benchmarks yet.</p>
                  ) : (
                    <ul className="list">
                      {benchmarks.map((benchmark) => (
                        <li key={benchmark.id}>
                          <button
                            type="button"
                            className={
                              benchmark.id === selectedBenchmarkId
                                ? 'list-item active'
                                : 'list-item'
                            }
                            onClick={() => handleSelectBenchmark(benchmark.id)}
                          >
                            <span>{benchmark.name}</span>
                            <span className="muted small">
                              {benchmark.description || 'No description'}
                            </span>
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                  {canEdit && (
                    <form className="form compact" onSubmit={handleCreateBenchmark}>
                      <h3>Create benchmark</h3>
                      <label>
                        Name
                        <input name="name" required minLength={1} maxLength={120} />
                      </label>
                      <label>
                        Description
                        <input name="description" maxLength={4000} />
                      </label>
                      <label>
                        Project (optional)
                        <select name="projectId" defaultValue="">
                          <option value="">None</option>
                          {projects.map((p) => (
                            <option key={p.id} value={p.id}>
                              {p.name}
                            </option>
                          ))}
                        </select>
                      </label>
                      <button className="cta" type="submit" disabled={busy}>
                        Create benchmark
                      </button>
                    </form>
                  )}
                </>
              ) : (
                <p className="muted">Select or create an organization to manage benchmarks.</p>
              )}
            </>
          )}
        </div>
      </section>

      {tab === 'evaluations' && selectedEvaluation && (
        <section className="panel" style={{ marginTop: '1.25rem' }}>
          <div className="topbar" style={{ marginBottom: '1rem' }}>
            <div>
              <h2>Evaluation detail</h2>
              <p className="muted small">
                {selectedEvaluation.evaluation.id} · attempt {selectedEvaluation.evaluation.attempt}
              </p>
            </div>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button type="button" className="cta secondary" onClick={handleRefreshEvaluation} disabled={busy}>
                Refresh
              </button>
              {canEdit &&
                !['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(
                  selectedEvaluation.evaluation.state,
                ) && (
                  <button type="button" className="cta secondary" onClick={handleCancelEvaluation} disabled={busy}>
                    Cancel
                  </button>
                )}
            </div>
          </div>
          <p>
            <span className="badge">{selectedEvaluation.evaluation.state}</span>
            {selectedEvaluation.evaluation.failureClass && (
              <span className="muted small">
                {' '}
                · {selectedEvaluation.evaluation.failureClass}
                {selectedEvaluation.evaluation.errorCode
                  ? ` / ${selectedEvaluation.evaluation.errorCode}`
                  : ''}
              </span>
            )}
          </p>
          {selectedEvaluation.evaluation.errorMessage && (
            <p className="error">{selectedEvaluation.evaluation.errorMessage}</p>
          )}
          <h3>Status timeline</h3>
          <ol className="timeline">
            {selectedEvaluation.transitions.map((transition) => (
              <li key={transition.id}>
                <strong>
                  {transition.fromState || '∅'} → {transition.toState}
                </strong>
                <div className="muted small">
                  {new Date(transition.createdAt).toLocaleString()} · {transition.actor}
                  {transition.reason ? ` · ${transition.reason}` : ''}
                </div>
              </li>
            ))}
          </ol>
          {selectedEvaluation.patch && (
            <p className="muted small">
              Sealed patch · {selectedEvaluation.patch.format} · {selectedEvaluation.patch.byteSize}{' '}
              bytes · {selectedEvaluation.patch.contentDigest.slice(0, 12)}…
            </p>
          )}
          {(selectedEvaluation.agentInvocations?.length ?? 0) > 0 && (
            <>
              <h3>Agent runs</h3>
              <ul className="list plain">
                {selectedEvaluation.agentInvocations.map((invocation) => (
                  <li key={invocation.id}>
                    <strong>
                      {invocation.provider} · {invocation.status} · attempt {invocation.attempt}/
                      {invocation.maxAttempts}
                    </strong>
                    <div className="muted small">
                      {invocation.model || 'model n/a'}
                      {invocation.providerVersion ? ` · ${invocation.providerVersion}` : ''}
                      {invocation.totalTokens != null ? ` · ${invocation.totalTokens} tokens` : ''}
                      {invocation.estimatedCostUsd != null
                        ? ` · ~$${invocation.estimatedCostUsd}`
                        : ''}
                      {invocation.patchDigest
                        ? ` · patch ${invocation.patchDigest.slice(0, 12)}…`
                        : ''}
                    </div>
                    {invocation.errorMessage && (
                      <div className="error small">{invocation.errorMessage}</div>
                    )}
                    <div className="muted small">
                      {new Date(invocation.startedAt).toLocaleString()}
                      {invocation.finishedAt
                        ? ` → ${new Date(invocation.finishedAt).toLocaleString()}`
                        : ''}
                    </div>
                  </li>
                ))}
              </ul>
            </>
          )}
          {(selectedEvaluation.qualityReports?.length ?? 0) > 0 && (
            <>
              <h3>Code quality findings</h3>
              {selectedEvaluation.qualityReports!.map((report) => (
                <div key={report.id} className="quality-report">
                  <p>
                    <span className="badge">{report.tool}</span>
                    {report.normalizedScore != null ? (
                      <>
                        {' '}
                        · normalized <strong>{Number(report.normalizedScore).toFixed(3)}</strong>
                      </>
                    ) : (
                      <span className="muted"> · score withheld (stub / unavailable)</span>
                    )}
                    <span className="muted small">
                      {' '}
                      · new {report.newFindingCount} · pre-existing {report.preExistingFindingCount}
                      {report.stub ? ' · stub' : ''}
                    </span>
                  </p>
                  {(report.tools?.length ?? 0) > 0 && (
                    <table className="metrics-table">
                      <thead>
                        <tr>
                          <th>Tool</th>
                          <th>New</th>
                          <th>Pre-existing</th>
                          <th>Total</th>
                          <th>Notes</th>
                        </tr>
                      </thead>
                      <tbody>
                        {report.tools.map((tool) => (
                          <tr key={tool.tool}>
                            <td>{tool.tool}</td>
                            <td>{tool.newFindings}</td>
                            <td>{tool.preExistingFindings}</td>
                            <td>{tool.totalFindings}</td>
                            <td className="muted small">
                              {tool.stub ? 'stub' : tool.notes || ''}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                  {report.tools?.some((tool) => (tool.findingsSample?.length ?? 0) > 0) && (
                    <ul className="muted small">
                      {report.tools.flatMap((tool) =>
                        (tool.findingsSample ?? []).slice(0, 8).map((finding, idx) => (
                          <li key={`${tool.tool}-${finding.ruleId}-${idx}`}>
                            [{tool.tool}] {finding.introduced ? 'NEW' : 'pre'} · {finding.severity}{' '}
                            · {finding.filePath}:{finding.line} — {finding.message}
                          </li>
                        )),
                      )}
                    </ul>
                  )}
                </div>
              ))}
            </>
          )}
          {selectedEvaluation.score && (
            <>
              <h3>Score</h3>
              <p>
                <span className="badge">{selectedEvaluation.score.scoreStatus}</span>
                {selectedEvaluation.score.weightedTotal != null ? (
                  <>
                    {' '}
                    · weighted{' '}
                    <strong>{Number(selectedEvaluation.score.weightedTotal).toFixed(2)}</strong> / 100
                  </>
                ) : (
                  <span className="muted"> · weighted total withheld</span>
                )}
                <span className="muted small"> · profile {selectedEvaluation.score.profileKey}</span>
              </p>
              {selectedEvaluation.score.transitionSummary && (
                <>
                  <h4>Visible transitions</h4>
                  <table className="metrics-table">
                    <thead>
                      <tr>
                        <th>F2P</th>
                        <th>P2P</th>
                        <th>P2F</th>
                        <th>F2F</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr>
                        <td>{selectedEvaluation.score.transitionSummary.F2P ?? 0}</td>
                        <td>{selectedEvaluation.score.transitionSummary.P2P ?? 0}</td>
                        <td>{selectedEvaluation.score.transitionSummary.P2F ?? 0}</td>
                        <td>{selectedEvaluation.score.transitionSummary.F2F ?? 0}</td>
                      </tr>
                    </tbody>
                  </table>
                </>
              )}
              <h4>Raw metrics (separate from weighted total)</h4>
              <table className="metrics-table">
                <thead>
                  <tr>
                    <th>Metric</th>
                    <th>Raw</th>
                    <th>Weight</th>
                    <th>Weighted</th>
                    <th>Notes</th>
                  </tr>
                </thead>
                <tbody>
                  {selectedEvaluation.score.metrics.map((metric) => (
                    <tr key={metric.metricKey}>
                      <td>{metric.metricKey}</td>
                      <td>{Number(metric.rawValue).toFixed(3)}</td>
                      <td>{(Number(metric.weight) * 100).toFixed(0)}%</td>
                      <td>{Number(metric.weightedValue).toFixed(2)}</td>
                      <td className="muted small">
                        {metric.meta?.stub ? 'stub' : ''}
                        {metric.meta?.neverF2P ? ' post-only · never F2P' : ''}
                        {metric.meta?.withheld ? ' withheld' : ''}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {(selectedEvaluation.score.warnings?.length ?? 0) > 0 && (
                <ul className="muted small">
                  {selectedEvaluation.score.warnings.map((warning) => (
                    <li key={warning}>{warning}</li>
                  ))}
                </ul>
              )}
            </>
          )}
          {selectedEvaluation.testRuns.length > 0 && (
            <>
              <h3>Test runs</h3>
              <ul className="list plain">
                {selectedEvaluation.testRuns.map((run) => (
                  <li key={run.id}>
                    <strong>
                      {run.phase} · {run.status}
                      {run.phase === 'HIDDEN_POST' ? ' · hidden pass-rate only (never F2P)' : ''}
                    </strong>
                    <ul className="muted small">
                      {run.results.map((result) => (
                        <li key={result.caseKey}>
                          {result.caseKey}: {result.status}
                          {result.transition ? ` (${result.transition})` : ''}
                          {result.category ? ` · ${result.category}` : ''}
                          {result.flaky ? ' · flaky' : ''} · {result.durationMs}ms
                        </li>
                      ))}
                    </ul>
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
      )}

      {tab === 'benchmarks' && selectedBenchmark && (
        <section className="grid detail-grid">
          <div className="panel">
            <h2>
              Versions · {selectedBenchmark.name}
            </h2>
            {versions.length === 0 ? (
              <p className="muted">No versions yet.</p>
            ) : (
              <ul className="list">
                {versions.map((version) => (
                  <li key={version.id}>
                    <button
                      type="button"
                      className={version.id === selectedVersionId ? 'list-item active' : 'list-item'}
                      onClick={() => handleSelectVersion(version.id)}
                    >
                      <span>
                        {version.version}{' '}
                        <span className="badge">{version.status}</span>
                      </span>
                      <span className="muted small">{version.changelog || 'No changelog'}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
            {canEdit && (
              <form className="form compact" onSubmit={handleCreateVersion}>
                <h3>Create draft version</h3>
                <label>
                  Version
                  <input name="version" required placeholder="1.0.0" maxLength={40} />
                </label>
                <label>
                  Changelog
                  <input name="changelog" maxLength={4000} />
                </label>
                <button className="cta" type="submit" disabled={busy}>
                  Create version
                </button>
              </form>
            )}
            {canEdit && draftSelected && (
              <button
                type="button"
                className="cta"
                style={{ marginTop: '0.75rem' }}
                onClick={handlePublishVersion}
                disabled={busy}
              >
                Publish version (immutable)
              </button>
            )}
          </div>

          <div className="panel">
            <h2>Tasks {selectedVersion ? `· ${selectedVersion.version}` : ''}</h2>
            {!selectedVersion ? (
              <p className="muted">Select a version to manage tasks.</p>
            ) : (
              <>
                {tasks.length === 0 ? (
                  <p className="muted">No tasks in this version.</p>
                ) : (
                  <ul className="list">
                    {tasks.map((task) => (
                      <li key={task.id}>
                        <button
                          type="button"
                          className={selectedTask?.id === task.id ? 'list-item active' : 'list-item'}
                          onClick={() => setSelectedTask(task)}
                        >
                          <span>
                            {task.title} <span className="muted small">({task.slug})</span>
                          </span>
                          <span className="muted small">
                            {task.difficulty || 'unrated'}
                            {task.hasVisibleSuite ? ' · visible' : ''}
                            {task.hasHiddenSuite ? ' · hidden' : ''}
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                )}

                {selectedTask && (
                  <div className="task-detail">
                    <h3>{selectedTask.title}</h3>
                    <p className="muted small">
                      {selectedTask.category || 'uncategorized'}
                      {selectedTask.languageTags?.length
                        ? ` · ${selectedTask.languageTags.join(', ')}`
                        : ''}
                      {selectedTask.baselineCommitSha
                        ? ` · baseline ${selectedTask.baselineCommitSha.slice(0, 8)}`
                        : ''}
                    </p>
                    <p>{selectedTask.prompt}</p>
                    {selectedTask.acceptanceCriteria && (
                      <p className="muted small">
                        Acceptance: {selectedTask.acceptanceCriteria}
                      </p>
                    )}
                    <p className="muted small">
                      Limits: {selectedTask.timeoutSec}s · {selectedTask.memoryMb} MB · max patch{' '}
                      {selectedTask.maxPatchBytes} bytes
                    </p>
                    <p className="muted small">
                      Visible suite:{' '}
                      {selectedTask.hasVisibleSuite
                        ? selectedTask.visibleSuite?.framework || 'configured'
                        : 'none'}
                      {' · '}
                      Hidden suite:{' '}
                      {selectedTask.hasHiddenSuite
                        ? selectedTask.hiddenSuite?.contentRestricted
                          ? 'present (restricted)'
                          : selectedTask.hiddenSuite?.framework || 'configured'
                        : 'none'}
                    </p>
                  </div>
                )}

                {canEdit && draftSelected && (
                  <>
                    <form className="form compact" onSubmit={handleCreateTask}>
                      <h3>Add task</h3>
                      <label>
                        Slug
                        <input
                          name="slug"
                          required
                          pattern="[a-z0-9]+(?:-[a-z0-9]+)*"
                          placeholder="null-deref"
                        />
                      </label>
                      <label>
                        Title
                        <input name="title" required maxLength={200} />
                      </label>
                      <label>
                        Prompt
                        <textarea name="prompt" required rows={3} />
                      </label>
                      <label>
                        Acceptance criteria
                        <textarea name="acceptanceCriteria" rows={2} />
                      </label>
                      <label>
                        Category
                        <input name="category" placeholder="bugfix" />
                      </label>
                      <label>
                        Difficulty
                        <select name="difficulty" defaultValue="MEDIUM">
                          <option value="">—</option>
                          <option value="EASY">EASY</option>
                          <option value="MEDIUM">MEDIUM</option>
                          <option value="HARD">HARD</option>
                          <option value="EXPERT">EXPERT</option>
                        </select>
                      </label>
                      <label>
                        Language tags (comma-separated)
                        <input name="languageTags" placeholder="java,maven" />
                      </label>
                      <label>
                        Baseline commit SHA
                        <input
                          name="baselineCommitSha"
                          pattern="[0-9a-fA-F]{40}"
                          placeholder="40 hex chars"
                        />
                      </label>
                      <label>
                        Expected behavior
                        <textarea name="expectedBehavior" rows={2} />
                      </label>
                      <label>
                        Reference patch
                        <textarea name="referencePatch" rows={2} />
                      </label>
                      <label>
                        Timeout (sec)
                        <input name="timeoutSec" type="number" defaultValue={1800} min={1} />
                      </label>
                      <label>
                        Memory (MB)
                        <input name="memoryMb" type="number" defaultValue={2048} min={64} />
                      </label>
                      <button className="cta" type="submit" disabled={busy}>
                        Create task
                      </button>
                    </form>

                    {selectedTask && (
                      <>
                        <form
                          className="form compact"
                          onSubmit={(e) => handleUpsertSuite(e, 'visible')}
                        >
                          <h3>Visible suite</h3>
                          <label>
                            Framework
                            <input name="framework" defaultValue="junit" required />
                          </label>
                          <label>
                            Entry command
                            <input name="entryCommand" placeholder="mvn -q test" />
                          </label>
                          <label>
                            Fixture content
                            <textarea name="fixtureContent" rows={3} />
                          </label>
                          <label>
                            Case count
                            <input name="caseCount" type="number" min={0} defaultValue={1} />
                          </label>
                          <button className="cta" type="submit" disabled={busy}>
                            Save visible suite
                          </button>
                        </form>
                        <form
                          className="form compact"
                          onSubmit={(e) => handleUpsertSuite(e, 'hidden')}
                        >
                          <h3>Hidden suite</h3>
                          <p className="muted small">
                            Never exposed on agent-facing APIs. VIEWER responses redact fixture content.
                          </p>
                          <label>
                            Framework
                            <input name="framework" defaultValue="junit" required />
                          </label>
                          <label>
                            Entry command
                            <input name="entryCommand" placeholder="mvn -q -Dtest=HiddenIT test" />
                          </label>
                          <label>
                            Fixture content
                            <textarea name="fixtureContent" rows={3} />
                          </label>
                          <label>
                            Case count
                            <input name="caseCount" type="number" min={0} defaultValue={1} />
                          </label>
                          <button className="cta" type="submit" disabled={busy}>
                            Save hidden suite
                          </button>
                        </form>
                      </>
                    )}
                  </>
                )}
              </>
            )}
          </div>
        </section>
      )}

      <p className="muted footer-links">
        <a href="/swagger-ui.html">Swagger UI</a>
        {' · '}
        <a href="/api/v1/info">API info</a>
      </p>
    </main>
  )
}

export default App
