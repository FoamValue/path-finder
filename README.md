<div align="center">

# PathFinder

**Private, secure, auditable file management for individuals & small teams**

> *Find the system path.* Unified content-file management with large-file upload, org-scoped data permission, operation audit, and one-click Docker deployment.

---

[![CI](https://github.com/FoamValue/path-finder/actions/workflows/ci.yml/badge.svg)](https://github.com/FoamValue/path-finder/actions/workflows/ci.yml)
![Java 26](https://img.shields.io/badge/Java-26-007396?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F?style=flat-square&logo=spring&logoColor=white)
![React 18](https://img.shields.io/badge/React-18-61DAFB?style=flat-square&logo=react&logoColor=white)
![Ant Design 5](https://img.shields.io/badge/Ant%20Design-5-1677FF?style=flat-square)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=flat-square&logo=docker&logoColor=white)
![License: MIT](https://img.shields.io/badge/License-MIT-green.svg?style=flat-square)

**English** · [简体中文](./README.zh-CN.md)

</div>

---

## What is PathFinder?

PathFinder is a **single-org, privately deployed** file management system that answers three core questions about your files: *where to put them, who can see them, and how to find them.*

It supports chunked upload with resume and de-dup, range-based resume download, org-scoped data permissions, and full operation auditing — packaged for a clean one-command deployment.

## Features

- 🔐 **Secure login** – image captcha, client-side RSA password encryption, BCrypt hashes; lockout for 10 minutes after 5 consecutive failures; single-session kickout, session-timeout auto logout, and forced password change on first login.
- 📂 **Large-file transfer** – chunked upload / resume / instant de-dup via `cn.chenxinjie:upload-file:1.0.0`, per-chunk MD5 checks, async merge, and Range resume download.
- 🗂 **Data permissions** – three-level ownership (personal / org / public), org-tree visibility inheritance, enforced server-side filtering.
- 🔍 **Efficient search** – real server-side pagination (`LIMIT/OFFSET` on the database layer) plus fuzzy filename search.
- ♻️ **Soft delete** – recycle bin retains files for 30 days, with restore and physical purge.
- 📝 **Audit trail** – login / upload / download / delete / ownership-change / password-change are fully recorded and archived for 12 months.
- 💾 **Disk persistence** – UUID + date-partitioned directories; 85% disk-usage alarm, auto-initialized directories on startup.
- 🔄 **Directory sync** – a scheduled scanner ingests files dropped into an import directory and re-verifies on-disk state (missing / replaced files are flagged and download is blocked until refreshed).
- 🐳 **Containerized deployment** – nginx:alpine (TLS) + server + redis:9 + mysql:8, host-mounted storage and persistent TLS certificate volume.

## Tech Stack

| Layer | Technology |
|---|---|
| Frontend | React 18 · TypeScript · Ant Design 5 / ProComponents · Vite |
| Backend | JDK 26 · Spring Boot 4.1.1 · Spring Security · Spring Data JPA · Jackson 3 |
| Cache | Redis 9 (sessions / captcha / lockout / metadata; fixed base TTL + random jitter to resist cache stampede) |
| Database | MySQL 8 |
| Large-file component | `cn.chenxinjie:upload-file:1.0.0` (Spring Boot Starter jakarta, MIT) |
| Deployment | Docker Compose · nginx:alpine · TLS |

> The `upload-file` component provides chunked upload, resume, MD5 de-dup, async merge, Range download, storage cleanup, and optional global quota. Its integration history and upgrade notes live in [ADR-001](docs/design/ADR-001-upload-file-starter-jakarta.md) and [UPGRADE](docs/design/UPGRADE-upload-file-starter-jakarta.md).

## Project Structure

```
path-finder/
├── server/                    # Backend (Spring Boot Maven, package cn.chenxinjie.pathfinder)
│   └── src/main/java/cn/chenxinjie/pathfinder/
│       ├── config/            # Security / Redis / exceptions / scheduler / seed
│       ├── controller/        # auth / user / org / file / recycle / log / storage
│       ├── service/           # Business services & data-permission decisions
│       ├── repository/        # JPA repositories (real pagination)
│       ├── entity/            # JPA entities (read-only ts field mapping)
│       ├── security/          # Token filter / current-user context / upload authorization & audit
│       ├── dto/               # Request / response DTOs
│       └── util/              # RSA / captcha / Redis TTL policy / path helpers
├── frontend/                  # Frontend (Vite + React + AntD)
│   └── src/
│       ├── pages/             # login / changePassword / fileList / recycle / user / org / log / storage
│       ├── components/        # MainLayout / UploadModal (chunked upload)
│       ├── api/               # Request wrapper & types
│       └── utils/             # RSA encryption / chunk MD5 / size formatting
├── docker/                    # docker-compose + nginx.conf + Dockerfiles
├── scripts/backup.sh          # Backup script (storage + MySQL + Redis)
└── docs/                      # PRD / TSDD / PLAN / TESTCASES / REVIEW
```

## Quick Start

### Prerequisites

- JDK 26, Maven 3.9+
- Node.js 20+
- Redis (for local dev, `docker run -d -p 6379:6379 redis:7` is enough)

### 1. Start the backend

```bash
cd server
mvn spring-boot:run
# Service at http://localhost:8080
```

> Requires MySQL 8 (database `pathfinder`); connection parameters are set via the env vars below.

### 2. Start the frontend

```bash
cd frontend
npm install
npm run dev
# Browser at http://localhost:8000 (/api, /upload, /captcha are proxied to 8080)
```

### 3. Initial account

| Account | Password | Note |
|---|---|---|
| `admin` | `Init@123` | System administrator; forced password change on first login |

## Configuration

Core environment variables (override the defaults in `server/src/main/resources/application.yml`):

| Variable | Default | Description |
|---|---|---|
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DB` / `MYSQL_USER` / `MYSQL_PASSWORD` | localhost / 3306 / pathfinder / pathfinder / pathfinder123 | MySQL connection |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | localhost / 6379 / *(blank)* | Redis connection |
| `STORAGE_ROOT` | `./data/storage` | File storage root (files/upload/del/tmp/archive) |
| `RSA_PRIVATE_KEY_PATH` | *(blank)* | Path to the RSA private key; mount a persistent file in production so the key survives restarts |
| `CAPTCHA_ENABLED` | `true` | Login captcha switch; set `false` **only** for automation tests — must stay `true` in production |
| `ADMIN_BOOTSTRAP_PASSWORD` | *(blank)* | Fixed password for the first seeded `admin` (skips forced change), used for reproducible E2E seed accounts |
| `SYNC_ENABLED` | `true` | Directory sync scanner switch |
| `SYNC_WATCH_DIR` | `./data/import` (Docker: `/data/storage/import`) | External import directory, automatically ingested by the scanner (admin + public space) |
| `SYNC_INTERVAL` | `5m` | Sync scan interval (e.g. `5m` / `1h`) |
| `SYNC_SKIP_RECENT_SECONDS` | 30 | Skip files written within the last N seconds (avoid half-written files) |
| `SYNC_DEDUP_BY_MD5` | `true` | Deduplicate imports by MD5 |
| `UPLOAD_QUOTA_MAX_BYTES` | 0 (disabled) | Global quota (bytes) for the upload-file component; returns 507 when exceeded |

### Storage root

`STORAGE_ROOT` is the storage root; these subdirectories are auto-initialized on startup:

| Directory | Purpose |
|---|---|
| `files/` | Live files (`files/{yyyy-MM-dd}/{uuid}.{ext}`, UUID + date-partitioned) |
| `upload/` | Chunked-upload staging (chunks under `chunks/`, merged results under `files/`) |
| `del/` | Physically removed files in the recycle bin (moved here on soft delete, moved back on restore) |
| `tmp/` | Temp files such as batch-download ZIPs |
| `archive/` | Archived audit-log CSV |
| `import/` | External import directory (inside the storage root under Docker, see `SYNC_WATCH_DIR`) |

**Directory sync**: a single-threaded scanner (default every 5 minutes) ingests files from `import/`, moves them into `files/`, and registers them in the database (owner = admin, space = public). It also re-checks the on-disk state of every stored file — a missing physical file is flagged as *deleted from directory* and download is blocked; a replaced file is flagged as *source updated* and download returns a fresh copy. The scanner is read-only and never modifies or deletes disk files.

**Redis TTL policy**: writes use a fixed base value + random jitter (±20%) to prevent cache stampede; business-critical semantics (captcha / lockout / session / download token) explicitly override and disable jitter — see TSDD §7.

## Docker Deployment

### Option 1 — HTTP (no TLS, no certificates) — recommended for quick start

Best for local / LAN / intranet use. **No certificates required** — nginx serves plain HTTP on port 80.

```bash
# 1. Build backend & frontend images (frontend is a multi-stage build that runs `npm build`)
docker compose -f docker/docker-compose.yml -f docker/docker-compose.http.yml build

# 2. Start (no certs to mount)
docker compose -f docker/docker-compose.yml -f docker/docker-compose.http.yml up -d

# 3. Open http://<host>
```

- The `.http.yml` override swaps nginx to an HTTP-only config (no `443`, no SSL files); the published `443` port is simply left unused.
- Offline / when Docker Hub is unreachable: add the local override so Redis falls back to a locally cached image (this combination was verified by a smoke test):

  ```bash
  docker compose -f docker/docker-compose.yml -f docker/docker-compose.local.yml -f docker/docker-compose.http.yml up -d
  ```

  `.local.yml` swaps `redis:9-alpine` → the locally cached `redis:7` (the base image still tries to pull `redis:9-alpine`, which fails without network access).
- See the configuration and storage notes in the HTTPS section below (they apply to both modes).

### Option 2 — HTTPS (with mounted certificates)

For production / public exposure. This is the default config; **it requires TLS certificate files** in a `certs` volume (nginx fails to start if `fullchain.pem` / `privkey.pem` are missing).

```bash
# 1. Build backend & frontend images
docker compose -f docker/docker-compose.yml build

# 2. Mount your TLS certificates, then start
mkdir -p certs && cp fullchain.pem certs/ && cp privkey.pem certs/
docker compose -f docker/docker-compose.yml up -d
```

- Entry point: `https://<host>/` (port 80 auto-redirects to HTTPS)
- File storage: host directory bind-mounted to `/data/storage` in the container, set via `STORAGE_HOST_DIR` in `docker/.env` (default `./data`, which becomes container `STORAGE_ROOT=/data/storage`, import dir `SYNC_WATCH_DIR=/data/storage/import`)
- Data volumes: `mysql-data`, `redis-data`, `rsa-key`, `certs`

> For local deployment when Docker Hub is unreachable (using a locally cached `redis:7` image), layer the local override file:
> `docker compose -f docker/docker-compose.yml -f docker/docker-compose.local.yml up -d`
> (works together with `.http.yml`: `-f docker/docker-compose.yml -f docker/docker-compose.local.yml -f docker/docker-compose.http.yml`)

## Testing

```bash
cd server && mvn test          # Backend unit/integration tests (JUnit 5 + Mockito; integration cases need MySQL pathfinder_test)
cd frontend && npm test        # Frontend unit tests (Vitest)
```

Before backend integration tests, bring up the MySQL/Redis test databases in containers:

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.local.yml -f docker/docker-compose.test.yml up -d mysql redis
# then: cd server && mvn test
```

E2E (Playwright + local Chrome, via an isolated Docker E2E stack; the original stack is auto-restored when it finishes):

```bash
bash scripts/run-e2e-docker.sh
# Equivalent to: down current stack → reset pathfinder_test → start E2E stack with captcha bypass + seed account
#                → npm run test:e2e (case 01/02/03) → restore original stack on exit
```

E2E coverage includes login smoke test, the full TC-E2E-001 flow (upload / search / download / ownership / delete / recycle restore / audit), and TC-E2E-003 authorization bypass & forced password change. All test switches default to off and never take effect in production.

Coverage gates (see PRD §6): overall backend line coverage ≥ 80%, core modules ≥ 85%; frontend core interactions ≥ 70%.

## Documentation

| Document | Description |
|---|---|
| [PRD](docs/PRD-PathFinder-v1.0.0.md) | Product requirements (user stories / functional requirements / data-permission model) |
| [USER-MANUAL](docs/USER-MANUAL-PathFinder-v1.0.0.md) | End-user manual |
| [TSDD](docs/design/TSDD-PathFinder-v1.0.0.md) | Technical design (architecture / database / APIs / security / component integration / caching / deployment) |
| [PLAN](docs/design/PLAN-PathFinder-v1.0.0.md) | Agile iteration plan (sprints / task cards / definition of done) |
| [TESTCASES](docs/design/TESTCASES-PathFinder-v1.0.0.md) | Test cases (110+, incl. data-permission matrix) |
| [REVIEW](docs/design/REVIEW-PathFinder-v1.0.0.md) | Production-release baseline review & revision log |
| [ADR-001](docs/design/ADR-001-upload-file-starter-jakarta.md) | Decision record: jakarta starter migration of the upload component |
| [UPGRADE](docs/design/UPGRADE-upload-file-starter-jakarta.md) | Migration checklist (coordinates / contract / audit / acceptance & rollback) |
| [CHANGELOG](CHANGELOG.md) | Version history |

## License

MIT — the large-file transfer component `cn.chenxinjie:upload-file` is also MIT-licensed.