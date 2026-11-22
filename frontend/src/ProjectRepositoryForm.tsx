import { useEffect, useState } from 'react'
import type { FormEvent, MouseEvent } from 'react'
import { api } from './api'
import type { ProjectRepository, RepoSnapshot } from './api'
import {
  isMissingRepository,
  projectRepositoryPath,
  projectRepositoryValidatePath,
  projectSnapshotsPath,
  readRepositoryFields,
  repositoryFieldsDiffer,
  repositoryStatusTone,
  repositoryWriteBody,
  snapshotWriteBody,
} from './projectRepository'

type Props = {
  orgId: string
  projectId: string
  projectName: string
  canEdit: boolean
}

function formatWhen(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return date.toLocaleString()
}

function filled(value: string | null | undefined): string | null {
  if (!value || !value.trim()) return null
  return value.trim()
}

function RepositoryStatus({ repository }: { repository: ProjectRepository }) {
  const status = repository.validationStatus || 'UNKNOWN'
  return (
    <p className="muted small">
      <span className="badge status-chip" data-tone={repositoryStatusTone(status)} data-status={status}>
        {status}
      </span>
      {repository.validationMessage ? ` ${repository.validationMessage}` : ''}
      {repository.lastValidatedAt ? ` · ${formatWhen(repository.lastValidatedAt)}` : ''}
    </p>
  )
}

function SnapshotList({ snapshots, canEdit }: { snapshots: RepoSnapshot[]; canEdit: boolean }) {
  if (snapshots.length === 0) {
    return (
      <p className="empty-state" role="status">
        {canEdit
          ? 'No snapshot yet. Create one to record the URL, branch, and commit.'
          : 'No snapshot yet. Ask a developer to record the URL, branch, and commit.'}
      </p>
    )
  }
  return (
    <ul className="list plain">
      {snapshots.map((snapshot) => {
        const digest = filled(snapshot.contentDigest)
        const storage = filled(snapshot.storageUri)
        return (
          <li key={snapshot.id}>
            <strong>{snapshot.gitUrl || 'No URL'}</strong>
            <div className="muted small">
              {snapshot.branch || 'no branch'}
              {' · '}
              {snapshot.commitSha || 'unpinned commit'}
              {' · '}
              {formatWhen(snapshot.createdAt)}
            </div>
            {digest ? <div className="muted small">Digest {digest}</div> : null}
            {storage ? <div className="muted small">{storage}</div> : null}
          </li>
        )
      })}
    </ul>
  )
}

