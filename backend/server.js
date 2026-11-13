'use strict';

require('dotenv').config();

const crypto = require('node:crypto');
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const mongoose = require('mongoose');
const { rateLimit } = require('express-rate-limit');
const { Octokit } = require('@octokit/rest');
const { searchWeb } = require('./research');

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', 1);
app.use(helmet({ crossOriginResourcePolicy: { policy: 'cross-origin' } }));

const allowedOrigins = (process.env.CORS_ORIGINS || '')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);

app.use(cors({
  origin(origin, callback) {
    // Native Android requests do not send Origin. Browser origins must be explicitly allow-listed.
    if (!origin || allowedOrigins.includes(origin)) return callback(null, true);
    return callback(new Error('Origin is not allowed by CORS'));
  },
  methods: ['GET', 'POST', 'PUT', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization', 'X-Client-Id'],
  maxAge: 86400
}));
app.use(express.json({ limit: '2mb', strict: true }));
app.use(rateLimit({
  windowMs: Number(process.env.RATE_LIMIT_WINDOW_MS || 60000),
  limit: Number(process.env.RATE_LIMIT_MAX || 60),
  standardHeaders: 'draft-7',
  legacyHeaders: false
}));

const messageSchema = new mongoose.Schema({
  sessionId: { type: mongoose.Schema.Types.ObjectId, ref: 'ChatSession', required: true, index: true },
  ownerId: { type: String, required: true, index: true },
  role: { type: String, enum: ['user', 'assistant', 'system'], required: true },
  content: { type: String, required: true, maxlength: 100000 },
  provider: { type: String, enum: ['local', 'cloud', 'client-local', 'system'], default: 'system' },
  createdAt: { type: Date, default: Date.now, index: true }
}, { versionKey: false });

const sessionSchema = new mongoose.Schema({
  ownerId: { type: String, required: true, index: true },
  title: { type: String, default: 'New conversation', maxlength: 160 },
  createdAt: { type: Date, default: Date.now },
  updatedAt: { type: Date, default: Date.now, index: true }
}, { versionKey: false });

const memorySchema = new mongoose.Schema({
  ownerId: { type: String, required: true, unique: true, index: true },
  content: { type: String, default: '', maxlength: 30000 },
  updatedAt: { type: Date, default: Date.now }
}, { versionKey: false });

const codeSchema = new mongoose.Schema({
  ownerId: { type: String, required: true, index: true },
  sessionId: { type: mongoose.Schema.Types.ObjectId, ref: 'ChatSession', default: null },
  language: { type: String, default: 'text', maxlength: 40 },
  filePath: { type: String, default: '', maxlength: 500 },
  content: { type: String, required: true, maxlength: 500000 },
  createdAt: { type: Date, default: Date.now }
}, { versionKey: false });

const ChatMessage = mongoose.model('ChatMessage', messageSchema);
const ChatSession = mongoose.model('ChatSession', sessionSchema);
const UserMemory = mongoose.model('UserMemory', memorySchema);
const CodeSnippet = mongoose.model('CodeSnippet', codeSchema);

const asyncRoute = (handler) => (req, res, next) => Promise.resolve(handler(req, res, next)).catch(next);
const text = (value, max = 10000) => typeof value === 'string' ? value.trim().slice(0, max) : '';
const safeEqual = (left, right) => {
  const a = Buffer.from(String(left));
  const b = Buffer.from(String(right));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
};

function requireApiKey(req, res, next) {
  const configured = process.env.APP_API_KEY;
  if (!configured) return res.status(503).json({ error: 'Backend is not configured: APP_API_KEY is missing.' });
  const supplied = (req.get('authorization') || '').replace(/^Bearer\s+/i, '');
  if (!supplied || !safeEqual(supplied, configured)) {
    return res.status(401).json({ error: 'Unauthorized.' });
  }
  req.ownerId = text(req.get('x-client-id'), 100) || 'default-device';
  next();
}

function requireDatabase(req, res, next) {
  if (mongoose.connection.readyState !== 1) {
    return res.status(503).json({ error: 'MongoDB is not connected. Configure MONGODB_URI in Render.' });
  }
  next();
}

app.get('/health', (req, res) => {
  res.status(200).json({
    ok: true,
    service: 'ai-bbgi-backend',
    database: mongoose.connection.readyState === 1 ? 'connected' : 'disconnected',
    ai: {
      localConfigured: Boolean(process.env.LLAMA_SERVER_URL),
      cloudConfigured: Boolean(process.env.CLOUD_API_KEY && process.env.CLOUD_API_URL)
    },
    timestamp: new Date().toISOString()
  });
});

app.get('/api/health', (req, res) => res.redirect(307, '/health'));

const api = express.Router();
api.use(requireApiKey);
api.use(requireDatabase);

async function loadHistory(sessionId, ownerId) {
  if (!sessionId) return [];
  if (!mongoose.isValidObjectId(sessionId)) throw Object.assign(new Error('Invalid sessionId.'), { status: 400 });
  const session = await ChatSession.findOne({ _id: sessionId, ownerId }).lean();
  if (!session) throw Object.assign(new Error('Session not found.'), { status: 404 });
  const rows = await ChatMessage.find({ sessionId, ownerId }).sort({ createdAt: -1 }).limit(30).lean();
  return rows.reverse().map(({ role, content }) => ({ role, content }));
}

function llamaEndpoint(base) {
  const clean = base.replace(/\/+$/, '');
  return clean.endsWith('/v1/chat/completions') ? clean : clean + '/v1/chat/completions';
}

async function requestJson(url, options, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { ...options, signal: controller.signal });
    const raw = await response.text();
    let data;
    try { data = raw ? JSON.parse(raw) : {}; } catch { data = { raw }; }
    if (!response.ok) {
      const message = data?.error?.message || data?.error || data?.message || `HTTP ${response.status}`;
      throw new Error(String(message).slice(0, 500));
    }
    return data;
  } finally {
    clearTimeout(timer);
  }
}

