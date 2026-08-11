import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "./AuthContext";

/** Redirects to /login if not authenticated at all, or to /tenants if authenticated but no tenant selected yet (requireTenant). */
export function RequireAuth({ children, requireTenant = true }: { children: ReactNode; requireTenant?: boolean }) {
  const { isAuthenticated, hasTenant, initializing } = useAuth();
  const location = useLocation();

  // A persisted token is still being validated/rehydrated (see AuthContext) — wait rather
  // than redirecting to /login on every refresh before that check has finished.
  if (initializing) {
    return null;
  }
  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }
  if (requireTenant && !hasTenant) {
    return <Navigate to="/tenants" replace />;
  }
  return <>{children}</>;
}
