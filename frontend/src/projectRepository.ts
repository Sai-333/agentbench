import type { ProjectRepository } from './api'

/** Detail returned when a project has no repository row yet. */
export const MISSING_REPOSITORY = 'Repository configuration not found'

export function isMissingRepository(err: unknown): boolean {
  return err instanceof Error && err.message === MISSING_REPOSITORY
}

export function projectRepositoryPath(orgId: string, projectId: string): string {
  return `/api/v1/orgs/${orgId}/projects/${projectId}/repository`
}

export function projectRepositoryValidatePath(orgId: string, projectId: string): string {
  return `${projectRepositoryPath(orgId, projectId)}/validate`
}

export function projectSnapshotsPath(orgId: string, projectId: string): string {
  return `/api/v1/orgs/${orgId}/projects/${projectId}/snapshots`
}

export type RepositoryFields = {
  gitUrl: string
  defaultBranch: string
  commitSha: string
}

/** PUT body for the existing repository route. Validation stays on the validate route. */
export function repositoryWriteBody(
  fields: RepositoryFields,
  existing: Pick<ProjectRepository, 'language' | 'buildCommand' | 'testCommand' | 'secretRefId'> | null,
) {
  return {
    gitUrl: fields.gitUrl.trim(),
    defaultBranch: fields.defaultBranch.trim(),
    commitSha: fields.commitSha.trim(),
    language: existing?.language ?? null,
    buildCommand: existing?.buildCommand ?? null,
    testCommand: existing?.testCommand ?? null,
    secretRefId: existing?.secretRefId ?? null,
    validate: false as const,
  }
}

/** POST body for the existing snapshot route. Digest and storage stay unset. */
export function snapshotWriteBody(fields: RepositoryFields) {
  return {
    gitUrl: fields.gitUrl.trim(),
    branch: fields.defaultBranch.trim(),
    commitSha: fields.commitSha.trim(),
  }
}

export function repositoryStatusTone(
  status: string | null | undefined,
): 'success' | 'danger' | 'warning' | 'neutral' {
  if (status === 'VALID') return 'success'
  if (status === 'INVALID') return 'danger'
  if (status === 'SKIPPED') return 'warning'
  return 'neutral'
}

export function readRepositoryFields(form: HTMLFormElement): RepositoryFields {
  const data = new FormData(form)
  return {
    gitUrl: String(data.get('gitUrl') || ''),
    defaultBranch: String(data.get('defaultBranch') || ''),
    commitSha: String(data.get('commitSha') || ''),
  }
}

export function repositoryFieldsDiffer(
  fields: RepositoryFields,
  existing: Pick<ProjectRepository, 'gitUrl' | 'defaultBranch' | 'commitSha'> | null,
): boolean {
  if (!existing) return true
  return (
    fields.gitUrl.trim() !== existing.gitUrl ||
    fields.defaultBranch.trim() !== existing.defaultBranch ||
    fields.commitSha.trim() !== (existing.commitSha ?? '')
  )
}
