import { useCallback, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import {
  api,
  clearTokens,
  getAccessToken,
  storeTokens,
} from './api'
import type { MeResponse, Org, Project, TokenResponse } from './api'
import './App.css'

type Mode = 'login' | 'register'

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
  const [mode, setMode] = useState<Mode>('login')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [bootstrapping, setBootstrapping] = useState(true)

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
    } else {
      setProjects([])
    }
  }, [selectedOrgId])

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
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load projects')
      setProjects([])
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
        <p className="eyebrow">Milestone 2</p>
        <h1>AgentBench</h1>
        <p className="lede">Sign in to manage organizations and projects.</p>
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

  return (
    <main className="shell wide">
      <header className="topbar">
        <div>
          <p className="eyebrow">Milestone 2</p>
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
            </>
          ) : (
            <p className="muted">Select or create an organization to manage projects.</p>
          )}
        </div>
      </section>

      <p className="muted footer-links">
        <a href="/swagger-ui.html">Swagger UI</a>
        {' · '}
        <a href="/api/v1/info">API info</a>
      </p>
    </main>
  )
}

export default App
