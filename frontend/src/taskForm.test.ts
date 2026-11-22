import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const source = readFileSync(new URL('./App.tsx', import.meta.url), 'utf8')

test('a task names a github repository and commit', () => {
  assert.match(source, /name="gitUrl"/)
  assert.match(source, /name="baselineCommitSha"/)
  assert.match(source, /https:\/\/github\.com\/owner\/repo/)
})

test('there is no folder upload of a frontend', () => {
  assert.doesNotMatch(source, /webkitdirectory/)
  assert.doesNotMatch(source, /type="file"/)
  assert.doesNotMatch(source, /directory/)
})
