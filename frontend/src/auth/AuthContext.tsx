import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { apiClient, getAuthToken, setAuthToken } from "../api/client";
import type {
  CreateTenantResponse,
  LoginResponse,
  MembershipRole,
  SelectTenantResponse,
  TenantMembershipSummary,
} from "../api/types";

interface AuthState {
  token: string | null;
  userId: string | null;
  email: string | null;
  tenantId: string | null;
  tenantName: string | null;
  role: MembershipRole | null;
  tenants: TenantMembershipSummary[];
}

interface AuthContextValue extends AuthState {
  isAuthenticated: boolean;
  hasTenant: boolean;
  // True only while rehydrating a persisted token on first load — RequireAuth waits on this
  // instead of redirecting to /login before that check has had a chance to run.
  initializing: boolean;
  login: (email: string, password: string) => Promise<void>;
  selectTenant: (tenantId: string) => Promise<void>;
  adoptCreatedTenant: (resp: CreateTenantResponse) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

const EMPTY_STATE: AuthState = {
  token: null,
  userId: null,
  email: null,
  tenantId: null,
  tenantName: null,
  role: null,
  tenants: [],
};

interface TokenClaims {
  sub: string;
  email?: string;
  tenantId?: string;
  role?: MembershipRole;
  exp?: number;
}

function decodeToken(token: string): TokenClaims | null {
  try {
    return JSON.parse(atob(token.split(".")[1]));
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>(EMPTY_STATE);
  const [initializing, setInitializing] = useState(true);

  // Runs once on app load: a token surviving in sessionStorage from before a refresh needs
  // its identity/tenant state rebuilt from scratch (that part was only ever in memory). The
  // JWT itself carries sub/email/tenantId/role once a tenant's been selected — GET /tenants
  // both re-fetches the full membership list (for tenant-switching) and doubles as a
  // liveness check, since an expired/rejected token fails it and gets discarded here rather
  // than surfacing as a raw error on whatever page happens to render first.
  useEffect(() => {
    const token = getAuthToken();
    if (!token) {
      setInitializing(false);
      return;
    }
    const claims = decodeToken(token);
    if (!claims || (claims.exp && claims.exp * 1000 < Date.now())) {
      setAuthToken(null);
      setInitializing(false);
      return;
    }
    apiClient
      .get<TenantMembershipSummary[]>("/tenants")
      .then((res) => {
        setState({
          token,
          userId: claims.sub,
          email: claims.email ?? null,
          tenantId: claims.tenantId ?? null,
          tenantName: claims.tenantId
            ? (res.data.find((t) => t.tenantId === claims.tenantId)?.tenantName ?? null)
            : null,
          role: claims.role ?? null,
          tenants: res.data,
        });
      })
      .catch(() => {
        setAuthToken(null);
      })
      .finally(() => setInitializing(false));
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const { data } = await apiClient.post<LoginResponse>("/auth/login", { email, password });
    setAuthToken(data.token);
    setState({
      token: data.token,
      userId: decodeToken(data.token)?.sub ?? null,
      email,
      tenantId: null,
      tenantName: null,
      role: null,
      tenants: data.tenants,
    });
  }, []);

  const selectTenant = useCallback(async (tenantId: string) => {
    const { data } = await apiClient.post<SelectTenantResponse>(`/auth/select-tenant/${tenantId}`);
    setAuthToken(data.token);
    setState((prev) => ({
      ...prev,
      token: data.token,
      tenantId: data.tenantId,
      tenantName: prev.tenants.find((t) => t.tenantId === data.tenantId)?.tenantName ?? null,
      role: data.role,
    }));
  }, []);

  const adoptCreatedTenant = useCallback((resp: CreateTenantResponse) => {
    setAuthToken(resp.token);
    setState((prev) => ({
      ...prev,
      token: resp.token,
      tenantId: resp.tenant.id,
      tenantName: resp.tenant.name,
      role: "OWNER",
      tenants: [
        ...prev.tenants.filter((t) => t.tenantId !== resp.tenant.id),
        { tenantId: resp.tenant.id, tenantName: resp.tenant.name, role: "OWNER" },
      ],
    }));
  }, []);

  const logout = useCallback(() => {
    setAuthToken(null);
    setState(EMPTY_STATE);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      ...state,
      isAuthenticated: state.token !== null,
      hasTenant: state.tenantId !== null,
      initializing,
      login,
      selectTenant,
      adoptCreatedTenant,
      logout,
    }),
    [state, initializing, login, selectTenant, adoptCreatedTenant, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return ctx;
}
