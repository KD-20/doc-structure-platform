import type { ReactNode } from "react";
import { NavLink, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { isAdminRole } from "../auth/roles";

export function Layout({ children }: { children: ReactNode }) {
  const { tenantId, tenantName, role, email, logout } = useAuth();
  const navigate = useNavigate();
  const isAdmin = isAdminRole(role);

  function handleLogout() {
    logout();
    navigate("/login");
  }

  const base = `/t/${tenantId}`;

  return (
    <div className="app-shell">
      <nav className="sidebar">
        <h2>{tenantName ?? "Tenant"}</h2>
        <NavLink to={`${base}/dashboard`}>Dashboard</NavLink>
        <NavLink to={`${base}/documents`}>Documents</NavLink>
        <NavLink to={`${base}/rule-sets`}>Rule Sets</NavLink>
        <NavLink to={`${base}/search`}>Search</NavLink>
        {isAdmin && <NavLink to={`${base}/settings/members`}>Members</NavLink>}
        {isAdmin && <NavLink to={`${base}/settings/guest-links`}>Guest Links</NavLink>}
        {isAdmin && <NavLink to={`${base}/audit-log`}>Audit Log</NavLink>}
        <div className="sidebar-footer">
          <div className="sidebar-account">
            <div className="muted">{email}</div>
            <span className="badge">{role}</span>
          </div>
          <button className="sidebar-action" onClick={() => navigate("/tenants")}>
            Switch tenant
          </button>
          <button className="sidebar-action danger-text" onClick={handleLogout}>
            Log out
          </button>
        </div>
      </nav>
      <main className="main">{children}</main>
    </div>
  );
}
