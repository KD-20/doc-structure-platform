import type { MembershipRole } from "../api/types";

const ADMIN_ROLES = new Set<MembershipRole>(["OWNER", "ADMIN"]);
const EDITOR_OR_ABOVE_ROLES = new Set<MembershipRole>(["OWNER", "ADMIN", "EDITOR"]);

/** Mirrors the backend's hasRole('ADMIN') checks (OWNER inherits ADMIN via the role hierarchy — see RoleHierarchyConfig) — used to hide actions the API would 403 on anyway, not as the actual enforcement (that's always server-side). */
export function isAdminRole(role: MembershipRole | null): boolean {
  return role !== null && ADMIN_ROLES.has(role);
}

/** Mirrors the backend's hasRole('EDITOR') checks (upload, doc-type changes, ...). */
export function isEditorRole(role: MembershipRole | null): boolean {
  return role !== null && EDITOR_OR_ABOVE_ROLES.has(role);
}
