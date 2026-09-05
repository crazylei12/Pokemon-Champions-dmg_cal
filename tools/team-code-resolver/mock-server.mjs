import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const host = process.env.TEAM_CODE_MOCK_HOST || "127.0.0.1";
const port = Number(process.env.TEAM_CODE_MOCK_PORT || 8765);
const codePattern = /^[A-Z0-9]{10}$/;

function reply(response, status, value) {
  const body = JSON.stringify(value);
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "cache-control": "no-store",
  });
  response.end(body);
}

async function readRequestJson(request) {
  const chunks = [];
  let bytes = 0;
  for await (const chunk of request) {
    bytes += chunk.length;
    if (bytes > 4096) throw new Error("request_too_large");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

const server = createServer(async (request, response) => {
  if (request.method !== "POST" || request.url !== "/v1/team-codes/resolve") {
    reply(response, 404, { error: { code: "NOT_FOUND" } });
    return;
  }
  try {
    const requestBody = await readRequestJson(request);
    const code = String(requestBody.code || "").trim().toUpperCase();
    if (requestBody.schemaVersion !== 1 || !codePattern.test(code)) {
      reply(response, 400, { error: { code: "INVALID_TEAM_CODE" } });
      return;
    }
    let fixture;
    try {
      fixture = await readFile(join(root, "fixtures", `${code}.json`), "utf8");
    } catch (error) {
      if (error?.code === "ENOENT") {
        reply(response, 404, { error: { code: "TEAM_CODE_NOT_FOUND" } });
        return;
      }
      throw error;
    }
    reply(response, 200, JSON.parse(fixture));
  } catch {
    reply(response, 500, { error: { code: "MOCK_RESOLVER_ERROR" } });
  }
});

server.listen(port, host, () => {
  process.stdout.write(`Development team-code fixture server: http://${host}:${port}/v1/team-codes/resolve\n`);
});
