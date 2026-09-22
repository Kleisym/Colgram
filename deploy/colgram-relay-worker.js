/**
 * Colgram fetch relay — a Cloudflare Worker.
 *
 * What it is for: some networks drop Telegram's API and CDN addresses by IP while leaving the
 * rest of the internet alone. Telegram itself gets around that with an MTProto proxy, but the
 * Bot API, the disposable-mail providers and the proxy lists are plain HTTPS from a Java socket
 * that cannot use an MTProto proxy. This worker forwards exactly those requests, from an
 * address the block has no reason to touch.
 *
 * What it is NOT: a VPN, and not a Telegram transport. It cannot carry MTProto, so it will not
 * make the app itself connect.
 *
 * Deploy (about 2 minutes, free tier):
 *   npm i -g wrangler
 *   wrangler login
 *   wrangler init --name colgram-relay      # then replace src/index.js with this file
 *   wrangler deploy                          # prints https://colgram-relay.<account>.workers.dev
 * Then paste that URL into Colgram: Настройки Colgram → Сеть → Реле-адрес.
 *
 * It is deliberately an allowlist, not an open proxy: without this an attacker would use the
 * deployment to scan the internet from your Cloudflare account.
 */

const ALLOWED_HOSTS = new Set([
  'api.telegram.org',
  'raw.githubusercontent.com',
  'github.com',
  'api.mail.tm',
  'api.mail.gw',
  'api.internal.temp-mail.io',
  'api.guerrillamail.com',
  'guerrillamail.com',
  'webmail.guerrillamail.com',
  'api.1secmail.com',
  'www.1secmail.com',
  'inbucket.app',
]);

const MAX_BODY_BYTES = 512 * 1024;

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, X-Colgram-Method, X-Colgram-Authorization',
    'Access-Control-Max-Age': '86400',
  };
}

function jsonError(status, message) {
  return new Response(JSON.stringify({ ok: false, error: message }), {
    status,
    headers: { 'Content-Type': 'application/json', ...corsHeaders() },
  });
}

export default {
  async fetch(request) {
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    const url = new URL(request.url);
    const target = url.searchParams.get('url');
    if (!target) return jsonError(400, 'missing ?url=');

    let parsed;
    try {
      parsed = new URL(target);
    } catch (e) {
      return jsonError(400, 'url is not absolute');
    }
    if (parsed.protocol !== 'https:') return jsonError(400, 'only https targets');
    if (!ALLOWED_HOSTS.has(parsed.hostname)) {
      return jsonError(403, 'host not allowed: ' + parsed.hostname);
    }

    const method = (request.headers.get('X-Colgram-Method') || 'GET').toUpperCase();
    const init = {
      method,
      redirect: 'follow',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Colgram)',
        Accept: 'application/json, text/plain, */*',
      },
    };

    if (method === 'POST') {
      const body = await request.arrayBuffer();
      if (body.byteLength > MAX_BODY_BYTES) return jsonError(413, 'body too large');
      const bearer = request.headers.get('X-Colgram-Authorization');
      if (bearer) init.headers.Authorization = 'Bearer ' + bearer;
      init.headers['Content-Type'] = 'application/json';
      init.body = body;
    }

    try {
      const upstream = await fetch(parsed.toString(), { ...init, cf: { cacheTtl: 0 } });
      const out = new Response(upstream.body, {
        status: upstream.status,
        headers: {
          'Content-Type': upstream.headers.get('Content-Type') || 'application/json',
          'Cache-Control': 'no-store',
          ...corsHeaders(),
        },
      });
      return out;
    } catch (e) {
      return jsonError(502, 'upstream fetch failed: ' + (e && e.message ? e.message : String(e)));
    }
  },
};
