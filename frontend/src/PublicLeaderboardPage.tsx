import { useEffect, useState } from 'react'
import { api } from './api'
import type { PublicLeaderboardResponse } from './api'
import './App.css'

type LoadState = 'loading' | 'ready' | 'error' | 'empty'

export function PublicLeaderboardPage({ slug }: { slug: string }) {
  const [board, setBoard] = useState<PublicLeaderboardResponse | null>(null)
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    const stored = localStorage.getItem('agentbench.theme')
    return stored === 'dark' ? 'dark' : 'light'
  })

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    localStorage.setItem('agentbench.theme', theme)
  }, [theme])

  useEffect(() => {
    let cancelled = false
    async function load() {
      setState('loading')
      setError(null)
      try {
        const data = await api<PublicLeaderboardResponse>(
          `/api/v1/public/leaderboards/${encodeURIComponent(slug)}?page=0&size=100`,
          {},
          false,
        )
        if (cancelled) return
        setBoard(data)
        setState(data.entries.length === 0 ? 'empty' : 'ready')
      } catch (err) {
        if (cancelled) return
        setBoard(null)
        setError(err instanceof Error ? err.message : 'Leaderboard not found')
        setState('error')
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [slug])

  return (
    <main className="shell wide">
      <header className="topbar">
        <div>
          <p className="eyebrow">Public leaderboard</p>
          <h1>{board?.title || 'AgentBench'}</h1>
          {board && (
            <p className="muted">
              {board.orgName} · {board.benchmarkName} {board.benchmarkVersion}
            </p>
          )}
        </div>
        <div className="userbox">
          <button
            type="button"
            className="cta secondary"
            onClick={() => setTheme((t) => (t === 'light' ? 'dark' : 'light'))}
            aria-label={`Switch to ${theme === 'light' ? 'dark' : 'light'} theme`}
          >
            {theme === 'light' ? 'Dark' : 'Light'}
          </button>
          <a className="cta secondary" href="/">
            Sign in
          </a>
        </div>
      </header>

      <section className="panel">
        {state === 'loading' && <p className="muted status-block">Loading public leaderboard…</p>}
        {state === 'error' && (
          <p className="error banner" role="alert">
            {error || 'This leaderboard is not published.'}
          </p>
        )}
        {state === 'empty' && board && (
          <p className="muted">Published, but no completed scored evaluations yet.</p>
        )}
        {state === 'ready' && board && (
          <div className="dash-stack">
            <p className="muted small">
              {board.note} · Profile {board.scoringProfileKey} · {board.page.total} entries
            </p>
            <table className="metrics-table">
              <thead>
                <tr>
                  <th>#</th>
                  <th>Agent</th>
                  <th>Task</th>
                  <th>Score</th>
                  <th>F2P/P2P/P2F/F2F</th>
                </tr>
              </thead>
              <tbody>
                {board.entries.map((entry) => (
                  <tr key={`${entry.rank}-${entry.agentName}-${entry.taskSlug}`}>
                    <td>{entry.rank}</td>
                    <td>
                      {entry.agentName} <span className="badge">{entry.adapterType}</span>
                    </td>
                    <td>{entry.taskSlug}</td>
                    <td>{Number(entry.weightedTotal).toFixed(2)}</td>
                    <td className="muted small">
                      {entry.transitionSummary?.F2P ?? 0}/{entry.transitionSummary?.P2P ?? 0}/
                      {entry.transitionSummary?.P2F ?? 0}/{entry.transitionSummary?.F2F ?? 0}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </main>
  )
}

export function publicLeaderboardSlugFromPath(pathname: string): string | null {
  const match = pathname.match(/^\/public\/leaderboards\/([a-z0-9]+(?:-[a-z0-9]+)*)$/i)
  return match ? match[1].toLowerCase() : null
}
