# Sign in with Apple — flow and differences from our implementation

This document describes how Sign in with Apple works and where it diverges from the
OAuth2/OIDC login flow currently implemented in the backend (`FederatedLoginService` /
`AuthRoutes`). It is meant as input for adding Apple as an identity provider.

Apple is *mostly* OIDC, but not compatible enough to work with the current generic
implementation without changes.

## 1. Apple endpoints

Taken from Apple's discovery document
(`https://appleid.apple.com/.well-known/openid-configuration`):

| Item | Value |
|---|---|
| Issuer | `https://appleid.apple.com` |
| Authorization | `https://appleid.apple.com/auth/authorize` |
| Token | `https://appleid.apple.com/auth/token` |
| JWKS | `https://appleid.apple.com/auth/keys` |
| UserInfo | **none — Apple does not expose a userinfo endpoint** |
| `response_types_supported` | `code` |
| `response_modes_supported` | `query`, `fragment`, `form_post` |
| `token_endpoint_auth_methods_supported` | `client_secret_post` (only) |
| `scopes_supported` | `openid`, `email`, `name` (no `profile`) |
| `id_token_signing_alg_values_supported` | `RS256` |
| `code_challenge_methods_supported` | *(absent — do not rely on PKCE)* |

## 2. The Apple flow

### 2.1 Authorization request

The browser is redirected to Apple with (roughly) these parameters:

```
GET https://appleid.apple.com/auth/authorize
      ?client_id=<Service ID>
      &redirect_uri=https://<host>/api/auth/callback/apple
      &response_type=code
      &response_mode=form_post
      &scope=openid email name
      &state=<opaque CSRF token>
      &nonce=<opaque>            # optional, recommended
```

Key points:

- `response_mode=form_post` is **required** whenever `name` or `email` is requested.
  Apple returns an error otherwise. Because we need the email, this is always the case.
- `name` is a valid scope, `profile` is not.
- Apple signs in with the *Service ID* (web) or the app bundle ID (native). For a web
  app this is the Service ID, and it must be registered as the `client_id`.

### 2.2 Authorization response (HTTP POST, not a redirect with query params)

Apple `POST`s to the registered `redirect_uri`:

```
POST /api/auth/callback/apple HTTP/1.1
Host: <host>
Content-Type: application/x-www-form-urlencoded

state=<opaque>&code=<authorization code>&user={"name":{"firstName":"Jane","lastName":"Doe"},"email":"jane@example.com"}
```

- `user` is **only sent on the first authorization** for a given app/user pair. After
  that it is never sent again, and the name is **not** present in the `id_token`.
  If we want a name, we must capture it here, on the first login.
- `id_token` is only included in this response for hybrid flows
  (`response_type=code id_token`). For the plain `response_type=code` we use, the
  `id_token` comes from the token endpoint instead.
- The authorization code is single-use and expires quickly (~5 minutes).

### 2.3 Client authentication: `client_secret` is a JWT

Apple accepts no shared secret. The `client_secret` sent to the token endpoint must be a
short-lived **ES256 JWT** signed with the `.p8` key created in the Apple Developer portal:

```
header:  { "alg": "ES256", "kid": "<Key ID>" }
claims:  {
           "iss": "<Team ID>",
           "iat": <now>,
           "exp": <now + up to 15777000 s>,   // Apple caps this at ~6 months
           "aud": "https://appleid.apple.com",
           "sub": "<Service ID == client_id>"
         }
```

It must be regenerated before it expires. Apple only supports `client_secret_post`
(the secret goes in the form body), not HTTP Basic.

### 2.4 Token exchange

```
POST https://appleid.apple.com/auth/token
Content-Type: application/x-www-form-urlencoded

client_id=<Service ID>
&client_secret=<ES256 JWT from 2.3>
&code=<authorization code>
&grant_type=authorization_code
&redirect_uri=https://<host>/api/auth/callback/apple
```

Response:

```json
{
  "access_token": "...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "refresh_token": "...",
  "id_token": "<RS256 JWT>"
}
```

Note there is no `scope` in the response, and the meaningful identity data is in the
`id_token`.

### 2.5 Where the identity comes from

Because there is no userinfo endpoint, identity must be read from the **`id_token`**:

- Verify the RS256 signature against Apple's JWKS (`/auth/keys`).
- Validate `iss` = `https://appleid.apple.com`, `aud` = the Service ID, and `exp`.
- Optionally validate `nonce` against the value sent in the authorization request.

