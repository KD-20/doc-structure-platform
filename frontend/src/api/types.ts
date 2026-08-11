export type MembershipRole = "OWNER" | "ADMIN" | "EDITOR" | "VIEWER";

export interface TenantMembershipSummary {
  tenantId: string;
  tenantName: string;
  role: MembershipRole;
}

export interface LoginResponse {
  token: string;
  tenants: TenantMembershipSummary[];
}

export interface SelectTenantResponse {
  token: string;
  tenantId: string;
  role: MembershipRole;
}

export interface CurrentUser {
  userId: string;
  email: string;
  tenantId: string | null;
  role: MembershipRole | null;
}

export interface Tenant {
  id: string;
  name: string;
  slug: string;
  status: "ACTIVE" | "SUSPENDED";
}

export interface CreateTenantResponse {
  tenant: Tenant;
  token: string;
}

export interface Member {
  userId: string;
  email: string;
  fullName: string;
  role: MembershipRole;
}

export type DocumentStatus =
  | "UPLOADED"
  | "TEXT_EXTRACTED"
  | "TEXT_EXTRACTION_FAILED"
  | "STRUCTURED"
  | "STRUCTURING_FAILED";

export interface DocumentSummary {
  id: string;
  filename: string;
  contentType: string | null;
  docType: string;
  fileSizeBytes: number;
  status: DocumentStatus;
  createdAt: string;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export type FieldStrategy = "ANCHOR_REGEX" | "REGEX_GLOBAL" | "TABLE_UNDER_HEADING";

export interface NormalizerSpec {
  type: string;
  params?: Record<string, unknown>;
}

export interface FieldRule {
  name: string;
  type: string;
  required: boolean;
  strategy: FieldStrategy;
  params: Record<string, unknown>;
  normalizer: NormalizerSpec | null;
}

export interface RuleSetDefinition {
  docType: string;
  fields: FieldRule[];
}

export interface RuleSet {
  id: string;
  docType: string;
  version: number;
  definition: RuleSetDefinition;
  active: boolean;
  createdAt: string;
}

// "CUSTOM" = tenant has its own active rule set for this doc type; "DEFAULT" = falling back
// to the platform-shipped template because the tenant hasn't customized this doc type yet.
export interface EffectiveRuleSet {
  docType: string;
  source: "CUSTOM" | "DEFAULT";
  activeVersion: number | null;
  definition: RuleSetDefinition;
}

export interface InterpretedField {
  name: string;
  value: unknown;
  confidence: number;
  required: boolean;
  found: boolean;
}

export type ExtractionRunStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED";

export interface ExtractionRun {
  id: string;
  documentId: string;
  strategy: "RULE_BASED" | "LLM";
  status: ExtractionRunStatus;
  startedAt: string | null;
  completedAt: string | null;
  errorMessage: string | null;
}

export interface ExtractedFieldValue {
  value: unknown;
  confidence: number;
}

export interface ExtractedData {
  id: string;
  documentId: string;
  docType: string;
  fields: Record<string, ExtractedFieldValue>;
  overallConfidence: number;
  status: "COMPLETE" | "PARTIAL" | "NEEDS_REVIEW";
  createdAt: string;
}

export interface SearchFilter {
  field: string;
  op: "eq" | "contains" | "gt" | "gte" | "lt" | "lte";
  value: string;
}

export interface SearchResultItem {
  documentId: string;
  filename: string;
  contentType: string | null;
  docType: string;
  status: DocumentStatus;
  textRank: number;
  fields: Record<string, ExtractedFieldValue> | null;
  overallConfidence: number | null;
  createdAt: string;
}

export interface SearchResponse {
  items: SearchResultItem[];
  totalElements: number;
  page: number;
  size: number;
  semanticQueryProvided: boolean;
  semanticSearchAvailable: boolean;
}

export interface AuditLogEntry {
  id: string;
  actorType: "USER" | "GUEST" | "SYSTEM";
  actorUserId: string | null;
  actorGuestLinkId: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  metadata: Record<string, unknown>;
  createdAt: string;
}

export interface GuestLink {
  id: string;
  documentIds: string[];
  expiresAt: string;
  maxUses: number | null;
  useCount: number;
  revoked: boolean;
  createdAt: string;
  token: string | null;
  // Only populated on the create response when a notifyEmail was sent with the request —
  // null means "no email was requested", not "it failed".
  emailSent: boolean | null;
  emailError: string | null;
}

export interface GuestDocument {
  documentId: string;
  filename: string;
  docType: string;
  status: DocumentStatus;
  fields: Record<string, ExtractedFieldValue> | null;
}

export interface ApiError {
  timestamp: string;
  status: number;
  message: string;
}

// Anonymous "try it before you subscribe" uploads — see PublicDemoController.
export interface PublicDocument {
  id: string;
  filename: string;
  contentType: string | null;
  docType: string;
  fileSizeBytes: number;
  status: DocumentStatus;
  createdAt: string;
  uploadsUsed: number;
  uploadsLimit: number;
}

export interface PublicExtractionRun {
  id: string;
  strategy: "RULE_BASED" | "LLM";
  status: ExtractionRunStatus;
  startedAt: string | null;
  completedAt: string | null;
  errorMessage: string | null;
}

export interface PublicExtractedData {
  id: string;
  docType: string;
  fields: Record<string, ExtractedFieldValue>;
  overallConfidence: number;
  status: "COMPLETE" | "PARTIAL" | "NEEDS_REVIEW";
  createdAt: string;
}
