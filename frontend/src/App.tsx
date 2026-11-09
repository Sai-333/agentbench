import { useEffect, useState } from 'react'
import './App.css'

type PlatformInfo = {
  name?: string
  milestone?: string
  apiBase?: string
  orgScopedApiBase?: string
}

function App() {
  const [info, setInfo] = useState<PlatformInfo | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    fetch('/api/v1/info')
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`)
        }
        return response.json() as Promise<PlatformInfo>
      })
      .then((data) => {
        if (!cancelled) {
          setInfo(data)
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'unreachable')
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <main className="shell">
      <p className="eyebrow">Milestone 1 foundation</p>
      <h1>AgentBench</h1>
      <p className="lede">
        Modular monolith skeleton: API, Compose stack, Flyway, and OpenAPI are wired.
        Product modules arrive in later milestones.
      </p>
      <div className="actions">
        <a className="cta" href="/swagger-ui.html">
          Open Swagger UI
        </a>
        <a className="cta secondary" href="/actuator/health">
          Health
        </a>
      </div>
      <section className="status" aria-live="polite">
        <h2>API status</h2>
        {info ? (
          <ul>
            <li>name: {info.name}</li>
            <li>milestone: {info.milestone}</li>
            <li>org API: {info.orgScopedApiBase}</li>
          </ul>
        ) : error ? (
          <p className="muted">Backend info unavailable ({error}). Use Compose or a local API.</p>
        ) : (
          <p className="muted">Checking /api/v1/info…</p>
        )}
      </section>
    </main>
  )
}

export default App
