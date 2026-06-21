import { useState } from 'react';
import { SearchBar } from './components/SearchBar';
import { TrendingPanel } from './components/TrendingPanel';

export default function App() {
  const [lastResponse, setLastResponse] = useState<string | null>(null);
  const [lastQuery, setLastQuery] = useState<string>('');
  const [refreshSignal, setRefreshSignal] = useState(0);

  function handleSearched(query: string, message: string) {
    setLastQuery(query);
    setLastResponse(message);
    // Tell the trending panel to refresh (the new search affects recency).
    setRefreshSignal((n) => n + 1);
  }

  return (
    <div className="app">
      <header className="app-header">
        <h1>Search Typeahead</h1>
        <p className="subtitle">
          Distributed cache · consistent hashing · trending search · batch writes
        </p>
      </header>

      <main className="app-main">
        <section className="search-section">
          <SearchBar onSearched={handleSearched} />
          {lastResponse && (
            <div className="search-result" role="status">
              <b>{lastResponse}</b>: “{lastQuery}”
            </div>
          )}
        </section>

        <TrendingPanel refreshSignal={refreshSignal} />
      </main>
    </div>
  );
}
