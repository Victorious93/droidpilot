#!/usr/bin/env node

import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createServer } from "./server.js";

/**
 * Entry point for the DroidPilot MCP server.
 *
 * stdout belongs to the MCP protocol, so every diagnostic goes to stderr. A stray
 * `console.log` here corrupts the JSON-RPC stream and the client reports an unhelpful
 * parse error rather than whatever was actually wrong.
 */
async function main(): Promise<void> {
  const server = createServer();
  await server.connect(new StdioServerTransport());
  console.error("DroidPilot MCP server ready (stdio)");
}

/**
 * Last-resort handlers.
 *
 * The transport is written so that no device failure can reach here — that guarantee is
 * covered by a regression test, because the previous client crashed the process on any
 * mid-session disconnect. These exist so that if something ever does slip through, it is
 * reported legibly on stderr instead of vanishing into a silent exit.
 */
process.on("uncaughtException", (error) => {
  console.error("Fatal: uncaught exception:", error);
  process.exit(1);
});

process.on("unhandledRejection", (reason) => {
  console.error("Fatal: unhandled rejection:", reason);
  process.exit(1);
});

main().catch((error) => {
  console.error("Fatal: failed to start:", error);
  process.exit(1);
});
