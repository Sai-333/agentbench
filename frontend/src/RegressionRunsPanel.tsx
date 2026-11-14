import { useState } from 'react'
import type { RegressionRunDetail, RegressionRunSummary } from './api'
import {
  EMPTY_REGRESSION_FILTER,
  filterRegressionRuns,
  regressionFiltersActive,
  type RegressionFilter,
} from './listFilters'

type LoadState = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

type Props = {
  allowed: boolean
  runs: RegressionRunSummary[]
  state: LoadState
  error: string | null
  expandedId: string
  expanded: RegressionRunDetail | null
  expanding: boolean
  onToggle: (id: string) => void
  onRefresh: () => void
  onBackToOrgs?: () => void
}

function formatWhen(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  return date.toLocaleString()
}

export function RegressionRunsPanel({
  allowed,
  runs,
  state,
  error,
  expandedId,
  expanded,
  expanding,
  onToggle,
  onRefresh,
  onBackToOrgs,
}: Props) {
  const [filter, setFilter] = useState<RegressionFilter>(EMPTY_REGRESSION_FILTER)
  const visibleRuns = filterRegressionRuns(runs, filter)
  return (
    <>
      <h2>Regression runs</h2>
      <p className="muted small">
        AgentBench application regression — platform-scoped results for a git repository, branch,
        and commit. Not consumer / organization data. OWNER or ADMIN of any org can view every run.
      </p>
      {onBackToOrgs ? (
        <button type="button" className="cta secondary" onClick={onBackToOrgs}>
          Back to organizations
        </button>
      ) : null}
      {!allowed ? (
        <p className="muted">
          Your account does not have OWNER or ADMIN on an organization, so run details stay hidden.
        </p>
      ) : (
        <>
          <button type="button" className="cta secondary" onClick={onRefresh}>
            Refresh runs
          </button>
          <div className="filter-row">
            <label>
              Search
              <input
                value={filter.q}
                onChange={(event) => setFilter((prev) => ({ ...prev, q: event.target.value }))}
                placeholder="suite, branch, commit, repo"
              />
            </label>
            <label>
              Outcome
              <select
                value={filter.outcome}
                onChange={(event) =>
                  setFilter((prev) => ({
                    ...prev,
                    outcome: event.target.value as RegressionFilter['outcome'],
                  }))
                }
              >
                <option value="">Any</option>
                <option value="passed">Passed</option>
                <option value="failed">Failed</option>
                <option value="skipped">Has skipped</option>
              </select>
            </label>
            <button
              type="button"
              className="cta secondary"
              disabled={!regressionFiltersActive(filter)}
              onClick={() => setFilter(EMPTY_REGRESSION_FILTER)}
            >
              Clear
            </button>
          </div>
          {error && <p className="error">{error}</p>}
          {state === 'loading' || state === 'idle' ? (
            <p className="muted">Loading regression runs…</p>
          ) : state === 'empty' ? (
            <p className="muted">No regression runs yet.</p>
          ) : visibleRuns.length === 0 ? (
            <p className="muted">No regression runs match these filters.</p>
          ) : (
            <ul className="list">
              {visibleRuns.map((run) => {
                const open = expandedId === run.id
                const detail = open && expanded?.id === run.id ? expanded : null
                return (
                  <li key={run.id}>
                    <button
                      type="button"
                      className={open ? 'list-item active' : 'list-item'}
                      aria-expanded={open}
                      onClick={() => onToggle(run.id)}
                    >
                      <span>{run.suiteName}</span>
                      <span className="muted small">
                        {run.gitBranch} · {run.gitSha.slice(0, 7)} · passed {run.passed} · failed{' '}
                        {run.failed} · skipped {run.skipped}
                      </span>
                    </button>
                    {open && (
                      <div className="detail-block">
                        <p className="muted small">{run.gitRepo}</p>
                        <p className="muted small">{run.gitSha}</p>
                        <p className="muted small">
                          {formatWhen(run.startedAt)} → {formatWhen(run.finishedAt)}
                        </p>
                        {expanding && !detail ? <p className="muted">Loading cases…</p> : null}
                        {detail && (
                          <ul className="regression-cases">
                            {detail.cases.map((item, index) => (
                              <li key={`${item.name}-${index}`} className="regression-case">
                                <span className="regression-case-name">{item.name}</span>
                                <span className="badge regression-status" data-status={item.status}>
                                  {item.status}
                                </span>
                                <span className="muted small">{item.durationMs} ms</span>
                                {item.message ? (
                                  <span className="regression-case-message muted small">{item.message}</span>
                                ) : null}
                              </li>
                            ))}
                          </ul>
                        )}
                      </div>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
        </>
      )}
    </>
  )
}
