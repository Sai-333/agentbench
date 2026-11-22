import assert from 'node:assert/strict'
import { test } from 'node:test'
import { resetForm } from './formUtils.ts'

test('resetForm calls reset on the form element', () => {
  let resetCalls = 0
  const form = {
    reset() {
      resetCalls += 1
    },
  } as HTMLFormElement

  resetForm(form)

  assert.equal(resetCalls, 1)
})

test('resetForm does nothing when form is null', () => {
  assert.doesNotThrow(() => resetForm(null))
})

test('resetForm does nothing when form is undefined', () => {
  assert.doesNotThrow(() => resetForm(undefined))
})
