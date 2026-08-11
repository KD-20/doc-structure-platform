import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev server proxies /api to the backend on :8081 (matches this repo's local dev port —
// see README); in production the built SPA is served same-origin by Spring Boot itself, so
// no proxy/base-URL configuration is needed there at all.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": {
        target: "http://localhost:8081",
        changeOrigin: true,
      },
    },
  },
});
