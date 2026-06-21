import { useEffect, useRef, useState } from 'react';
import { useDebounce } from '../hooks/useDebounce';
import { fetchSuggestions, submitSearch } from '../api/client';
import type { Suggestion } from '../types';
import { SuggestionsDropdown } from './SuggestionsDropdown';

interface Props {
  onSearched: (query: string, message: string) => void;
}

const DEBOUNCE_MS = 200;

/**
 * The typeahead search box. Responsibilities:
 *  - debounce keystrokes (200ms) before calling GET /suggest
 *  - cancel in-flight requests when the user keeps typing (AbortController)
 *  - keyboard navigation: ArrowUp/Down to move, Enter to submit, Esc to close
 *  - submit via Enter or the Search button -> POST /search (dummy response)
 */
export function SearchBar({ onSearched }: Props) {
  const [input, setInput] = useState('');
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  const [source, setSource] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const [mode, setMode] = useState<'popularity' | 'recency'>('popularity');

  const debounced = useDebounce(input, DEBOUNCE_MS);
  const inputRef = useRef<HTMLInputElement>(null);

  // Fetch suggestions whenever the debounced prefix changes.
  useEffect(() => {
    const prefix = debounced.trim();
    if (prefix.length === 0) {
      setSuggestions([]);
      setSource('');
      setError(null);
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    fetchSuggestions(prefix, mode, controller.signal)
      .then((res) => {
        setSuggestions(res.suggestions);
        setSource(res.source);
        setActiveIndex(-1);
        setLoading(false);
      })
      .catch((e: unknown) => {
        if ((e as Error).name === 'AbortError') return; // superseded by a newer request
        setError('Could not load suggestions');
        setSuggestions([]);
        setLoading(false);
      });
    return () => controller.abort();
    // Re-fetch when the prefix OR the ranking mode changes.
  }, [debounced, mode]);

  function doSearch(query: string) {
    const q = query.trim();
    if (!q) return;
    setOpen(false);
    setInput(q);
    submitSearch(q)
      .then((res) => onSearched(q, res.message))
      .catch(() => onSearched(q, 'Search failed'));
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (!open && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
      setOpen(true);
      return;
    }
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        setActiveIndex((i) => Math.min(i + 1, suggestions.length - 1));
        break;
      case 'ArrowUp':
        e.preventDefault();
        setActiveIndex((i) => Math.max(i - 1, 0));
        break;
      case 'Enter':
        e.preventDefault();
        if (activeIndex >= 0 && activeIndex < suggestions.length) {
          doSearch(suggestions[activeIndex].query);
        } else {
          doSearch(input);
        }
        break;
      case 'Escape':
        setOpen(false);
        break;
      default:
        break;
    }
  }

  return (
    <div className="searchbar">
      <div className="searchbar-row">
        <input
          ref={inputRef}
          className="search-input"
          type="text"
          value={input}
          placeholder="Search…"
          aria-label="Search"
          autoComplete="off"
          onChange={(e) => {
            setInput(e.target.value);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => setOpen(false)}
          onKeyDown={onKeyDown}
        />
        <button className="search-btn" onClick={() => doSearch(input)}>
          Search
        </button>
      </div>

      <div className="rank-toggle">
        <span>Rank suggestions by:</span>
        <button className={mode === 'popularity' ? 'on' : ''} onClick={() => setMode('popularity')}>
          Popularity
        </button>
        <button className={mode === 'recency' ? 'on' : ''} onClick={() => setMode('recency')}>
          Recency
        </button>
      </div>

      {open && input.trim().length > 0 && (
        <SuggestionsDropdown
          suggestions={suggestions}
          activeIndex={activeIndex}
          loading={loading}
          error={error}
          source={source}
          onPick={(s) => doSearch(s.query)}
          onHover={(i) => setActiveIndex(i)}
        />
      )}
    </div>
  );
}
