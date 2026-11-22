import type { FormEvent } from 'react'
import type { Member, Org } from './api'
import { EMPTY } from './emptyCopy'
import { ADD_MEMBER_ROLES, canRemoveMember, rolesAssignableBy } from './memberRoles'

type MembersLoadState = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

export function MembersPanel({
  selectedOrg,
  role,
  members,
  membersForOrgId,
  membersState,
  membersError,
  manageMembers,
  busy,
  onRefresh,
  onAdd,
  onRoleChange,
  onRemove,
}: {
  selectedOrg: Org | undefined
  role: string | undefined
  members: Member[]
  membersForOrgId: string
  membersState: MembersLoadState
  membersError: string | null
  manageMembers: boolean
  busy: boolean
  onRefresh: () => void
  onAdd: (event: FormEvent<HTMLFormElement>) => void
  onRoleChange: (member: Member, role: string) => void
  onRemove: (member: Member) => void
}) {
  if (!selectedOrg) {
    return (
      <>
        <h2>Members</h2>
        <p className="empty-state" role="status">
          {EMPTY.selectOrgContinue}
        </p>
      </>
    )
  }

  const loaded =
    membersForOrgId === selectedOrg.id && membersState !== 'idle' && membersState !== 'loading'
  if (!loaded) {
    return (
      <p className="muted" role="status">
        Loading members…
      </p>
    )
  }

  const ownerCount = members.filter((member) => member.role === 'OWNER').length

  return (
    <>
      <h2>Members · {selectedOrg.name}</h2>
      <p className="muted small">Your role: {role || 'unknown'}</p>
      <button type="button" className="cta secondary" disabled={busy} onClick={onRefresh}>
        Refresh members
      </button>
      {membersState === 'error' && members.length === 0 ? (
        <p className="error" role="alert">
          {membersError || 'Could not load members'}
        </p>
      ) : members.length === 0 ? (
        <p className="empty-state" role="status">
          {manageMembers ? EMPTY.members : EMPTY.membersReadOnly}
        </p>
      ) : (
        <>
          {membersError && (
            <p className="error" role="alert">
              {membersError}
            </p>
          )}
          <ul className="list plain">
            {members.map((member) => {
              const assignable = rolesAssignableBy(role, member.role)
              return (
                <li key={member.userId}>
                  <strong>{member.displayName}</strong>
                  <div className="muted small">{member.email}</div>
                  <div className="muted small">Joined {new Date(member.createdAt).toLocaleString()}</div>
                  {assignable.length > 0 ? (
                    <label>
                      Role
                      <select
                        name="role"
                        aria-label={`Role for ${member.displayName}`}
                        value={member.role}
                        disabled={busy}
                        onChange={(event) => onRoleChange(member, event.target.value)}
                      >
                        {assignable.map((option) => (
                          <option key={option} value={option}>
                            {option}
                          </option>
                        ))}
                      </select>
                    </label>
                  ) : (
                    <div>
                      Role <span className="badge">{member.role}</span>
                    </div>
                  )}
                  {manageMembers && canRemoveMember(role, member.role, ownerCount) && (
                    <button
                      type="button"
                      className="cta secondary"
                      disabled={busy}
                      onClick={() => onRemove(member)}
                    >
                      Remove
                    </button>
                  )}
                </li>
              )
            })}
          </ul>
        </>
      )}
      {manageMembers ? (
        <form className="form compact" onSubmit={onAdd}>
          <h3>Add member</h3>
          <p className="muted small">
            Add an existing user by email. The account must already be registered.
          </p>
          <label>
            Email
            <input name="email" type="email" required autoComplete="off" maxLength={254} />
          </label>
          <label>
            Role
            <select name="role" defaultValue="VIEWER" required>
              {ADD_MEMBER_ROLES.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </label>
          <button className="cta" type="submit" disabled={busy}>
            Add member
          </button>
        </form>
      ) : (
        members.length > 0 && (
          <p className="muted small" role="status">
            {EMPTY.membersForbidden}
          </p>
        )
      )}
    </>
  )
}
