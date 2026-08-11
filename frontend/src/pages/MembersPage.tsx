import { useEffect, useState, type FormEvent } from "react";
import { useParams } from "react-router-dom";
import { apiClient, errorMessage } from "../api/client";
import type { Member, MembershipRole } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { isAdminRole } from "../auth/roles";

const ROLES: MembershipRole[] = ["VIEWER", "EDITOR", "ADMIN", "OWNER"];

function initials(fullName: string): string {
  const parts = fullName.trim().split(/\s+/);
  const chars = parts.length > 1 ? [parts[0][0], parts[parts.length - 1][0]] : [parts[0]?.[0] ?? "?"];
  return chars.join("").toUpperCase();
}

export function MembersPage() {
  const { tenantId } = useParams();
  const { role: myRole } = useAuth();
  // Nav hides this page's link for non-admins already (see Layout), but that doesn't stop
  // direct URL navigation — mirrors the backend's hasRole('ADMIN') on add/role-change/remove.
  const isAdmin = isAdminRole(myRole);
  const [members, setMembers] = useState<Member[]>([]);
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<MembershipRole>("VIEWER");
  const [error, setError] = useState<string | null>(null);

  async function load() {
    try {
      const { data } = await apiClient.get<Member[]>(`/tenants/${tenantId}/members`);
      setMembers(data);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenantId]);

  async function handleAdd(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      await apiClient.post(`/tenants/${tenantId}/members`, { email, role });
      setEmail("");
      await load();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function handleRoleChange(userId: string, newRole: MembershipRole) {
    setError(null);
    try {
      await apiClient.patch(`/tenants/${tenantId}/members/${userId}/role`, { role: newRole });
      await load();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function handleRemove(userId: string) {
    setError(null);
    try {
      await apiClient.delete(`/tenants/${tenantId}/members/${userId}`);
      await load();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <div>
      <h1>Members</h1>
      {error && <div className="error-banner">{error}</div>}

      {isAdmin && (
      <div className="card">
        <form onSubmit={handleAdd} className="member-add-row">
          <input
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            type="email"
            placeholder="Member email"
            required
          />
          <select className="role-select" value={role} onChange={(e) => setRole(e.target.value as MembershipRole)}>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </select>
          <button type="submit">Add</button>
        </form>
      </div>
      )}

      <div className="card">
        <div className="item-bar-list">
          {members.map((m) => (
            <div className="item-bar static" key={m.userId}>
              <div className="member-avatar">{initials(m.fullName)}</div>
              <div className="item-bar-main">
                <div className="item-bar-title">{m.fullName}</div>
                <div className="item-bar-meta">{m.email}</div>
              </div>
              <div className="item-bar-right">
                {isAdmin ? (
                  <>
                    <select
                      className="role-select"
                      value={m.role}
                      onChange={(e) => handleRoleChange(m.userId, e.target.value as MembershipRole)}
                    >
                      {ROLES.map((r) => (
                        <option key={r} value={r}>
                          {r}
                        </option>
                      ))}
                    </select>
                    <button className="danger icon-btn" title="Remove" onClick={() => handleRemove(m.userId)}>
                      ×
                    </button>
                  </>
                ) : (
                  <span className="badge">{m.role}</span>
                )}
              </div>
            </div>
          ))}
        </div>
        {members.length === 0 && <p className="muted">No members yet.</p>}
      </div>
    </div>
  );
}
