/** Roles accepted by POST /api/v1/orgs/{orgId}/members. OWNER is not assignable on add. */
export const ADD_MEMBER_ROLES = ['ADMIN', 'DEVELOPER', 'VIEWER'] as const

/** Roles accepted by PUT /api/v1/orgs/{orgId}/members/{userId}. */
export const MEMBER_ROLES = ['OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER'] as const

export function canManageMembers(role: string | undefined): boolean {
  return role === 'OWNER' || role === 'ADMIN'
}

/**
 * Roles this actor may write onto the target membership.
 * An empty list means the role stays read-only (matches OrganizationService).
 */
export function rolesAssignableBy(actorRole: string | undefined, targetRole: string): readonly string[] {
  if (actorRole === 'OWNER') return MEMBER_ROLES
  if (actorRole === 'ADMIN' && targetRole !== 'OWNER') return ADD_MEMBER_ROLES
  return []
}

/** Remove is ADMIN+, and an OWNER can be removed only by an OWNER when another OWNER remains. */
export function canRemoveMember(
  actorRole: string | undefined,
  targetRole: string,
  ownerCount: number,
): boolean {
  if (!canManageMembers(actorRole)) return false
  if (targetRole === 'OWNER' && (actorRole !== 'OWNER' || ownerCount <= 1)) return false
  return true
}
