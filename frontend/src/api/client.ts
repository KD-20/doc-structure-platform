import axios from "axios";
import { getDeviceId } from "../utils/deviceId";

const STORAGE_KEY = "docstructure_token";

// Session-scoped persistence, not localStorage: survives a page refresh (fixing the
// every-refresh-bounces-to-login problem) but still clears when the tab/browser closes,
// so a stolen token doesn't sit around indefinitely. Full refresh-token rotation with
// server-side revocation is real additional scope, deliberately deferred — see
// docs/DECISIONS.md §7. AuthContext rehydrates identity/tenant state from this token on boot.
let currentToken: string | null = sessionStorage.getItem(STORAGE_KEY);

export function setAuthToken(token: string | null) {
  currentToken = token;
  if (token) {
    sessionStorage.setItem(STORAGE_KEY, token);
  } else {
    sessionStorage.removeItem(STORAGE_KEY);
  }
}

export function getAuthToken(): string | null {
  return currentToken;
}

export const apiClient = axios.create({ baseURL: "/api" });

apiClient.interceptors.request.use((config) => {
  if (currentToken) {
    config.headers.Authorization = `Bearer ${currentToken}`;
  }
  return config;
});

// A 401 on an authenticated request means the token expired or was rejected server-side —
// clear it and bounce to login instead of leaving every page showing a raw error banner.
// Guarded on currentToken so a plain wrong-password response on the login form itself
// (no token set yet) doesn't trigger this.
apiClient.interceptors.response.use(
  (res) => res,
  (err) => {
    if (axios.isAxiosError(err) && err.response?.status === 401 && currentToken) {
      setAuthToken(null);
      if (window.location.pathname !== "/login") {
        window.location.href = "/login";
      }
    }
    return Promise.reject(err);
  },
);

export function guestClient(guestToken: string) {
  const instance = axios.create({ baseURL: "/api" });
  instance.interceptors.request.use((config) => {
    config.headers["X-Guest-Token"] = guestToken;
    return config;
  });
  return instance;
}

// Anonymous "try it before you subscribe" uploads — no login, no share-link token, just this
// browser's device id. See backend PublicDemoController/PublicDemoService.
export const publicClient = axios.create({ baseURL: "/api/public" });

publicClient.interceptors.request.use((config) => {
  config.headers["X-Device-Id"] = getDeviceId();
  return config;
});

export function errorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as { message?: string } | undefined;
    return data?.message ?? err.message;
  }
  return String(err);
}
