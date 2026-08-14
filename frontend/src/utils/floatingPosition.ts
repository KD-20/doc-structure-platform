export interface FixedPosition {
  top: number;
  right: number;
}

/**
 * position: fixed coordinates anchoring a floating panel just below and right-aligned to a
 * trigger element, computed from its live on-screen position. Using `fixed` (not the plain CSS
 * `position: absolute` these panels used before) is what lets the panel escape ancestor overflow
 * clipping — e.g. the `overflowX: "auto"` wrapper around the Documents/Audit Log tables was
 * cutting these panels off before they could render fully, because setting overflow-x to
 * anything other than 'visible' forces the browser to also treat overflow-y as 'auto' rather
 * than 'visible' (CSS spec), silently clipping any absolutely-positioned descendant that extends
 * past the wrapper's bottom edge — confirmed live, reported on both the Documents and Audit Log
 * pages since both wrap a scrollable table the same way.
 */
export function fixedPanelPositionBelow(trigger: HTMLElement): FixedPosition {
  const rect = trigger.getBoundingClientRect();
  return { top: rect.bottom + 6, right: window.innerWidth - rect.right };
}
