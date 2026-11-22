import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  ADD_MEMBER_ROLES,
  canManageMembers,
  canRemoveMember,
  rolesAssignableBy,
} from './memberRoles.ts'

test('only owner and admin manage members', () => {
  assert.equal(canManageMembers('OWNER'), true)
  assert.equal(canManageMembers('ADMIN'), true)
  assert.equal(canManageMembers('DEVELOPER'), false)
  assert.equal(canManageMembers('VIEWER'), false)
  assert.equal(canManageMembers(undefined), false)
})

test('add-member roles exclude owner', () => {
  assert.deepEqual([...ADD_MEMBER_ROLES], ['ADMIN', 'DEVELOPER', 'VIEWER'])
})

test('owner can assign every role and admin cannot change an owner', () => {
  assert.deepEqual([...rolesAssignableBy('OWNER', 'VIEWER')], ['OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER'])
  assert.deepEqual([...rolesAssignableBy('ADMIN', 'DEVELOPER')], ['ADMIN', 'DEVELOPER', 'VIEWER'])
  assert.deepEqual([...rolesAssignableBy('ADMIN', 'OWNER')], [])
  assert.deepEqual([...rolesAssignableBy('VIEWER', 'VIEWER')], [])
  assert.deepEqual([...rolesAssignableBy(undefined, 'ADMIN')], [])
})

test('the last owner cannot be removed and admin cannot remove an owner', () => {
  assert.equal(canRemoveMember('OWNER', 'OWNER', 1), false)
  assert.equal(canRemoveMember('OWNER', 'OWNER', 2), true)
  assert.equal(canRemoveMember('ADMIN', 'OWNER', 2), false)
  assert.equal(canRemoveMember('ADMIN', 'VIEWER', 1), true)
  assert.equal(canRemoveMember('OWNER', 'DEVELOPER', 1), true)
  assert.equal(canRemoveMember('DEVELOPER', 'VIEWER', 1), false)
  assert.equal(canRemoveMember(undefined, 'VIEWER', 1), false)
})
