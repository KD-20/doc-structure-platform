import { useState } from "react";
import { Link } from "react-router-dom";
import {
  ArrowRightIcon,
  BoltIcon,
  DocumentIcon,
  LayersIcon,
  SearchIcon,
  ShieldIcon,
  SparkleIcon,
  StructureIcon,
  UploadIcon,
} from "../components/icons";

interface Passage {
  file: string;
  page: number;
  section: string;
  excerpt: string;
  tags: string[];
  keywords: string[];
}

// A small fixed sample index for the "search by query or by word" demo below — illustrative,
// not backed by the real search API (that requires a signed-in tenant with its own documents).
const SAMPLE_PASSAGES: Passage[] = [
  {
    file: "Master Services Agreement.pdf",
    page: 4,
    section: "5.2 Termination for convenience",
    excerpt:
      "Either party may terminate this agreement with sixty (60) days written notice, provided all outstanding invoices are settled within the notice period.",
    tags: ["clause", "termination"],
    keywords: ["termination notice", "termination", "terminate", "notice"],
  },
  {
    file: "Q3-Financials.xlsx",
    page: 2,
    section: "Revenue by segment",
    excerpt:
      "Total revenue for Q3 grew 18% year-over-year to $42.6M, driven primarily by the enterprise segment.",
    tags: ["table", "revenue"],
    keywords: ["revenue", "financials", "growth"],
  },
  {
    file: "Master Services Agreement.pdf",
    page: 6,
    section: "7.1 Payment terms",
    excerpt: "Invoices are due within thirty (30) days of receipt. Late payments accrue interest at 1.5% per month.",
    tags: ["clause", "payment"],
    keywords: ["payment terms", "invoice", "payment"],
  },
  {
    file: "Clinical-Trial-Report.pdf",
    page: 11,
    section: "9.3 Adverse events",
    excerpt:
      "Three participants reported mild adverse events during the treatment phase; none required discontinuation of the study.",
    tags: ["section", "safety"],
    keywords: ["adverse events", "safety", "side effects"],
  },
  {
    file: "Employee-Handbook.pdf",
    page: 8,
    section: "4.4 Leave policy",
    excerpt: "Full-time employees accrue fifteen (15) days of paid leave per year, prorated for the first year.",
    tags: ["policy", "leave"],
    keywords: ["leave", "pto", "vacation", "time off"],
  },
  {
    file: "Data-Processing-Addendum.pdf",
    page: 3,
    section: "3.1 Confidentiality",
    excerpt:
      "Each party shall protect the other's confidential information using the same degree of care it applies to its own, and no less than reasonable care.",
    tags: ["clause", "confidentiality"],
    keywords: ["confidentiality", "confidential", "data protection"],
  },
];

const SUGGESTED_QUERIES = ["termination notice", "revenue", "payment terms", "adverse events", "leave"];

function findPassage(query: string): { passage: Passage; index: number } | null {
  const q = query.trim().toLowerCase();
  if (!q) return null;
  const index = SAMPLE_PASSAGES.findIndex((p) => p.keywords.some((k) => k.includes(q) || q.includes(k)));
  return index >= 0 ? { passage: SAMPLE_PASSAGES[index], index } : null;
}

