import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import { viteSingleFile } from "vite-plugin-singlefile";

const frontendRoot = fileURLToPath(new URL(".", import.meta.url));
const pagesOnPluginClasspath = fileURLToPath(
  new URL("../../../target/classes/io/jenkins/plugins/mcp/server/apps/", import.meta.url),
);

export default defineConfig({
  root: frontendRoot,
  plugins: [viteSingleFile()],
  build: {
    outDir: pagesOnPluginClasspath,
    emptyOutDir: false,
    rollupOptions: {
      input: fileURLToPath(new URL("jenkins.html", import.meta.url)),
    },
  },
});
