"use strict";

const CLOUDFLARE_TURN_TTL_SECONDS = 12 * 60 * 60;
const CLOUDFLARE_TURN_ENDPOINT = "https://rtc.live.cloudflare.com/v1/turn/keys";

async function requestCloudflareIceServers({ keyId, apiToken, fetchImpl = fetch }) {
  if (!keyId || !apiToken) throw new Error("Cloudflare TURN credentials are not configured");
  const response = await fetchImpl(
    `${CLOUDFLARE_TURN_ENDPOINT}/${encodeURIComponent(keyId)}/credentials/generate-ice-servers`,
    {
      method: "POST",
      headers: {
        authorization: `Bearer ${apiToken}`,
        "content-type": "application/json"
      },
      body: JSON.stringify({ ttl: CLOUDFLARE_TURN_TTL_SECONDS }),
      signal: AbortSignal.timeout(8000)
    }
  );
  if (!response.ok) throw new Error("Cloudflare TURN credential request failed");
  const payload = await response.json();
  const hasUsableTurnServer = payload && Array.isArray(payload.iceServers) &&
    payload.iceServers.some(item => {
      if (!item || typeof item.username !== "string" || !item.username.trim() ||
          typeof item.credential !== "string" || !item.credential.trim()) return false;
      const urls = Array.isArray(item.urls) ? item.urls : [item.urls];
      return urls.some(url =>
        typeof url === "string" && (url.startsWith("turn:") || url.startsWith("turns:")));
    });
  if (!hasUsableTurnServer) {
    throw new Error("Cloudflare returned no usable TURN relay credentials");
  }
  return payload.iceServers;
}

module.exports = { requestCloudflareIceServers, CLOUDFLARE_TURN_TTL_SECONDS };
