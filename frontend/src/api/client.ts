import type { SuggestResponse, SearchResponse, TrendingResponse } from '../types';

// Same-origin relative URLs; the dev server / nginx proxies them to the backend.

async function getJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(url, { signal });
  if (!res.ok) {
    throw new Error(`Request failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as T;
}

export function fetchSuggestions(
  prefix: string,
  mode: 'popularity' | 'recency' = 'popularity',
  signal?: AbortSignal
): Promise<SuggestResponse> {
  return getJson<SuggestResponse>(`/suggest?q=${encodeURIComponent(prefix)}&mode=${mode}`, signal);
}

export async function submitSearch(query: string): Promise<SearchResponse> {
  const res = await fetch('/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query }),
  });
  if (!res.ok) {
    throw new Error(`Search failed: ${res.status}`);
  }
  return (await res.json()) as SearchResponse;
}

export function fetchTrending(mode: 'recency' | 'popularity', signal?: AbortSignal): Promise<TrendingResponse> {
  return getJson<TrendingResponse>(`/trending?mode=${mode}`, signal);
}
