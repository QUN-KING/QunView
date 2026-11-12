"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { join } = require("node:path");

const viewerSource = readFileSync(join(__dirname, "viewer.js"), "utf8");

test("viewer pins signaling to the trusted host instead of accepting it from the share link", () => {
  assert.match(viewerSource, /signalingUrl="wss:\\/\\/qunview-signaling\\.onrender\\.com"/);
  assert.doesNotMatch(viewerSource, /params\\.get\\(["']signal["']\\)/);
});
