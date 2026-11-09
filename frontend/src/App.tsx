import { useCallback, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import {
  api,
  clearTokens,
  getAccessToken,
  storeTokens,
} from './api'
import type {
  Benchmark,
  BenchmarkVersion,
  MeResponse,
  Org,
  Project,
  Task,
  TokenResponse,
} from './api'
import './App.css'

type Mode = 'login' | 'register'
type WorkspaceTab = 'projects' | 'benchmarks'

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
    } else {
      setProjects([])
      setBenchmarks([])
      setVersions([])
      setTasks([])
      setSelectedTask(null)
    }
  }, [selectedOrgId, loadBenchmarks])

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
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load organization')
      setProjects([])
      setBenchmarks([])
    } finally {
      setBusy(false)
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
        <p className="eyebrow">Milestone 3</p>
        <h1>AgentBench</h1>
        <p className="lede">Sign in to manage organizations, projects, and benchmarks.</p>
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
          <p className="eyebrow">Milestone 3</p>
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
              className={tab === 'projects' ? 'tab active' : 'tab'}
              onClick={() => setTab('projects')}
            >
              Projects
            </button>
          </div>

          {tab === 'projects' ? (
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
