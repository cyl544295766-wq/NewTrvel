# Travel OS

Travel OS is a long-term travel management platform for private use with trusted friends.

## Current Scope

The project is currently at the V0.4 development stage. It includes the private authentication foundation plus the first Trip business modules:

- pnpm monorepo workspace
- React + Vite web app
- NestJS API app
- Prisma + PostgreSQL setup
- Docker Compose for local PostgreSQL and Redis
- Health check and database health APIs
- Private username/password login
- HttpOnly cookie authentication
- Seeded admin user
- Trip list, create, detail, edit, and archive flows
- Trip member read API
- Trip day generation and update APIs
- Itinerary place create, update, delete, and reorder flows
- Trip expense list, create, update, delete, and summary flows

The current implementation is functional for local development. It is not yet production hardened.

## Requirements

- Node.js 20+
- Corepack
- Docker Desktop

## Local Setup

```bash
corepack prepare pnpm@9.15.0 --activate
corepack pnpm install
copy .env.example .env
docker compose up -d
corepack pnpm db:generate
corepack pnpm db:migrate
corepack pnpm db:seed
corepack pnpm dev
```

Web: http://localhost:5173

API health: http://localhost:3000/api/health

Database health: http://localhost:3000/api/health/database

## Development Login

The default local seed account is controlled by `.env`:

```txt
SEED_ADMIN_USERNAME=admin
SEED_ADMIN_PASSWORD=ChangeMe123
```

Change these values before using the system outside local development.

## Development Commands

```bash
corepack pnpm dev
corepack pnpm dev:web
corepack pnpm dev:api
corepack pnpm build
corepack pnpm typecheck
corepack pnpm lint
corepack pnpm db:generate
corepack pnpm db:migrate
corepack pnpm db:seed
```

## Verification

The current codebase has been verified with:

```bash
corepack pnpm typecheck
corepack pnpm lint
corepack pnpm build
```

Local services were also verified at:

- http://localhost:5173
- http://localhost:3000/api/health
- http://localhost:3000/api/health/database

## Development Rules

- Trip is the core business entity.
- Every future business module must directly or indirectly relate to Trip.
- Before any feature implementation, design database, business flow, API, and page structure first.
