import assert from 'node:assert/strict'
import { test } from 'node:test'
import { EMPTY, SELENIUM_EMPTY_ANCHORS } from './emptyCopy.ts'

const ACTION = /\b(create|select|enqueue|publish|add|clear|refresh|run|record|ask|open|use|pick|leave)\b/i

test('signed-in empty states name the next action', () => {
  for (const [key, message] of Object.entries(EMPTY)) {
    assert.match(message, ACTION, key)
  }
})

test('selenium empty-state anchors stay intact', () => {
  assert.match(EMPTY.overview, new RegExp(SELENIUM_EMPTY_ANCHORS[0]))
  assert.match(EMPTY.leaderboard, new RegExp(SELENIUM_EMPTY_ANCHORS[1].replace(/[.]/g, '\\.')))
  assert.match(EMPTY.tasks, new RegExp(SELENIUM_EMPTY_ANCHORS[2]))
})
