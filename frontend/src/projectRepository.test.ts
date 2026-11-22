import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import {
  isMissingRepository,
  projectRepositoryPath,
  projectRepositoryValidatePath,
  projectSnapshotsPath,
  repositoryFieldsDiffer,
  repositoryStatusTone,
  repositoryWriteBody,
  snapshotWriteBody,
} from './projectRepository.ts'

const formSource = readFileSync(new URL('./ProjectRepositoryForm.tsx', import.meta.url), 'utf8')
const appSource = readFileSync(new URL('./App.tsx', import.meta.url), 'utf8')

test('repository writes use the existing project routes', () => {
  assert.equal(
    projectRepositoryPath('org', 'project'),
    '/api/v1/orgs/org/projects/project/repository',
  )
  assert.equal(
    projectRepositoryValidatePath('org', 'project'),
    '/api/v1/orgs/org/projects/project/repository/validate',
  )
  assert.equal(
    projectSnapshotsPath('org', 'project'),
    '/api/v1/orgs/org/projects/project/snapshots',
  )
})

test('saving a URL does not validate and keeps fields the form does not edit', () => {
  const body = repositoryWriteBody(
    { gitUrl: ' https://github.com/owner/repo ', defaultBranch: ' main ', commitSha: '' },
    {
      language: 'java',
      buildCommand: 'mvn -q package',
      testCommand: 'mvn -q test',
      secretRefId: 'secret-1',
    },
  )
  assert.equal(body.gitUrl, 'https://github.com/owner/repo')
  assert.equal(body.defaultBranch, 'main')
  assert.equal(body.commitSha, '')
  assert.equal(body.validate, false)
  assert.equal(body.language, 'java')
  assert.equal(body.secretRefId, 'secret-1')
})

test('a snapshot records the URL, branch, and commit from the form', () => {
  assert.deepEqual(
    snapshotWriteBody({
      gitUrl: 'https://github.com/owner/repo',
      defaultBranch: 'main',
      commitSha: 'a'.repeat(40),
    }),
    {
      gitUrl: 'https://github.com/owner/repo',
      branch: 'main',
      commitSha: 'a'.repeat(40),
    },
  )
})

test('a missing repository is an empty configuration, not a failed load', () => {
  assert.equal(isMissingRepository(new Error('Repository configuration not found')), true)
  assert.equal(isMissingRepository(new Error('Project not found')), false)
  assert.equal(isMissingRepository('Repository configuration not found'), false)
})

test('validation status uses the existing chip tones', () => {
  assert.equal(repositoryStatusTone('VALID'), 'success')
  assert.equal(repositoryStatusTone('INVALID'), 'danger')
  assert.equal(repositoryStatusTone('SKIPPED'), 'warning')
  assert.equal(repositoryStatusTone('UNKNOWN'), 'neutral')
  assert.equal(repositoryStatusTone(null), 'neutral')
})

test('validate reuses a saved URL and writes when the form changed', () => {
  const saved = { gitUrl: 'https://github.com/owner/repo', defaultBranch: 'main', commitSha: null }
  assert.equal(
    repositoryFieldsDiffer(
      { gitUrl: 'https://github.com/owner/repo', defaultBranch: 'main', commitSha: '' },
      saved,
    ),
    false,
  )
  assert.equal(
    repositoryFieldsDiffer(
      { gitUrl: 'https://github.com/other/repo', defaultBranch: 'main', commitSha: '' },
      saved,
    ),
    true,
  )
  assert.equal(
    repositoryFieldsDiffer(
      { gitUrl: 'https://github.com/owner/repo', defaultBranch: 'main', commitSha: '' },
      null,
    ),
    true,
  )
})

test('the project form validates and snapshots through the existing API', () => {
  assert.match(formSource, /projectRepositoryPath/)
  assert.match(formSource, /projectRepositoryValidatePath/)
  assert.match(formSource, /projectSnapshotsPath/)
  assert.match(formSource, /method: 'PUT'/)
  assert.match(formSource, /method: 'POST'/)
  assert.match(formSource, /<h3>Repository<\/h3>/)
  assert.match(formSource, /name="gitUrl"/)
  assert.match(formSource, /name="defaultBranch"/)
  assert.match(formSource, /name="commitSha"/)
  assert.match(formSource, /https:\/\/github\.com\/owner\/repo/)
  assert.match(formSource, />\s*Validate\s*</)
  assert.match(formSource, />\s*Create snapshot\s*</)
  assert.doesNotMatch(formSource, /\/api\/v2\//)
  assert.doesNotMatch(formSource, /secretRefId/)
})

test('the repository form stays on the projects tab', () => {
  const projectsTab = appSource.slice(appSource.indexOf("tab === 'projects'"))
  assert.match(projectsTab, /<ProjectRepositoryForm/)
  assert.match(projectsTab, /<h3>Create project<\/h3>/)
  assert.doesNotMatch(appSource, /tab === 'repository'/)
})
