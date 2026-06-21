// Shared API types, mirroring the backend DTOs.

export interface Suggestion {
  query: string;
  count: number;
}

export interface SuggestResponse {
  prefix: string;
  mode: string; // POPULARITY | RECENCY
  suggestions: Suggestion[];
  source: string; // CACHE | DB | TRIE | DB+RECENCY | EMPTY
  cacheNode: string;
  tookMs: number;
}

export interface SearchResponse {
  message: string;
  query: string;
  accepted: boolean;
}

export interface TrendingResponse {
  mode: string; // POPULARITY | RECENCY
  suggestions: Suggestion[];
  source: string;
  tookMs: number;
}
