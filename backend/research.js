'use strict';

const { URL } = require('node:url');

const USER_AGENT = 'Ai-BBGI-Research/1.0 (+https://github.com/Bibgg/Ai-bbgi)';

function decodeEntities(value) {
  return String(value || '')
    .replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)));
}

function stripHtml(html) {
  return decodeEntities(String(html || '')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim());
}

async function fetchText(url, timeoutMs = 9000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      headers: { 'User-Agent': USER_AGENT, Accept: 'text/html,application/xhtml+xml,application/rss+xml' },
      signal: controller.signal,
      redirect: 'follow'
    });
    if (!response.ok) throw new Error('HTTP ' + response.status);
    const type = response.headers.get('content-type') || '';
    if (!type.includes('text/html') && !type.includes('application/xhtml+xml') && !type.includes('xml')) {
      throw new Error('Unsupported source content type');
    }
    return (await response.text()).slice(0, 1200000);
  } finally {
    clearTimeout(timer);
  }
}

function safeResultUrl(raw) {
  try {
    const url = new URL(raw);
    if (url.protocol !== 'https:' || !url.hostname || url.username || url.password) return null;
    if (['localhost', '127.0.0.1', '0.0.0.0'].includes(url.hostname)) return null;
    return url.toString();
  } catch {
    return null;
  }
}

async function searchWeb(query, limit = 5) {
  const q = String(query || '').trim().slice(0, 500);
  if (!q) throw new Error('Search query is required.');
  const searchUrl = 'https://html.duckduckgo.com/html/?q=' + encodeURIComponent(q);
  const html = await fetchText(searchUrl, 12000);
  const matches = [...html.matchAll(/<div[^>]*class="[^"]*result[^"]*"[^>]*>([\s\S]*?)<\/div>\s*<\/div>/gi)];
  const results = [];
  for (const match of matches) {
    const block = match[1];
    const hrefMatch = block.match(/<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"/i);
    const titleMatch = block.match(/<a[^>]*class="[^"]*result__a[^"]*"[^>]*>([\s\S]*?)<\/a>/i);
    const snippetMatch = block.match(/<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>([\s\S]*?)<\/a>|<div[^>]*class="[^"]*result__snippet[^"]*"[^>]*>([\s\S]*?)<\/div>/i);
    if (!hrefMatch || !titleMatch) continue;
    let rawUrl = decodeEntities(hrefMatch[1]);
    try {
      const parsed = new URL(rawUrl, 'https://duckduckgo.com');
      const redirected = parsed.searchParams.get('uddg');
      if (redirected) rawUrl = decodeURIComponent(redirected);
    } catch {}
    const url = safeResultUrl(rawUrl);
    if (!url) continue;
    results.push({
      title: stripHtml(titleMatch[1]).slice(0, 240),
      url,
      snippet: stripHtml(snippetMatch?.[1] || snippetMatch?.[2] || '').slice(0, 700)
    });
    if (results.length >= Math.min(Math.max(limit, 1), 5)) break;
  }
  if (!results.length) throw new Error('Search provider returned no readable results. Try a more specific query.');
  const enriched = await Promise.all(results.map(async (item) => {
    try {
      const page = await fetchText(item.url, 7000);
      const text = stripHtml(page).slice(0, 5000);
      return { ...item, excerpt: text || item.snippet };
    } catch {
      return { ...item, excerpt: item.snippet };
    }
  }));
  return enriched;
}

module.exports = { searchWeb };