export function ProjectRepositoryForm({ orgId, projectId, projectName, canEdit }: Props) {
  const [repository, setRepository] = useState<ProjectRepository | null>(null)
  const [snapshots, setSnapshots] = useState<RepoSnapshot[]>([])
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let cancelled = false
    async function load() {
      try {
        const [repoResult, snapshotList] = await Promise.all([
          api<ProjectRepository>(projectRepositoryPath(orgId, projectId)).catch((err: unknown) => {
            if (isMissingRepository(err)) return null
            throw err
          }),
          api<RepoSnapshot[]>(projectSnapshotsPath(orgId, projectId)),
        ])
        if (cancelled) return
        setRepository(repoResult)
        setSnapshots(snapshotList)
      } catch (err) {
        if (cancelled) return
        setError(err instanceof Error ? err.message : 'Could not load repository')
      } finally {
        if (!cancelled) setLoaded(true)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [orgId, projectId])

  async function handleSave(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!loaded || busy) return
    setError(null)
    setBusy(true)
    try {
      const saved = await api<ProjectRepository>(projectRepositoryPath(orgId, projectId), {
        method: 'PUT',
        body: JSON.stringify(repositoryWriteBody(readRepositoryFields(event.currentTarget), repository)),
      })
      setRepository(saved)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save repository')
    } finally {
      setBusy(false)
    }
  }

  async function handleValidate(form: HTMLFormElement) {
    if (!loaded || busy) return
    setError(null)
    setBusy(true)
    try {
      const fields = readRepositoryFields(form)
      if (repositoryFieldsDiffer(fields, repository)) {
        await api<ProjectRepository>(projectRepositoryPath(orgId, projectId), {
          method: 'PUT',
          body: JSON.stringify(repositoryWriteBody(fields, repository)),
        })
      }
      const validated = await api<ProjectRepository>(projectRepositoryValidatePath(orgId, projectId), {
        method: 'POST',
      })
      setRepository(validated)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not validate repository')
    } finally {
      setBusy(false)
    }
  }

  async function handleSnapshot(form: HTMLFormElement) {
    if (!loaded || busy) return
    setError(null)
    setBusy(true)
    try {
      const created = await api<RepoSnapshot>(projectSnapshotsPath(orgId, projectId), {
        method: 'POST',
        body: JSON.stringify(snapshotWriteBody(readRepositoryFields(form))),
      })
      setSnapshots((current) => [created, ...current.filter((item) => item.id !== created.id)])
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create snapshot')
    } finally {
      setBusy(false)
    }
  }

  function onValidate(event: MouseEvent<HTMLButtonElement>) {
    const form = event.currentTarget.form
    if (!form || !form.reportValidity()) return
    void handleValidate(form)
  }

  function onSnapshot(event: MouseEvent<HTMLButtonElement>) {
    const form = event.currentTarget.form
    if (!form || !form.reportValidity()) return
    void handleSnapshot(form)
  }

  const fieldKey = `${projectId}:${repository?.updatedAt ?? 'new'}`
  const status = repository ? <RepositoryStatus repository={repository} /> : null
  const snapshotList = <SnapshotList snapshots={snapshots} canEdit={canEdit} />
  const loadError = error ? <p className="error">{error}</p> : null

  if (!canEdit) {
    return (
      <div className="task-detail">
        <h3>Repository</h3>
        <p className="muted small">{projectName}</p>
        {!loaded ? <p className="muted">Loading repository…</p> : null}
        {loadError}
        {loaded && !repository ? (
          <p className="empty-state" role="status">
            No repository URL yet. Ask a developer to save one.
          </p>
        ) : (
          status
        )}
        {repository ? (
          <p className="muted small">
            {repository.gitUrl}
            {' · '}
            {repository.defaultBranch}
            {repository.commitSha ? ` · ${repository.commitSha}` : ''}
          </p>
        ) : null}
        <h3>Snapshots</h3>
        {loaded ? snapshotList : null}
      </div>
    )
  }

  return (
    <form className="form compact" onSubmit={handleSave}>
      <h3>Repository</h3>
      <p className="muted small">
        Save the repository URL for {projectName}. Validate checks that GitHub repository. Create
        snapshot records the URL, branch, and commit.
      </p>
      {!loaded ? <p className="muted">Loading repository…</p> : null}
      {loadError}
      {loaded && !repository ? (
        <p className="empty-state" role="status">
          No repository URL yet. Save a GitHub URL below, then validate it.
        </p>
      ) : (
        status
      )}
      <label>
        Repository URL
        <input
          key={`${fieldKey}:url`}
          name="gitUrl"
          required
          maxLength={500}
          placeholder="https://github.com/owner/repo"
          defaultValue={repository?.gitUrl ?? ''}
        />
      </label>
      <label>
        Default branch
        <input
          key={`${fieldKey}:branch`}
          name="defaultBranch"
          required
          maxLength={200}
          defaultValue={repository?.defaultBranch || 'main'}
        />
      </label>
      <label>
        Commit SHA
        <input
          key={`${fieldKey}:sha`}
          name="commitSha"
          pattern="[0-9a-fA-F]{40}"
          placeholder="40 hex chars"
          defaultValue={repository?.commitSha ?? ''}
        />
      </label>
      <div className="action-row">
        <button className="cta" type="submit" disabled={!loaded || busy}>
          Save repository
        </button>
        <button className="cta secondary" type="button" disabled={!loaded || busy} onClick={onValidate}>
          Validate
        </button>
        <button className="cta secondary" type="button" disabled={!loaded || busy} onClick={onSnapshot}>
          Create snapshot
        </button>
      </div>
      <h3>Snapshots</h3>
      {loaded ? snapshotList : null}
    </form>
  )
}
