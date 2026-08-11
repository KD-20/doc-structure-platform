import { Navigate, Route, Routes } from "react-router-dom";
import { RequireAuth } from "./auth/RequireAuth";
import { Layout } from "./components/Layout";
import { LandingPage } from "./pages/LandingPage";
import { LoginPage } from "./pages/LoginPage";
import { RegisterPage } from "./pages/RegisterPage";
import { TenantsPage } from "./pages/TenantsPage";
import { DashboardPage } from "./pages/DashboardPage";
import { DocumentsPage } from "./pages/DocumentsPage";
import { DocumentDetailPage } from "./pages/DocumentDetailPage";
import { RuleSetsPage } from "./pages/RuleSetsPage";
import { RuleSetEditorPage } from "./pages/RuleSetEditorPage";
import { SearchPage } from "./pages/SearchPage";
import { AuditLogPage } from "./pages/AuditLogPage";
import { MembersPage } from "./pages/MembersPage";
import { GuestLinksPage } from "./pages/GuestLinksPage";
import { GuestDocumentPage } from "./pages/GuestDocumentPage";
import { GuestSearchPage } from "./pages/GuestSearchPage";
import { TryItPage } from "./pages/TryItPage";

function Tenant({ children }: { children: React.ReactNode }) {
  return (
    <RequireAuth>
      <Layout>{children}</Layout>
    </RequireAuth>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route
        path="/tenants"
        element={
          <RequireAuth requireTenant={false}>
            <TenantsPage />
          </RequireAuth>
        }
      />

      <Route path="/t/:tenantId/dashboard" element={<Tenant><DashboardPage /></Tenant>} />
      <Route path="/t/:tenantId/documents" element={<Tenant><DocumentsPage /></Tenant>} />
      <Route path="/t/:tenantId/documents/:documentId" element={<Tenant><DocumentDetailPage /></Tenant>} />
      <Route path="/t/:tenantId/rule-sets" element={<Tenant><RuleSetsPage /></Tenant>} />
      <Route path="/t/:tenantId/rule-sets/:docType/edit" element={<Tenant><RuleSetEditorPage /></Tenant>} />
      <Route path="/t/:tenantId/search" element={<Tenant><SearchPage /></Tenant>} />
      <Route path="/t/:tenantId/audit-log" element={<Tenant><AuditLogPage /></Tenant>} />
      <Route path="/t/:tenantId/settings/members" element={<Tenant><MembersPage /></Tenant>} />
      <Route path="/t/:tenantId/settings/guest-links" element={<Tenant><GuestLinksPage /></Tenant>} />

      <Route path="/guest/:token/documents/:documentId" element={<GuestDocumentPage />} />
      <Route path="/guest/:token/search" element={<GuestSearchPage />} />
      <Route path="/try" element={<TryItPage />} />

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
