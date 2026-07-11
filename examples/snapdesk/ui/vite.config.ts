import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// base: "./" — packaged JDesk apps serve the built bundle from the
// jdesk://app/ protocol, so all asset URLs must stay relative.
export default defineConfig({
  base: "./",
  plugins: [react(), tailwindcss()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
  },
});
