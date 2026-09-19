# Security improvements

Tracking document for security hardening around authentication, cookies and CSRF.
Findings are prioritised; each item notes the affected code so it can be picked up later.

## Current situation

### Cookie issuance

All cookies are created with `sttp.model.headers.CookieValueWithMeta`:

- `ApiSecurity.createCookies` (`backend/src/main/scala/nl/amony/modules/auth/api/ApiSecurity.scala`)
  sets:
  - `access_token` — `httpOnly=true`, `secure=authConfig.secureCookies`, `path=/`, expires after the access-token lifetime
  - `refresh_token` — `httpOnly=true`, `secure=authConfig.secureCookies`, `path=/`, expires after the refresh-token lifetime
  - `XSRF-TOKEN` — `httpOnly=false` (must be readable by JS for the double-submit pattern), `secure=authConfig.secureCookies`
- `ApiSecurity.createLogoutCookes` expires the same three cookies.
- The OAuth `oauth_login_state` cookie in `AuthRoutes.oauth2loginEndpoint`
  (`backend/src/main/scala/nl/amony/modules/auth/http/AuthRoutes.scala`) is
  `httpOnly=true` but **hardcodes `secure=false`**.
- `secure-cookies` defaults to `true` (`backend/src/main/resources/auth.conf`), overridden to
  `false` only in the local dev/test JVM (`backend/build.sbt`).

### CSRF protection that exists

- Double-submit token: `ApiSecurity.requireXsrfProtection` compares the `XSRF-TOKEN` cookie
  against the `X-XSRF-TOKEN` header. It is invoked from `resolveToken` **only when the token is
  authenticated and the method is POST/PUT/PATCH/DELETE**.
- Frontend sends the header from `frontend/src/api/AxiosInstance.ts`
  (`xsrfTokenHeader()` reads the `XSRF-TOKEN` cookie).
- All state-changing business endpoints (`ResourceRoutes`, `CollectionRoutes`, `AdminRoutes`)
  require an authenticated permission, so they are covered by the double-submit check.
- OAuth login uses a server-side `state` (DB row + cookie), so forged callbacks are rejected.

### Deployment shape

Same origin: the backend serves the SPA (`WebServer.webAppRoutes`) and in production nginx
fronts a single `DOMAIN_SUB_AMONY` domain for both the app and `/api`.

## Findings / gaps

| # | Issue | Severity |
|---|-------|----------|
| 1 | No explicit `SameSite` attribute on any cookie (relies on browser default) | High |
| 2 | OAuth `oauth_login_state` cookie hardcodes `secure=false` | High |
| 3 | `refresh` and `logout` are `Endpoint[Unit, …]` and are **not** XSRF-checked | Medium |
| 4 | No `Origin`/`Referer` validation ("only accept calls from our domain") | Medium |
| 5 | No security headers / HSTS (backend or nginx); no `:80 -> :443` redirect | Medium |
| 6 | Refresh tokens are stateless JWTs re-issued with a fresh 7-day expiry (no rotation/reuse detection) | Low |
| 7 | No `__Host-` cookie name prefix; XSRF token not bound to the session | Low |

## Proposed changes

### P0 — Explicit cookie hardening — DONE

- [x] Set `SameSite=Lax` explicitly on `access_token`, `refresh_token`, `XSRF-TOKEN`,
      logout and `oauth_login_state` cookies.
- [x] Give `oauth_login_state` `secure = authConfig.secureCookies` instead of hardcoded `false`.
- [x] Give `XSRF-TOKEN` the same lifetime as the refresh token so session persistence across
      browser restarts is not broken once XSRF is enforced on `refresh`.

Rationale: `Lax` (not `Strict`) is required because the OAuth callback is a cross-site
top-level GET redirect from the identity provider; `Strict` would not send the state cookie
and would break login. `Lax` still blocks cross-site POST/PUT/PATCH/DELETE.

### P0 — Enforce XSRF on `refresh` and `logout` — DONE

- [x] Add the `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header inputs to `refreshEndpoint` and
      `logoutEndpoint` and validate them, returning `401` on mismatch.
- Frontend already sends `X-XSRF-TOKEN` on refresh, so this is compatible.

The check lives in the security layer, not in controller logic:

- `xsrfSecurityInput` (`TapirUtil.scala`) is the reusable security input, attached with
  `.securityIn(xsrfSecurityInput)`.
- `ApiSecurity.authorizeXsrf` is the authorization function
  `(Option[String], Option[String]) => Either[SecurityError, AuthToken]`.
- New DSL variants accept an arbitrary authorization function
  `SI => Either[SecurityError, AuthToken]`: `serverLogic(endpoint, authorize = …)` and
  `serverLogicT(endpoint, authorize = …)` (`backend/src/main/scala/nl/amony/lib/tapir/dsl/DSL.scala`).
  This allows any free security input type `SI`, not just `SecurityInput`, while keeping
  authorization out of the endpoint logic.

### P1 — Origin/Referer allowlist

- Add an http4s middleware (in `WebServer.run` or when assembling `apiRoutes` in `App.scala`)
  that, for state-changing methods, rejects requests whose `Origin` (fallback `Referer`) does
  not match the configured `authConfig.publicUri` origin with `403`.
- Note: CORS is not the mechanism here. The app is same-origin, so no CORS headers are needed;
  CORS does not prevent CSRF. An explicit `Origin` check is the relevant control.
- Consider also http4s `AllowedHosts` middleware for the configured host (DNS-rebinding /
  Host-header protection).

### P1 — Reverse-proxy / transport headers

- Add a security-header block to the nginx templates:
  - `Strict-Transport-Security: max-age=63072000; includeSubDomains`
  - `X-Content-Type-Options: nosniff`
  - `X-Frame-Options: DENY` (or `Content-Security-Policy: frame-ancestors 'none'`)
  - `Referrer-Policy: strict-origin-when-cross-origin`
  - A restrictive CSP (start report-only; the SPA and media player need tuning).
- Add an `:80` server that redirects to `:443`.

### P2 — Optional hardening

- Use `__Host-` cookie name prefixes (`__Host-access_token`, etc.); requires `Secure`,
  `Path=/` and no `Domain` (already true) and updating `Cookies.get("XSRF-TOKEN")` in the
  frontend.
- Refresh-token rotation + reuse detection in `TokenManager` so a stolen refresh token cannot
  be replayed indefinitely.
- Constant-time comparison for the XSRF token (minor; tokens are random).
