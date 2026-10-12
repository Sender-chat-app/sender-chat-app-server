# Sender Chat

Sender Chat is a production-oriented real-time one-to-one messaging application.

## Technology

- **Backend:** Java 21, Spring Boot, Spring Security, Spring Data JPA, PostgreSQL, Redis, WebSocket/STOMP
- **Frontend:** Next.js App Router, TypeScript, React, TanStack Query, Tailwind CSS, shadcn-style UI
- **Development:** Maven, npm, Docker Compose for local infrastructure

## Repository layout

```text
backend/   Spring Boot application
frontend/  Next.js application
docs/      Persistent architecture and implementation context
perf/      Exploratory k6 load-test harness (seed data, scenarios, operator runbook)
```

## Exploratory load testing

The repository includes an optional **k6** harness under [`perf/`](perf/) for manual, exploratory runs against the **public REST + STOMP API** on a **single app instance** (in-memory STOMP broker). It is not a CI gate or production SLA certificate.

- **Operator runbook:** [`perf/RUNBOOK.md`](perf/RUNBOOK.md) — prerequisites, scenarios (`smoke`, `realistic`, `stress`), soft-stop thresholds, and what to watch on host / Postgres / Redis.
- **Harness summary:** [`perf/README.md`](perf/README.md) — what each ticket delivered.
- **Perf env overlay:** copy values from [`perf/env.perf.example`](perf/env.perf.example) into `backend/.env` only on dedicated load-test machines. Normal local development keeps the safe defaults in [`backend/.env.example`](backend/.env.example); do not commit secrets or local `.env` files.

**v1 scope:** direct-message pairs, REST send, STOMP `/user/queue/events`, presence heartbeats, optional inbox/history reads in the realistic scenario. **Phase 2 (not in v1):** group chat, search, typing, attachments under load, multi-instance STOMP fan-out, Grafana dashboards, and automated perf in CI. See [`docs/plan.md`](docs/plan.md).

## Development status

The repository currently contains starter backend and frontend applications. Feature implementation follows the vertical slices in [`docs/plan.md`](docs/plan.md).

The first planned feature slices are:

1. Project foundation and configuration
2. Authentication
3. Users and friendships
4. Direct conversations
5. Persistent messaging
6. Read/delivery state
7. Realtime messaging, receipts, and presence

## Architectural decisions

- PostgreSQL is the source of truth for durable application data.
- Redis is used only for ephemeral presence, short-lived cache, and realtime fan-out.
- Direct-message creation is REST-based; WebSocket/STOMP distributes committed events.
- Access tokens are short-lived JWTs; refresh tokens are rotated opaque tokens stored in secure HttpOnly cookies.
- TanStack Query owns server state in the frontend. Local React state owns transient UI state.
- Registration validates password confirmation in the browser only; it is never sent to the backend.
- Logout supports revoking the current session or all refresh sessions. Changing a password revokes all refresh sessions.
- The product supports direct and group conversations, text messages, and optional file attachments. Blocking, moderation, and account recovery are deferred.

See the complete design in [`docs/architecture.md`](docs/architecture.md) and the implementation sequence in [`docs/plan.md`](docs/plan.md).

When the backend is running, API documentation is available at `http://localhost:8080/swagger-ui.html`.

## Working agreements

- Read the relevant documentation before changing a feature.
- Implement one vertical slice at a time.
- Keep REST operations authoritative and realtime events idempotent.
- Validate and authorize every request on the server.
- Add behavior-focused tests with each meaningful feature.
- Do not add infrastructure or dependencies without documenting the reason.
