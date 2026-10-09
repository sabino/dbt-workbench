import {copyFile} from "node:fs/promises";
import {resolve} from "node:path";

// Closure rewrites named capture groups inside String.raw templates. Preserve the library's browser build.
await copyFile(resolve(import.meta.dir, "../node_modules/sql-formatter/dist/sql-formatter.min.js"),
  resolve(import.meta.dir, "../../resources/static/js/sql-formatter.min.js"));
