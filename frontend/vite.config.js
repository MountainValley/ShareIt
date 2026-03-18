import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    host: "0.0.0.0",
    strictPort: true,
    port: 5173,
    origin: "http://0.0.0.0:5173",
    proxy: {
      "/api": {
        target: "http://localhost:7777",
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: "dist",
    emptyOutDir: true
  }
});