async function callLocal(messages) {
  const base = text(process.env.LLAMA_SERVER_URL, 1000);
  if (!base) throw new Error('LLAMA_SERVER_URL is not configured.');
  const data = await requestJson(llamaEndpoint(base), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: 'local-model', messages, temperature: 0.7, stream: false })
  }, Number(process.env.LLAMA_TIMEOUT_MS || 45000));
  const answer = data?.choices?.[0]?.message?.content;
  if (typeof answer !== 'string' || !answer.trim()) throw new Error('Local llama.cpp returned an empty response.');
  return answer.trim();
}

async function callCloud(messages) {
  const endpoint = text(process.env.CLOUD_API_URL, 1000);
  const key = text(process.env.CLOUD_API_KEY, 2000);
  if (!endpoint || !key) throw new Error('Cloud fallback is not configured.');
  const data = await requestJson(endpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${key}`
    },
    body: JSON.stringify({
      model: text(process.env.CLOUD_MODEL, 120) || 'gpt-4o-mini',
      messages,
      temperature: 0.7,
      stream: false
    })
  }, Number(process.env.CLOUD_TIMEOUT_MS || 45000));
  const answer = data?.choices?.[0]?.message?.content;
  if (typeof answer !== 'string' || !answer.trim()) throw new Error('Cloud provider returned an empty response.');
  return answer.trim();
}

async function routeAi(messages, mode = 'auto') {
  const errors = [];
  if (mode !== 'cloud' && process.env.LLAMA_SERVER_URL) {
    try { return { answer: await callLocal(messages), provider: 'local' }; }
    catch (error) { errors.push(`local: ${error.message}`); }
  }
  try { return { answer: await callCloud(messages), provider: 'cloud' }; }
  catch (error) { errors.push(`cloud: ${error.message}`); }
  const error = new Error(`No AI provider is available. ${errors.join(' | ')}`);
  error.status = 503;
  throw error;
}

api.post('/sessions', asyncRoute(async (req, res) => {
  const title = text(req.body?.title, 160) || 'New conversation';
  const session = await ChatSession.create({ ownerId: req.ownerId, title });
  res.status(201).json({ sessionId: session.id, title: session.title, createdAt: session.createdAt });
}));

api.get('/sessions', asyncRoute(async (req, res) => {
  const sessions = await ChatSession.find({ ownerId: req.ownerId })
    .sort({ updatedAt: -1 }).limit(100).lean();
  res.json({ sessions });
}));

api.get('/sessions/:sessionId/messages', asyncRoute(async (req, res) => {
  if (!mongoose.isValidObjectId(req.params.sessionId)) return res.status(400).json({ error: 'Invalid sessionId.' });
  const session = await ChatSession.findOne({ _id: req.params.sessionId, ownerId: req.ownerId }).lean();
  if (!session) return res.status(404).json({ error: 'Session not found.' });
  const messages = await ChatMessage.find({ sessionId: session._id, ownerId: req.ownerId })
    .sort({ createdAt: 1 }).limit(500).lean();
  res.json({ session, messages });
}));

api.post('/sessions/:sessionId/messages', asyncRoute(async (req, res) => {
  const { sessionId } = req.params;
  if (!mongoose.isValidObjectId(sessionId)) return res.status(400).json({ error: 'Invalid sessionId.' });
  const role = req.body?.role;
  const content = text(req.body?.content, 100000);
  const provider = ['local', 'cloud', 'client-local', 'system'].includes(req.body?.provider) ? req.body.provider : 'system';
  if (!['user', 'assistant', 'system'].includes(role) || !content) {
    return res.status(400).json({ error: 'role and content are required.' });
  }
  const session = await ChatSession.findOne({ _id: sessionId, ownerId: req.ownerId });
  if (!session) return res.status(404).json({ error: 'Session not found.' });
  const message = await ChatMessage.create({ sessionId, ownerId: req.ownerId, role, content, provider });
  session.updatedAt = new Date();
  if (role === 'user' && session.title === 'New conversation') session.title = content.slice(0, 80);
  await session.save();
  res.status(201).json({ message });
}));

api.post('/chat', asyncRoute(async (req, res) => {
  const prompt = text(req.body?.message, 20000);
  if (!prompt) return res.status(400).json({ error: 'message is required.' });
  let session;
  if (req.body?.sessionId && mongoose.isValidObjectId(req.body.sessionId)) {
    session = await ChatSession.findOne({ _id: req.body.sessionId, ownerId: req.ownerId });
    if (!session) return res.status(404).json({ error: 'Session not found.' });
  } else {
    session = await ChatSession.create({ ownerId: req.ownerId, title: prompt.slice(0, 80) });
  }

  const history = await loadHistory(session.id, req.ownerId);
  const memory = await UserMemory.findOne({ ownerId: req.ownerId }).lean();
  const messages = [];
  let sources = [];
  if (memory?.content) messages.push({ role: 'system', content: `Long-term user context:\n${memory.content}` });
  if (req.body?.mode === 'research') {
    try {
      sources = await searchWeb(prompt, 5);
      const sourceNotes = sources.map((source, index) => `[${index + 1}] ${source.title}\nURL: ${source.url}\nSnippet: ${source.snippet}\nPage extract: ${source.excerpt}`).join('\n\n');
      messages.push({ role: 'system', content: 'You are in live web research mode. Answer using the supplied current web sources. Separate sourced facts from uncertainty, never invent facts, cite sources as [1], [2], and explain conflicts.\n\nWEB SOURCES:\n' + sourceNotes });
    } catch (error) {
      return res.status(502).json({ error: 'Live web search failed: ' + error.message, sessionId: session.id });
    }
  }
  messages.push(...history, { role: 'user', content: prompt });

  await ChatMessage.create({ sessionId: session.id, ownerId: req.ownerId, role: 'user', content: prompt, provider: 'system' });
  let result;
  try {
    result = await routeAi(messages, req.body?.mode === 'cloud' ? 'cloud' : 'auto');
  } catch (error) {
    res.status(error.status || 503).json({ error: error.message, sessionId: session.id });
    return;
  }
  await ChatMessage.create({
    sessionId: session.id, ownerId: req.ownerId, role: 'assistant',
    content: result.answer, provider: result.provider
  });
  session.updatedAt = new Date();
  await session.save();

  const blocks = [...result.answer.matchAll(/```([a-zA-Z0-9_+.-]*)\n([\s\S]*?)```/g)];
  for (const block of blocks) {
    await CodeSnippet.create({
      ownerId: req.ownerId, sessionId: session._id,
      language: block[1] || 'text', content: block[2].trim()
    });
  }
  res.json({ sessionId: session.id, reply: result.answer, provider: result.provider, sources });
}));

api.get('/memory', asyncRoute(async (req, res) => {
  const memory = await UserMemory.findOne({ ownerId: req.ownerId }).lean();
  res.json({ memory: memory?.content || '', updatedAt: memory?.updatedAt || null });
}));

api.put('/memory', asyncRoute(async (req, res) => {
  const content = text(req.body?.content, 30000);
  const memory = await UserMemory.findOneAndUpdate(
    { ownerId: req.ownerId },
    { $set: { content, updatedAt: new Date() } },
    { upsert: true, new: true, setDefaultsOnInsert: true }
  );
  res.json({ memory: memory.content, updatedAt: memory.updatedAt });
}));

api.post('/code', asyncRoute(async (req, res) => {
  const content = text(req.body?.content, 500000);
  if (!content) return res.status(400).json({ error: 'content is required.' });
  const snippet = await CodeSnippet.create({
    ownerId: req.ownerId,
    sessionId: mongoose.isValidObjectId(req.body?.sessionId) ? req.body.sessionId : null,
    language: text(req.body?.language, 40) || 'text',
    filePath: text(req.body?.filePath, 500),
    content
  });
  res.status(201).json({ snippet });
}));

api.get('/code', asyncRoute(async (req, res) => {
  const snippets = await CodeSnippet.find({ ownerId: req.ownerId })
    .sort({ createdAt: -1 }).limit(100).lean();
  res.json({ snippets });
}));

api.post('/github/push', asyncRoute(async (req, res) => {
  const repo = text(req.body?.repo, 200);
  const filePath = text(req.body?.filePath, 500);
  const content = typeof req.body?.content === 'string' ? req.body.content : '';
  const commitMessage = text(req.body?.commitMessage, 200) || 'Add AI-generated file';
  const token = text(req.body?.githubToken, 5000) || text(process.env.GITHUB_TOKEN, 5000);

  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo)) return res.status(400).json({ error: 'repo must be owner/name.' });
  if (!filePath || filePath.startsWith('/') || filePath.split('/').some((part) => part === '..' || !part)) {
    return res.status(400).json({ error: 'filePath must be a safe relative repository path.' });
  }
  if (!content || Buffer.byteLength(content, 'utf8') > 900000) return res.status(400).json({ error: 'content is empty or exceeds 900 KB.' });
  if (!token) return res.status(400).json({ error: 'GitHub token is required.' });

  const octokit = new Octokit({ auth: token, userAgent: 'Ai-BBGI-Android/1.0' });
  const [owner, name] = repo.split('/');
  let sha;
  try {
    const existing = await octokit.rest.repos.getContent({ owner, repo: name, path: filePath });
    if (!Array.isArray(existing.data) && existing.data.type === 'file') sha = existing.data.sha;
  } catch (error) {
    if (error.status !== 404) throw error;
  }
  const result = await octokit.rest.repos.createOrUpdateFileContents({
    owner,
    repo: name,
    path: filePath,
    message: commitMessage,
    content: Buffer.from(content, 'utf8').toString('base64'),
    ...(sha ? { sha } : {})
  });
  res.status(200).json({
    ok: true,
    repo,
    filePath,
    commitSha: result.data.commit.sha,
    contentUrl: result.data.content?.html_url || null
  });
}));

app.use('/api', api);

app.use((error, req, res, next) => {
  const status = Number(error.status) || (error.name === 'ValidationError' ? 400 : 500);
  if (status >= 500) console.error('[api-error]', error.message);
  res.status(status).json({ error: status >= 500 ? 'Internal server error.' : error.message });
});

const port = Number(process.env.PORT || 10000);
let server;

async function connectMongo() {
  const uri = text(process.env.MONGODB_URI, 4000);
  if (!uri) {
    console.warn('[startup] MONGODB_URI is missing; database routes will return 503 until configured.');
    return;
  }
  try {
    await mongoose.connect(uri, {
      dbName: process.env.MONGODB_DB_NAME || 'KedarAI',
      serverSelectionTimeoutMS: 10000,
      maxPoolSize: 10,
      minPoolSize: 0
    });
    console.log('[startup] MongoDB connected.');
  } catch (error) {
    console.error('[startup] MongoDB connection failed:', error.message);
    const retry = setTimeout(() => connectMongo(), 15000);
    retry.unref();
  }
}

mongoose.connection.on('disconnected', () => {
  console.warn('[mongo] Connection lost.');
});
mongoose.connection.on('error', (error) => {
  console.error('[mongo] Connection error:', error.message);
});

async function start() {
  await connectMongo();
  server = app.listen(port, '0.0.0.0', () => console.log(`Ai-BBGI backend listening on ${port}`));
}
start();

async function shutdown(signal) {
  console.log(`[shutdown] ${signal}`);
  if (server) server.close();
  await mongoose.disconnect().catch(() => {});
  process.exit(0);
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
