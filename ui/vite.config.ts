import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"

const apiTarget = process.env.RULE_ENGINE_URL ?? "http://localhost:8080"

const apiProxy = {
  "/api": {
    target: apiTarget,
    changeOrigin: true,
  },
}

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: apiProxy,
  },
  preview: {
    port: 4173,
    proxy: apiProxy,
  },
})
