"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const { requestCloudflareIceServers, CLOUDFLARE_TURN_TTL_SECONDS } = require("./turn-credentials");

test("requests short-lived Cloudflare ICE credentials using server-side bearer token", async () => {
  let requested;
  const iceServers = [
    { urls: ["stun:stun.cloudflare.com:3478"] },
    { urls: ["turn:turn.cloudflare.com:3478?transport=udp"], username: "temporary-user", credential: "temporary-password" }
  ];
  const result = await requestCloudflareIceServers({
    keyId: "test-key-id",
    apiToken: "server-only-test-token",
    fetchImpl: async (url, options) => {
      requested = { url, options };
      return { ok: true, json: async () => ({ iceServers }) };
    }
  });
  assert.deepEqual(result, iceServers);
  assert.match(requested.url, /\/test-key-id\/credentials\/generate-ice-servers$/);
  assert.equal(requested.options.method, "POST");
  assert.equal(requested.options.headers.authorization, "Bearer server-only-test-token");
  assert.deepEqual(JSON.parse(requested.options.body), { ttl: CLOUDFLARE_TURN_TTL_SECONDS });
});

test("rejects unsuccessful Cloudflare API responses", async () => {
  await assert.rejects(
    requestCloudflareIceServers({
      keyId: "key",
      apiToken: "token",
      fetchImpl: async () => ({ ok: false, status: 403 })
    }),
    /credential request failed/
  );
});

test("rejects responses without a TURN relay URL", async () => {
  await assert.rejects(
    requestCloudflareIceServers({
      keyId: "key",
      apiToken: "token",
      fetchImpl: async () => ({ ok: true, json: async () => ({ iceServers: [{ urls: ["stun:stun.cloudflare.com:3478"] }] }) })
    }),
    /no TURN relay server/
  );
});

test("does not call Cloudflare without both server credentials", async () => {
  let called = false;
  await assert.rejects(
    requestCloudflareIceServers({ keyId: "key", apiToken: "", fetchImpl: async () => { called = true; } }),
    /not configured/
  );
  assert.equal(called, false);
});
