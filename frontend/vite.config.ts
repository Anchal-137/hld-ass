import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// During development the Vite dev server (5173) proxies the API paths to the
// Spring Boot backend (8080), so the frontend can use same-origin relative URLs
// and we avoid CORS entirely. In Docker, nginx performs the same proxying.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/suggest': 'http://localhost:8080',
      '/search': 'http://localhost:8080',
      '/trending': 'http://localhost:8080',
      '/cache': 'http://localhost:8080',
      '/stats': 'http://localhost:8080',
    },
  },
});
