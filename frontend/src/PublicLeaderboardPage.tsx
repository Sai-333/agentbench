import { useMemo, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { api, getAccessToken } from './api'
import type {
  PublicCommentListResponse,
  PublicLeaderboardResponse,
  PublicVoteSummaryResponse,
} from './api'
import {
  EMPTY_PUBLIC_BOARD_FILTER,
  filterPublicEntries,
  publicBoardFiltersActive,
  type PublicBoardFilter,
} from './listFilters'
import './App.css'

type LoadState = 'loading' | 'ready' | 'error' | 'empty'

const VOTER_KEY = 'agentbench.publicVoterFingerprint'
const DISPLAY_NAME_KEY = 'agentbench.publicDisplayName'

function voterFingerprint(): string {
  const existing = localStorage.getItem(VOTER_KEY)
  if (existing && /^[a-zA-Z0-9_-]{8,64}$/.test(existing)) {
    return existing
  }
  const created =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID().replace(/-/g, '')
      : `fp${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
  localStorage.setItem(VOTER_KEY, created)
  return created
}

export function PublicLeaderboardPage({ slug }: { slug: string }) {
  const [board, setBoard] = useState<PublicLeaderboardResponse | null>(null)
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)
  const [votes, setVotes] = useState<PublicVoteSummaryResponse | null>(null)
  const [comments, setComments] = useState<PublicCommentListResponse | null>(null)
  const [displayName, setDisplayName] = useState(() => localStorage.getItem(DISPLAY_NAME_KEY) || '')
  const [commentBody, setCommentBody] = useState('')
  const [engagementError, setEngagementError] = useState<string | null>(null)
  const [posting, setPosting] = useState(false)
  const [voting, setVoting] = useState(false)
  const [boardFilter, setBoardFilter] = useState<PublicBoardFilter>(EMPTY_PUBLIC_BOARD_FILTER)
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    const stored = localStorage.getItem('agentbench.theme')
    return stored === 'dark' ? 'dark' : 'light'
  })

  const withOptionalAuth = !!getAccessToken()
  const visibleEntries = useMemo(
    () => filterPublicEntries(board?.entries ?? [], boardFilter),
    [board, boardFilter],
  )
  const agentOptions = useMemo(
    () => [...new Set((board?.entries ?? []).map((entry) => entry.agentName))].sort(),
    [board],
  )
  const taskOptions = useMemo(
    () => [...new Set((board?.entries ?? []).map((entry) => entry.taskSlug))].sort(),
    [board],
  )

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    localStorage.setItem('agentbench.theme', theme)
  }, [theme])

  useEffect(() => {
    let cancelled = false
    async function load() {
      setState('loading')
      setError(null)
      setEngagementError(null)
      const fp = voterFingerprint()
      try {
        const [data, voteData, commentData] = await Promise.all([
          api<PublicLeaderboardResponse>(
            `/api/v1/public/leaderboards/${encodeURIComponent(slug)}?page=0&size=100`,
            {},
            false,
          ),
          api<PublicVoteSummaryResponse>(
            `/api/v1/public/leaderboards/${encodeURIComponent(slug)}/votes?voterFingerprint=${encodeURIComponent(fp)}`,
            {},
            withOptionalAuth,
          ),
          api<PublicCommentListResponse>(
            `/api/v1/public/leaderboards/${encodeURIComponent(slug)}/comments?page=0&size=50`,
            {},
            false,
          ),
        ])
        if (cancelled) return
        setBoard(data)
        setVotes(voteData)
        setComments(commentData)
        setState(data.entries.length === 0 ? 'empty' : 'ready')
      } catch (err) {
        if (cancelled) return
        setBoard(null)
        setVotes(null)
        setComments(null)
        setError(err instanceof Error ? err.message : 'Leaderboard not found')
        setState('error')
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [slug, withOptionalAuth])

  async function castVote(value: 1 | -1) {
    setVoting(true)
    setEngagementError(null)
    try {
      const updated = await api<PublicVoteSummaryResponse>(
        `/api/v1/public/leaderboards/${encodeURIComponent(slug)}/votes`,
        {
          method: 'POST',
          body: JSON.stringify({ value, voterFingerprint: voterFingerprint() }),
        },
        withOptionalAuth,
      )
      setVotes(updated)
    } catch (err) {
      setEngagementError(err instanceof Error ? err.message : 'Vote failed')
    } finally {
      setVoting(false)
    }
  }

  async function submitComment(event: FormEvent) {
    event.preventDefault()
    setPosting(true)
    setEngagementError(null)
    const name = displayName.trim()
    if (name) {
      localStorage.setItem(DISPLAY_NAME_KEY, name)
    }
    try {
      await api(
        `/api/v1/public/leaderboards/${encodeURIComponent(slug)}/comments`,
        {
          method: 'POST',
          body: JSON.stringify({
            body: commentBody,
            displayName: name || undefined,
          }),
        },
        withOptionalAuth,
      )
      setCommentBody('')
      const refreshed = await api<PublicCommentListResponse>(
        `/api/v1/public/leaderboards/${encodeURIComponent(slug)}/comments?page=0&size=50`,
        {},
        false,
      )
      setComments(refreshed)
    } catch (err) {
      setEngagementError(err instanceof Error ? err.message : 'Comment failed')
    } finally {
      setPosting(false)
    }
  }

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
            <div className="filter-row">
              <label>
                Search
                <input
                  value={boardFilter.q}
                  onChange={(event) =>
                    setBoardFilter((prev) => ({ ...prev, q: event.target.value }))
                  }
                  placeholder="agent or task"
                />
              </label>
              <label>
                Agent
                <select
                  value={boardFilter.agent}
                  onChange={(event) =>
                    setBoardFilter((prev) => ({ ...prev, agent: event.target.value }))
                  }
                >
                  <option value="">Any</option>
                  {agentOptions.map((name) => (
                    <option key={name} value={name}>
                      {name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Task
                <select
                  value={boardFilter.task}
                  onChange={(event) =>
                    setBoardFilter((prev) => ({ ...prev, task: event.target.value }))
                  }
                >
                  <option value="">Any</option>
                  {taskOptions.map((slug) => (
                    <option key={slug} value={slug}>
                      {slug}
                    </option>
                  ))}
                </select>
              </label>
              <button
                type="button"
                className="cta secondary"
                disabled={!publicBoardFiltersActive(boardFilter)}
                onClick={() => setBoardFilter(EMPTY_PUBLIC_BOARD_FILTER)}
              >
                Clear
              </button>
            </div>
            {visibleEntries.length === 0 ? (
              <p className="muted">No leaderboard rows match these filters.</p>
            ) : (
              <div className="table-scroll">
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
                    {visibleEntries.map((entry) => (
                      <tr key={`${entry.rank}-${entry.agentName}-${entry.taskSlug}`}>
                        <td data-label="#">{entry.rank}</td>
                        <td data-label="Agent">
                          {entry.agentName} <span className="badge">{entry.adapterType}</span>
                        </td>
                        <td data-label="Task">{entry.taskSlug}</td>
                        <td data-label="Score">{Number(entry.weightedTotal).toFixed(2)}</td>
                        <td data-label="F2P/P2P/P2F/F2F" className="muted small">
                          {entry.transitionSummary?.F2P ?? 0}/{entry.transitionSummary?.P2P ?? 0}/
                          {entry.transitionSummary?.P2F ?? 0}/{entry.transitionSummary?.F2F ?? 0}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}
      </section>

      {board && state !== 'error' && (
        <section className="panel engagement-panel">
          <h2>Community</h2>
          <p className="muted small">
            Anonymous comments and up/down votes are allowed on published boards. Org OWNER/ADMIN can
            moderate comments. Rate limits apply.
          </p>

          <div className="vote-row">
            <button
              type="button"
              className={`cta secondary${votes?.myVote === 1 ? ' vote-active' : ''}`}
              disabled={voting}
              onClick={() => void castVote(1)}
            >
              ▲ Up ({votes?.upCount ?? 0})
            </button>
            <button
              type="button"
              className={`cta secondary${votes?.myVote === -1 ? ' vote-active' : ''}`}
              disabled={voting}
              onClick={() => void castVote(-1)}
            >
              ▼ Down ({votes?.downCount ?? 0})
            </button>
            <span className="muted small">Score {votes?.score ?? 0}</span>
          </div>

          <form className="comment-form" onSubmit={(e) => void submitComment(e)}>
            <label>
              Display name
              <input
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                maxLength={80}
                placeholder="Anonymous"
              />
            </label>
            <label>
              Comment
              <textarea
                value={commentBody}
                onChange={(e) => setCommentBody(e.target.value)}
                maxLength={2000}
                rows={3}
                required
                placeholder="Share feedback on this public board…"
              />
            </label>
            <button className="cta" type="submit" disabled={posting || !commentBody.trim()}>
              {posting ? 'Posting…' : 'Post comment'}
            </button>
          </form>

          {engagementError && (
            <p className="error banner" role="alert">
              {engagementError}
            </p>
          )}

          <div className="comment-list">
            {(comments?.comments?.length ?? 0) === 0 ? (
              <p className="muted">No comments yet.</p>
            ) : (
              comments?.comments.map((comment) => (
                <article key={comment.id} className="comment-item">
                  <header>
                    <strong>{comment.displayName}</strong>
                    {comment.authenticatedAuthor && <span className="badge">signed-in</span>}
                    <span className="muted small">{new Date(comment.createdAt).toLocaleString()}</span>
                  </header>
                  <p>{comment.body}</p>
                </article>
              ))
            )}
          </div>
        </section>
      )}
    </main>
  )
}

export function publicLeaderboardSlugFromPath(pathname: string): string | null {
  const match = pathname.match(/^\/public\/leaderboards\/([a-z0-9]+(?:-[a-z0-9]+)*)$/i)
  return match ? match[1].toLowerCase() : null
}
