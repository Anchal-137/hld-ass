import type { Suggestion } from '../types';

interface Props {
  suggestions: Suggestion[];
  activeIndex: number;
  loading: boolean;
  error: string | null;
  source: string;
  onPick: (s: Suggestion) => void;
  onHover: (index: number) => void;
}

/**
 * Presentational dropdown. Renders loading / error / empty / results states.
 * The currently highlighted row (keyboard navigation) is marked `active`.
 */
export function SuggestionsDropdown({
  suggestions,
  activeIndex,
  loading,
  error,
  source,
  onPick,
  onHover,
}: Props) {
  if (loading) {
    return (
      <div className="dropdown">
        <div className="dropdown-status">Loading…</div>
      </div>
    );
  }
  if (error) {
    return (
      <div className="dropdown">
        <div className="dropdown-status error">⚠ {error}</div>
      </div>
    );
  }
  if (suggestions.length === 0) {
    return (
      <div className="dropdown">
        <div className="dropdown-status">No suggestions</div>
      </div>
    );
  }
  return (
    <div className="dropdown" role="listbox">
      {suggestions.map((s, i) => (
        <div
          key={s.query}
          role="option"
          aria-selected={i === activeIndex}
          className={`dropdown-item ${i === activeIndex ? 'active' : ''}`}
          onMouseDown={(e) => {
            // onMouseDown (not onClick) so it fires before the input blur.
            e.preventDefault();
            onPick(s);
          }}
          onMouseEnter={() => onHover(i)}
        >
          <span className="item-query">{s.query}</span>
          <span className="item-count">{s.count.toLocaleString()}</span>
        </div>
      ))}
      <div className="dropdown-footer">served from {source}</div>
    </div>
  );
}
