import { useNavigate } from "react-router-dom";
import { ArrowLeftIcon } from "./icons";

/**
 * Real browser history back (`navigate(-1)`), not a hardcoded parent route — this is what makes
 * one component work correctly on every page regardless of how the user actually got there (a
 * document opened from a search result vs from the documents list should both go back to
 * wherever that was, not to one hardcoded "parent"). Falls back to a fixed destination only when
 * there's genuinely no in-app history to go back to (a fresh tab opened directly on this URL,
 * e.g. a bookmarked or shared link) — `window.history.length` on a freshly-loaded tab is 1.
 */
export function BackButton({ fallback = "/" }: { fallback?: string }) {
  const navigate = useNavigate();

  function handleClick() {
    if (window.history.length > 1) {
      navigate(-1);
    } else {
      navigate(fallback);
    }
  }

  return (
    <button type="button" className="back-button" onClick={handleClick} title="Go back">
      <ArrowLeftIcon />
      Back
    </button>
  );
}
