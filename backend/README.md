# Ai-BBGI Backend

Node.js 20 + Express + Mongoose API for chat sessions, user memory, generated code, hybrid llama.cpp/cloud routing and GitHub commits.

## Local run

```bash
cd backend
cp .env.example .env
npm install
npm start
```

Set `MONGODB_URI` and a long random `APP_API_KEY` before using protected endpoints. The API deliberately refuses protected requests while `APP_API_KEY` is unset. Configure Atlas Network Access for the Render service's outbound IP range; do not use `0.0.0.0/0` as a permanent production rule.

## Render

Create a Node web service from this repository with root directory `backend`, build command `npm ci`, start command `npm start`, and health check `/health`. Or use the repository's `backend/render.yaml` Blueprint. Set secrets in Render Environment:
- `MONGODB_URI`: Atlas SRV URI for database user with readWrite on `KedarAI`
- `APP_API_KEY`: long random app-to-backend bearer key; configure the same value in Android Settings
- `CLOUD_API_KEY`: optional OpenAI-compatible provider key for fallback
- `GITHUB_TOKEN`: optional server-side fallback PAT; the Android client may send a repository-scoped token to the push endpoint

Optional routing:
- `LLAMA_SERVER_URL`: reachable llama.cpp server base URL, e.g. a secure tunnel URL exposing `/v1/chat/completions`
- `CLOUD_API_URL`, `CLOUD_MODEL`: OpenAI-compatible fallback endpoint/model

The Android Local Mode talks directly to the llama.cpp server URL saved on the phone. If that request fails, it falls back to this backend's `/api/chat` endpoint.

## API

All `/api/*` routes require `Authorization: Bearer APP_API_KEY` and optionally `X-Client-Id` (stable device ID).

- `POST /api/sessions`, `GET /api/sessions`
- `GET /api/sessions/:sessionId/messages`, `POST /api/sessions/:sessionId/messages`
- `POST /api/chat` body: `{ "message": "...", "sessionId": "...", "mode": "auto|cloud" }`
- `GET /api/memory`, `PUT /api/memory` body: `{ "content": "..." }`
- `GET /api/code`, `POST /api/code`
- `POST /api/github/push` body: `{ "repo": "owner/name", "filePath": "src/file.kt", "content": "...", "commitMessage": "...", "githubToken": "..." }`
- `GET /health` (public readiness/status)

GitHub tokens are never written to MongoDB or application logs. Prefer a fine-grained PAT scoped only to the target repository with Contents read/write access.