Claims of interest: `sub` (pairwise, stable per Service ID), `email`,
`email_verified`, `is_private_email`, `nonce`, `real_user_status`.

`name` / `given_name` / `family_name` are **not** claims in the `id_token` — see 2.2.
`email` may be an Apple private-relay address (`@privaterelay.appleid.com`); the
`is_private_email` claim indicates this.

## 3. What we currently implement

The login flow in `AuthRoutes` / `FederatedLoginService` is:

1. `GET /api/auth/login/{provider}` builds the authorize URL with `client_id`,
   `response_type=code`, `redirect_uri`, `scope` and `state`, then redirects.
   State is a random value stored in the `oauth_state` table and mirrored into the
   `oauth_login_state` cookie (`SameSite=Lax`).
2. `GET /api/auth/callback/{provider}?code=...&state=...` reads `code` and `state` from
   the **query string**, compares `state` with the cookie, and consumes the DB state row.
3. `FederatedLoginService.getToken` exchanges the code at `tokenUrl` using the static
   `clientSecret` from config.
4. `FederatedLoginService.getUserInfo` calls `userInfoUrl` with the access token as a `Bearer`
   header and maps the JSON into `UserInfo`.
5. A local user is looked up/created by email and a local JWT session is issued.

`IdentityProvider` config models a provider as: `name`, `clientId`, `clientSecret`,
`authorizeUrl`, `tokenUrl`, `userInfoUrl`, `scopes`, `defaultRoles`, `adminOnly`.

## 4. Differences

| Concern | Apple | Our implementation | Gap |
|---|---|---|---|
| Authorize params | requires `response_mode=form_post` for name/email | sends no `response_mode` (defaults to query) | add `response_mode` per provider |
| Scopes | `openid email name` | default `openid profile email` | use per-provider scopes; Apple rejects `profile` |
| Callback request | `POST` (form-urlencoded body) | `GET` with query params | need a POST / form_post callback variant |
| Callback payload | `code`, `state`, `user` (first login) | `code`, `state` only | parse form body; capture `user` once |
| State cookie | cross-site POST → `SameSite=Lax` cookie is not sent | `SameSite=Lax` cookie is required | use `SameSite=None; Secure`, or rely on DB state only |
| Client secret | ES256 JWT from `.p8` key | static string | add a client-secret strategy for JWT generation |
| Token auth method | `client_secret_post` only | sends secret in body | already compatible |
| Token response | includes `id_token` | `OauthTokenResponse` has no `id_token` | add `id_token: Option[String]` |
| Identity source | `id_token` via JWKS verification | `GET userInfoUrl` with Bearer token | add id_token verification path |
| Name | only in `user` on first login | not modelled | persist name on first authorization |
| Email | in `id_token`; may be private relay | read from userinfo | handle relay email / use `sub` as key |
| UserInfo endpoint | none | `userInfoUrl` is required | make it optional |
| PKCE | not advertised | not used | no change (can't rely on it) |

## 5. Consequences

The overall pattern — redirect with server-side state, exchange the code, resolve the
user, issue our own session — is unchanged and still valid. What Apple requires is a set
of per-provider seams rather than a different flow:

- **Authorize request**: per-provider extra params (`response_mode`, optional `nonce`).
- **Callback**: support both `GET`+query (Google, Dex) and `POST`+form (Apple), sharing
  the same core logic.
- **Client authentication**: replace the static `clientSecret` with a strategy that can
  either return a configured secret or generate (and cache) an Apple ES256 JWT.
- **Identity resolution**: resolve user info either from a userinfo endpoint or by
  verifying an `id_token` against the provider's JWKS; merge the first-login `user`
  payload for Apple.
- **State cookie**: for form_post providers the cookie cannot be `SameSite=Lax`.

Google, by contrast, matches the current implementation closely (static secret,
`client_secret_post`, query callback, and a userinfo endpoint returning exactly the
fields in `UserInfo`), so it only needs configuration.

## 6. References

- Apple: [Sign in with Apple REST API](https://developer.apple.com/documentation/signinwithapplerestapi)
- Apple: [Request an authorization](https://developer.apple.com/documentation/signinwithapplerestapi/request-an-authorization-to-the-sign-in-with-apple-server)
- Apple: [Generate and validate tokens](https://developer.apple.com/documentation/signinwithapplerestapi/generate-and-validate-tokens)
- Apple: [Use `response_mode=form_post`](https://developer.apple.com/forums/thread/121760)
- Google: [OpenID Connect discovery](https://accounts.google.com/.well-known/openid-configuration)