export function LandingPage() {
  const [query, setQuery] = useState("termination notice");
  const match = findPassage(query);

  return (
    <div className="landing-page">
      <nav className="landing-nav">
        <div className="landing-nav-logo">
          <span className="landing-logo-mark">
            <LayersIcon />
          </span>
          <span className="landing-logo-word">DocStructure</span>
        </div>
        <div className="landing-nav-links">
          <a href="#steps">How it works</a>
          <a href="#search-demo">Search</a>
          <a href="#step-structure">Structure</a>
        </div>
        <div className="landing-nav-actions">
          <Link to="/login" className="landing-nav-signin">
            Sign in
          </Link>
          <Link to="/login" className="pill landing-nav-cta">
            Upload a doc <ArrowRightIcon />
          </Link>
        </div>
      </nav>

      <section className="landing-hero landing-hero-solo landing-container">
        <div className="landing-hero-copy">
          <span className="landing-badge">
            <SparkleIcon /> DOCUMENT INTELLIGENCE
          </span>
          <h1 className="landing-headline">
            Drop in a document.
            <br />
            Get back <em className="landing-accent">structure</em> you can search.
          </h1>
          <p className="landing-sub">
            DocStructure reads PDFs, contracts, invoices and reports, splits them into clean
            sections, tables and entities, then lets you find any clause by keyword or plain
            question — with the exact page attached.
          </p>
          <div className="landing-cta-row">
            <Link to="/login" className="pill">
              <UploadIcon /> Sign in to upload
            </Link>
            <a href="#search-demo" className="pill secondary-outline">
              <SearchIcon /> Try the search demo
            </a>
          </div>
          <div className="landing-stats-row">
            <span>
              <ShieldIcon /> Private by default
            </span>
            <span>
              <BoltIcon /> ~8s per 100 pages
            </span>
            <span>
              <DocumentIcon /> PDF, DOCX, XLSX, scans
            </span>
          </div>
        </div>
      </section>

      <section className="landing-container landing-section" id="steps">
        <h2 className="landing-section-title">Three steps, no setup</h2>
        <div className="landing-steps-grid">
          <div className="card landing-step-card">
            <div className="landing-step-top">
              <span className="landing-step-icon">
                <UploadIcon />
              </span>
              <span className="muted">01</span>
            </div>
            <h3 className="landing-step-title">Upload</h3>
            <p className="muted">
              Drag in one file or a whole folder. Scans get OCR'd automatically, so even
              photographed pages become text.
            </p>
          </div>
          <div className="card landing-step-card" id="step-structure">
            <div className="landing-step-top">
              <span className="landing-step-icon">
                <StructureIcon />
              </span>
              <span className="muted">02</span>
            </div>
            <h3 className="landing-step-title">Structure</h3>
            <p className="muted">
              Headings, clauses, tables, dates, amounts and parties are extracted into a clean
              outline with page anchors.
            </p>
          </div>
          <div className="card landing-step-card">
            <div className="landing-step-top">
              <span className="landing-step-icon">
                <SearchIcon />
              </span>
              <span className="muted">03</span>
            </div>
            <h3 className="landing-step-title">Search</h3>
            <p className="muted">
              Ask a question in plain language or hunt a single word. Every answer cites the
              document and page.
            </p>
          </div>
        </div>
      </section>

      <section className="landing-container landing-section" id="search-demo">
        <div className="landing-search-header">
          <div>
            <h2 className="landing-section-title">Search by query or by word</h2>
            <p className="muted">
              A small sample index of six documents. Type anything — results rank by term matches
              and jump straight to the page.
            </p>
          </div>
          <span className="muted landing-search-count">
            {match ? match.index + 1 : 0} of {SAMPLE_PASSAGES.length} passages
          </span>
        </div>

        <div className="landing-search-box card">
          <div className="landing-search-input-row">
            <SearchIcon />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search termination clauses, revenue, payment terms…"
            />
            <kbd className="landing-kbd">⌘K</kbd>
          </div>
          <div className="chip-list">
            {SUGGESTED_QUERIES.map((s) => (
              <button
                type="button"
                key={s}
                className={`chip${query === s ? " selected" : ""}`}
                onClick={() => setQuery(s)}
              >
                {s}
              </button>
            ))}
          </div>
        </div>

        {match ? (
          <div className="card landing-result-card">
            <div className="landing-result-header">
              <DocumentIcon />
              <span className="landing-result-file">{match.passage.file}</span>
              <span className="muted">p.{match.passage.page}</span>
              <span className="muted">· {match.passage.section}</span>
            </div>
            <p className="landing-result-excerpt">{match.passage.excerpt}</p>
            <div className="chip-list" style={{ marginBottom: 0 }}>
              {match.passage.tags.map((t) => (
                <span className="chip" key={t}>
                  {t}
                </span>
              ))}
            </div>
          </div>
        ) : (
          <div className="card">
            <p className="muted" style={{ margin: 0 }}>
              No sample passage matches "{query}" — try one of the suggestions above.
            </p>
          </div>
        )}
      </section>

      <footer className="landing-footer landing-container">
        <span className="muted">© {new Date().getFullYear()} DocStructure</span>
        <div className="landing-footer-links">
          <Link to="/try">Try without an account</Link>
          <Link to="/register">Register</Link>
          <Link to="/login">Sign in</Link>
        </div>
      </footer>
    </div>
  );
}
