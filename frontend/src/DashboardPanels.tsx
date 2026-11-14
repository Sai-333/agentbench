import { useEffect, useState, type ReactNode } from 'react'
import type {
  ComparisonResponse,
  CostAnalyticsResponse,
  LeaderboardResponse,
  OverviewResponse,
  SystemHealthResponse,
} from './api'

type LoadState = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

function StateBlock({
  state,
  error,
  emptyMessage,
  children,
}: {
  state: LoadState
  error: string | null
  emptyMessage: string
  children: ReactNode
}) {
  if (state === 'loading' || state === 'idle') {
    return (
      <p className="muted status-block" role="status" aria-live="polite">
        Loading…
      </p>
    )
  }
  if (state === 'error') {
    return (
      <p className="error banner" role="alert">
        {error || 'Something went wrong'}
      </p>
    )
  }
  if (state === 'empty') {
    return <p className="muted status-block">{emptyMessage}</p>
  }
  return <>{children}</>
}

export function OverviewPanel({
  overview,
  state,
  error,
  onOpenEvaluation,
}: {
  overview: OverviewResponse | null
  state: LoadState
  error: string | null
  onOpenEvaluation: (id: string) => void
}) {
  return (
    <div className="dash-stack">
      <StateBlock state={state} error={error} emptyMessage="No evaluation activity yet for this organization.">
        {overview && (
          <>
            <div className="stat-grid" role="group" aria-label="Evaluation overview">
              <div className="stat">
                <span className="stat-label">Total</span>
                <strong className="stat-value">{overview.totalEvaluations}</strong>
              </div>
              <div className="stat">
                <span className="stat-label">Completed</span>
                <strong className="stat-value">{overview.completed}</strong>
              </div>
              <div className="stat">
                <span className="stat-label">In flight</span>
                <strong className="stat-value">{overview.inFlight}</strong>
              </div>
              <div className="stat">
                <span className="stat-label">Queued</span>
                <strong className="stat-value">{overview.queued}</strong>
              </div>
              <div className="stat">
                <span className="stat-label">Failed</span>
                <strong className="stat-value">{overview.failed}</strong>
              </div>
              <div className="stat">
                <span className="stat-label">Avg score</span>
                <strong className="stat-value">
                  {overview.averageWeightedTotal == null
                    ? '—'
                    : Number(overview.averageWeightedTotal).toFixed(1)}
                </strong>
              </div>
              <div className="stat">
                <span className="stat-label">Tokens</span>
                <strong className="stat-value">{overview.totalTokens ?? 0}</strong>
              </div>
              <div className="stat">
                <span className="stat-label">Est. cost</span>
                <strong className="stat-value">
                  ${Number(overview.totalEstimatedCostUsd || 0).toFixed(4)}
                </strong>
              </div>
            </div>

            <h3>Recent evaluations</h3>
            {overview.recent.length === 0 ? (
              <p className="muted">No recent evaluations.</p>
            ) : (
              <ul className="list">
                {overview.recent.map((item) => (
                  <li key={item.id}>
                    <button
                      type="button"
                      className="list-item"
                      onClick={() => onOpenEvaluation(item.id)}
                    >
                      <span>
                        <span className="badge">{item.state}</span> {item.agentName} · {item.taskSlug}
                      </span>
                      <span className="muted small">
                        {item.weightedTotal != null
                          ? `score ${Number(item.weightedTotal).toFixed(2)} · `
                          : ''}
                        {new Date(item.createdAt).toLocaleString()}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}

            <h3>Volume (last {overview.volumeByDay.length || 0} days with activity)</h3>
            {overview.volumeByDay.length === 0 ? (
              <p className="muted">No volume in the selected window.</p>
            ) : (
            <div className="table-scroll">
              <table className="metrics-table">
                <thead>
                  <tr>
                    <th>Day</th>
                    <th>Started</th>
                    <th>Completed</th>
                  </tr>
                </thead>
                <tbody>
                  {overview.volumeByDay.map((point) => (
                    <tr key={point.day}>
                      <td data-label="Day">{point.day}</td>
                      <td data-label="Started">{point.started}</td>
                      <td data-label="Completed">{point.completed}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            )}
          </>
        )}
      </StateBlock>
    </div>
  )
}

export function LeaderboardPanel({
  board,
  state,
  error,
  onExport,
  busy,
  canPublish,
  publication,
  publicationBusy,
  onPublish,
  onUnpublish,
  onMintEmbed,
  embedSnippet,
  embedStatus,
  emptyMessage = 'No completed scored evaluations for this benchmark version yet.',
}: {
  board: LeaderboardResponse | null
  state: LoadState
  error: string | null
  onExport: (format: 'csv' | 'json' | 'pdf') => void
  busy: boolean
  canPublish: boolean
  publication: {
    published: boolean
    publicSlug: string | null
    title: string | null
    publicPath: string | null
  } | null
  publicationBusy: boolean
  onPublish: (publicSlug: string, title: string) => void
  onUnpublish: () => void
  onMintEmbed?: () => void
  embedSnippet?: string | null
  embedStatus?: string | null
  emptyMessage?: string
}) {
  const [slugDraft, setSlugDraft] = useState('')
  const [titleDraft, setTitleDraft] = useState('')

  useEffect(() => {
    setSlugDraft(publication?.publicSlug || '')
    setTitleDraft(publication?.title || '')
  }, [publication?.publicSlug, publication?.title])

  return (
    <div className="dash-stack">
      <div className="toolbar">
        <p className="muted small">
          Org-private by default · same benchmark version · best score per agent×task
        </p>
        <div className="toolbar-actions">
          <button type="button" className="cta secondary" disabled={busy || !board} onClick={() => onExport('csv')}>
            Export CSV
          </button>
          <button type="button" className="cta secondary" disabled={busy || !board} onClick={() => onExport('json')}>
            Export JSON
          </button>
          <button type="button" className="cta secondary" disabled={busy || !board} onClick={() => onExport('pdf')}>
            Export PDF
          </button>
        </div>
      </div>
      {(canPublish || publication?.published) && (
        <div className="publish-box">
          <h3>Public publication</h3>
          <p className="muted small">
            {canPublish
              ? 'OWNER/ADMIN only. Publishes a sanitized snapshot (no internal IDs, tokens, costs, or secrets).'
              : 'This version has an active public leaderboard (read-only for your role).'}
          </p>
          {publication?.published ? (
            <div className="dash-stack">
              <div className="toolbar">
                <p className="muted small">
                  Live at{' '}
                  <a href={publication.publicPath || '#'} target="_blank" rel="noreferrer">
                    {publication.publicPath}
                  </a>
                  {publication.publicSlug ? ` · slug ${publication.publicSlug}` : ''}
                </p>
                <div className="toolbar-actions">
                  {canPublish && onMintEmbed && (
                    <button
                      type="button"
                      className="cta secondary"
                      disabled={publicationBusy}
                      onClick={() => onMintEmbed()}
                    >
                      Copy embed snippet
                    </button>
                  )}
                  {canPublish && (
                    <button
                      type="button"
                      className="cta secondary"
                      disabled={publicationBusy}
                      onClick={() => onUnpublish()}
                    >
                      Unpublish
                    </button>
                  )}
                </div>
              </div>
              {embedStatus && <p className="muted small">{embedStatus}</p>}
              {embedSnippet && (
                <label className="embed-snippet">
                  Embed iframe (signed token; expires)
                  <textarea readOnly rows={4} value={embedSnippet} />
                </label>
              )}
            </div>
          ) : canPublish ? (
            <form
              className="form compact"
              onSubmit={(event) => {
                event.preventDefault()
                onPublish(slugDraft.trim(), titleDraft.trim())
              }}
            >
              <label>
                Public slug
                <input
                  value={slugDraft}
                  onChange={(e) => setSlugDraft(e.target.value)}
                  placeholder="my-org-bench-1-0-0"
                  pattern="[a-z0-9]+(?:-[a-z0-9]+)*"
                  minLength={3}
                  maxLength={80}
                />
              </label>
              <label>
                Title
                <input
                  value={titleDraft}
                  onChange={(e) => setTitleDraft(e.target.value)}
                  placeholder="Optional display title"
                  maxLength={200}
                />
              </label>
              <button className="cta" type="submit" disabled={publicationBusy}>
                Publish publicly
              </button>
            </form>
          ) : null}
        </div>
      )}
      <StateBlock state={state} error={error} emptyMessage={emptyMessage}>
        {board && (
          <>
            <p className="muted small">
              Profile {board.scoringProfileKey} · page {board.page.page + 1} · {board.page.total}{' '}
              entries
            </p>
            <div className="table-scroll">
              <table className="metrics-table">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Agent</th>
                    <th>Task</th>
                    <th>Score</th>
                    <th>F2P/P2P/P2F/F2F</th>
                    <th>Tokens</th>
                    <th>Cost</th>
                  </tr>
                </thead>
                <tbody>
                  {board.entries.map((entry) => (
                    <tr key={`${entry.agentId}-${entry.taskId}`}>
                      <td data-label="#">{entry.rank}</td>
                      <td data-label="Agent">
                        {entry.agentName}{' '}
                        <span className="badge">{entry.adapterType}</span>
                      </td>
                      <td data-label="Task">{entry.taskSlug}</td>
                      <td data-label="Score">{Number(entry.weightedTotal).toFixed(2)}</td>
                      <td data-label="F2P/P2P/P2F/F2F" className="muted small">
                        {entry.transitionSummary?.F2P ?? 0}/{entry.transitionSummary?.P2P ?? 0}/
                        {entry.transitionSummary?.P2F ?? 0}/{entry.transitionSummary?.F2F ?? 0}
                      </td>
                      <td data-label="Tokens">{entry.totalTokens ?? '—'}</td>
                      <td data-label="Cost">
                        {entry.estimatedCostUsd == null
                          ? '—'
                          : `$${Number(entry.estimatedCostUsd).toFixed(4)}`}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </StateBlock>
    </div>
  )
}

export function ComparisonPanel({
  comparison,
  state,
  error,
  emptyMessage = 'Select agents and a published benchmark version to compare identical runs.',
}: {
  comparison: ComparisonResponse | null
  state: LoadState
  error: string | null
  emptyMessage?: string
}) {
  return (
    <div className="dash-stack">
      <StateBlock state={state} error={error} emptyMessage={emptyMessage}>
        {comparison && (
          <>
            <p className="muted small">Profile {comparison.scoringProfileKey}</p>
            <div className="table-scroll">
              <table className="metrics-table">
                <thead>
                  <tr>
                    <th>Agent</th>
                    <th>Task</th>
                    <th>Total</th>
                    {comparison.metricKeys.map((key) => (
                      <th key={key}>{key}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {comparison.rows.map((row) => (
                    <tr key={`${row.agentId}-${row.taskId}`}>
                      <td data-label="Agent">
                        {row.agentName} <span className="badge">{row.adapterType}</span>
                      </td>
                      <td data-label="Task">{row.taskSlug}</td>
                      <td data-label="Total">{Number(row.weightedTotal).toFixed(2)}</td>
                      {comparison.metricKeys.map((key) => (
                        <td key={key} data-label={key}>
                          {row.metricRaws[key] == null
                            ? '—'
                            : Number(row.metricRaws[key]).toFixed(3)}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </StateBlock>
    </div>
  )
}

export function CostsPanel({
  costs,
  state,
  error,
  emptyMessage = 'No token or cost records yet. Costs appear when adapters report them.',
}: {
  costs: CostAnalyticsResponse | null
  state: LoadState
  error: string | null
  emptyMessage?: string
}) {
  return (
    <div className="dash-stack">
      <StateBlock state={state} error={error} emptyMessage={emptyMessage}>
        {costs && (
          <>
            <div className="stat-grid" role="group" aria-label="Cost analytics">
              <div className="stat">
                <span className="stat-label">Invocations</span>
                <strong className="stat-value">{costs.invocationCount}</strong>
              </div>
              <div className="stat">
                <span className="stat-label">Total tokens</span>
                <strong className="stat-value">{costs.totalTokens}</strong>
              </div>
              <div className="stat">
                <span className="stat-label">Prompt</span>
                <strong className="stat-value">{costs.totalPromptTokens}</strong>
              </div>
              <div className="stat">
                <span className="stat-label">Completion</span>
                <strong className="stat-value">{costs.totalCompletionTokens}</strong>
              </div>
              <div className="stat">
                <span className="stat-label">Est. USD</span>
                <strong className="stat-value">
                  ${Number(costs.totalEstimatedCostUsd || 0).toFixed(4)}
                </strong>
              </div>
            </div>
            <h3>By agent</h3>
            {costs.byAgent.length === 0 ? (
              <p className="muted">No per-agent cost rows.</p>
            ) : (
              <div className="table-scroll">
                <table className="metrics-table">
                  <thead>
                    <tr>
                      <th>Agent</th>
                      <th>Calls</th>
                      <th>Tokens</th>
                      <th>Cost</th>
                    </tr>
                  </thead>
                  <tbody>
                    {costs.byAgent.map((row) => (
                      <tr key={row.agentId}>
                        <td data-label="Agent">
                          {row.agentName} <span className="badge">{row.adapterType}</span>
                        </td>
                        <td data-label="Calls">{row.invocationCount}</td>
                        <td data-label="Tokens">{row.totalTokens}</td>
                        <td data-label="Cost">${Number(row.estimatedCostUsd || 0).toFixed(4)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <h3>By day</h3>
            {costs.byDay.length === 0 ? (
              <p className="muted">No daily cost points.</p>
            ) : (
              <div className="table-scroll">
                <table className="metrics-table">
                  <thead>
                    <tr>
                      <th>Day</th>
                      <th>Calls</th>
                      <th>Tokens</th>
                      <th>Cost</th>
                    </tr>
                  </thead>
                  <tbody>
                    {costs.byDay.map((row) => (
                      <tr key={row.day}>
                        <td data-label="Day">{row.day}</td>
                        <td data-label="Calls">{row.invocationCount}</td>
                        <td data-label="Tokens">{row.totalTokens}</td>
                        <td data-label="Cost">${Number(row.estimatedCostUsd || 0).toFixed(4)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
      </StateBlock>
    </div>
  )
}

export function OpsHealthPanel({
  health,
  state,
  error,
}: {
  health: SystemHealthResponse | null
  state: LoadState
  error: string | null
}) {
  return (
    <div className="dash-stack">
      <StateBlock state={state} error={error} emptyMessage="Health summary unavailable.">
        {health && (
          <>
            <p>
              Overall <span className="badge">{health.status}</span>
              <span className="muted small">
                {' '}
                · checked {new Date(health.checkedAt).toLocaleString()}
              </span>
            </p>
            <div className="stat-grid">
              {Object.entries(health.counts).map(([key, value]) => (
                <div className="stat" key={key}>
                  <span className="stat-label">{key}</span>
                  <strong className="stat-value">{value}</strong>
                </div>
              ))}
            </div>
            <h3>Actuator components</h3>
            <div className="table-scroll">
              <table className="metrics-table">
                <thead>
                  <tr>
                    <th>Component</th>
                    <th>Status</th>
                    <th>Detail</th>
                  </tr>
                </thead>
                <tbody>
                  {Object.entries(health.components).map(([name, component]) => (
                    <tr key={name}>
                      <td data-label="Component">{name}</td>
                      <td data-label="Status">
                        <span className="badge">{component.status}</span>
                      </td>
                      <td data-label="Detail" className="muted small">{component.detail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </StateBlock>
    </div>
  )
}
