import { useEffect, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { apiClient, errorMessage } from "../api/client";
import type { CreateTenantResponse } from "../api/types";
import { BackButton } from "../components/BackButton";

export function TenantsPage() {
  const { tenants, selectTenant, adoptCreatedTenant, logout, email } = useAuth();
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [selectedTenantId, setSelectedTenantId] = useState(tenants[0]?.tenantId ?? "");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // tenants can arrive after mount (e.g. AuthContext rehydrating on a page refresh), so the
  // useState initializer above alone isn't enough — pick a default once the list is actually
  // populated, without overriding a choice the user already made.
  useEffect(() => {
    if (!selectedTenantId && tenants.length > 0) {
      setSelectedTenantId(tenants[0].tenantId);
    }
  }, [tenants, selectedTenantId]);

  async function handleSelect(tenantId: string) {
    setError(null);
    try {
      await selectTenant(tenantId);
      navigate(`/t/${tenantId}/dashboard`);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const { data } = await apiClient.post<CreateTenantResponse>("/tenants", { name, slug });
      adoptCreatedTenant(data);
      navigate(`/t/${data.tenant.id}/dashboard`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="centered-auth">
      <div className="card" style={{ width: 480 }}>
        <BackButton fallback="/" />
        <div className="topbar">
          <h1 style={{ fontSize: 20 }}>Your tenants</h1>
          <button className="secondary" onClick={logout}>
            Log out ({email})
          </button>
        </div>
        {error && <div className="error-banner">{error}</div>}

        {tenants.length === 0 && <p className="muted">You don't belong to any tenant yet.</p>}
        {tenants.length > 0 && (
          <div className="form-row">
            <label>Select a tenant</label>
            <div style={{ display: "flex", gap: 8 }}>
              <select
                style={{ flex: 1 }}
                value={selectedTenantId}
                onChange={(e) => setSelectedTenantId(e.target.value)}
              >
                {tenants.map((t) => (
                  <option key={t.tenantId} value={t.tenantId}>
                    {t.tenantName} — {t.role}
                  </option>
                ))}
              </select>
              <button onClick={() => handleSelect(selectedTenantId)} disabled={!selectedTenantId}>
                Open
              </button>
            </div>
          </div>
        )}

        <h2 style={{ fontSize: 15, marginTop: 20 }}>Create a new tenant</h2>
        <form onSubmit={handleCreate}>
          <div className="form-row">
            <label>Name</label>
            <input value={name} onChange={(e) => setName(e.target.value)} required />
          </div>
          <div className="form-row">
            <label>Slug (lowercase, hyphens)</label>
            <input value={slug} onChange={(e) => setSlug(e.target.value)} pattern="^[a-z0-9]([a-z0-9-]{0,48}[a-z0-9])?$" required />
          </div>
          <button type="submit" disabled={loading}>
            {loading ? "Creating..." : "Create tenant"}
          </button>
        </form>
      </div>
    </div>
  );
}
