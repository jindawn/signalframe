import { execFileSync } from "node:child_process";
import { readFileSync, readdirSync } from "node:fs";
import SwaggerParser from "@apidevtools/swagger-parser";
const directory = "apps/api/src/main/java/com/signalframe/contract";
const files = [
  ...readdirSync(directory).map((f) => `${directory}/${f}`),
  "apps/web/src/lib/api.generated.ts",
  "contracts/analysis-result.schema.json",
  "apps/api/src/main/resources/analysis-result.schema.json",
];
const before = files.map((f) => readFileSync(f, "utf8"));
await SwaggerParser.validate("contracts/openapi.yaml");
execFileSync("npm", ["run", "contracts"], { stdio: "inherit" });
for (let i = 0; i < files.length; i++)
  if (readFileSync(files[i], "utf8") !== before[i])
    throw new Error(`Contract drift: ${files[i]}`);
console.log("OpenAPI valid; generated Java and TypeScript are current.");
