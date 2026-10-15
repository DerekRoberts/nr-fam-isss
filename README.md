[![Merge](https://github.com/bcgov/nr-fam/actions/workflows/merge.yml/badge.svg)](https://github.com/bcgov/nr-fam/actions/workflows/merge.yml)
[![Analysis](https://github.com/bcgov/nr-fam/actions/workflows/analysis.yml/badge.svg)](https://github.com/bcgov/nr-fam/actions/workflows/analysis.yml)

# Forest Access Management (FAM)

FAM defines who has access to which BC natural-resource applications, and the
roles they operate under once access is granted.

This repository is a port of
[nr-forests-access-management](https://github.com/bcgov/nr-forests-access-management)
from AWS serverless to OpenShift. What changed:

| Area           | Was                                         | Now                                       |
| -------------- | ------------------------------------------- | ----------------------------------------- |
| Backend        | Two Python FastAPI Lambdas                  | One Spring Boot service                   |
| Auth           | AWS Cognito + a pre-token-generation Lambda | BC Gov SSO (Keycloak)                     |
| Database       | AWS RDS, provisioned by Terraform           | On-prem PostgreSQL, **not** deployed here |
| Frontend       | Vue 3 on S3/CloudFront                      | Same Vue 3 app, behind Caddy on OpenShift |
| API client     | Two generated TypeScript clients            | One, generated from the running backend   |
| Infrastructure | Terraform                                   | OpenShift templates + GitHub Actions      |

## Layout

| Path                        | What it is                                                    |
| --------------------------- | ------------------------------------------------------------- |
| `backend/`                  | Spring Boot API (Java 21, Maven). See `backend/docs/`.         |
| `frontend/`                 | Vue 3 SPA, served by Caddy.                                    |
| `frontend/client-code-gen/` | The generated API client and its spec. See its README.         |
| `migrations/`               | DBA-owned role and grant scripts. The migrations themselves ship in the backend jar. |
| `common/`                   | Shared OpenShift template (secret, ConfigMap, NetworkPolicy).  |
| `monitoring/`               | Sysdig alert definitions.                                      |

## The database is not deployed by this repository

FAM runs against an **on-prem PostgreSQL instance**. There is no StatefulSet, no
Patroni, no Crunchy operator, and no database in any OpenShift template. The
deploy connects to the on-prem host using credentials from a secret.

Role creation and privilege grants are the DBA team's responsibility and live in
`migrations/dba/`, not in the migrations themselves. See `migrations/README.md`.

The database holds **two** tables, both audit: `fam_privilege_change_audit` and
its code table `fam_privilege_change_type` — who granted what to whom, which CSS
does not record. Applications, roles and role assignments are CSS's.

There is no user table. FAM used to keep `fam_user`, provisioned at login, and
rejected any token without a row — which made "has signed in before" a
precondition for every API call. It decided nothing: authorisation comes from the
roles on the token, and every field it held is on the token too. The audit
records the people it names as **snapshots** (`change_performer_user_details`,
`change_target_user_details`) rather than references, so the trail stays readable
without it — and stays truthful after someone is renamed.

Three conventions hold across every table:

- **The same four audit columns everywhere** - `create_user`, `create_date`,
  `update_user`, `update_date` - including the code tables, so "who put this row
  here" has one answer regardless of which table is being read. All four are
  `NOT NULL`: on a create, `update_user` is filled with `create_user` rather than
  left empty, so "last touched by" always has an answer instead of encoding
  "never updated" as an absence. `AuditedEntity`'s `@PrePersist` does the filling.
- **Timestamps are `timestamp(6)`, without a time zone.** The containers run
  `TZ=America/Vancouver`, so stored values are BC local time and line up with the
  application's logs. The cost is that the two 01:30s on the November DST
  fall-back are indistinguishable, leaving one hour of audit history a year
  ambiguous to the minute.
- **Audit users are `<TYPE>\<GUID>`**, e.g.
  `IDIR\A1B2C3D4E5F60718293A4B5C6D7E8F90`. A bare GUID does not say which
  directory it came from, and an audit column has no `user_type_code` beside it
  to disambiguate. The prefix is the identity provider's name — `IDIR` or
  `BCEID_BUS` — the same vocabulary `target_user_type_code` uses. Rows FAM writes
  itself are stamped `system`. See `AuditUser`.
- **Surrogate keys are UUIDs, not sequences.** Rows are created from several pods
  at once and get cross-referenced against systems with their own numbering,
  where a sequence value invites being read as an ordering or a count.

The user type codes are `IDIR` and `BCEID_BUS`. BC Services Card is deliberately
absent from both the code table and the `UserType` enum: FAM does not admit BCSC
logins, so those codes could only ever describe rows that cannot be created.

**Migrations run inside the application.** `backend/src/main/resources/db/migration`
ships in the jar and Flyway applies it at start-up, before Hibernate validates
the schema, so the schema a build expects and the code that expects it are one
artefact. There is no migrations image and no init container. The consequence is
that the runtime database role needs `CREATE` on `app_fam` - it creates tables,
not just rows. See `migrations/README.md`.

## Running locally

```sh
docker compose up
```

That starts PostgreSQL, then the backend on `:8080` and the frontend on `:3000`.
The backend migrates the database itself on start-up.

The frontend is the one you open. It proxies `/api` to the backend and strips
the prefix, mirroring the Caddy config the deployed frontend runs behind, so the
API is same-origin in development too.

To sign in you need a real BC Gov SSO client; set `KEYCLOAK_ISSUER_URI` and
`FAM_OIDC_CLIENT_ID` in your environment first. Without them the stack still
starts, but every authenticated call is rejected.

Running the pieces individually:

```sh
cd backend  && ./mvnw spring-boot:run     # needs a database on :5432
cd frontend && npm run install-frontend && npm run dev
```

The frontend dev server proxies `/api` to the backend and strips the prefix,
matching the Caddy configuration it runs behind in production. The API is
therefore same-origin in both, so CORS is not involved.

## Tests

```sh
cd backend  && ./mvnw verify   # unit tests + SchemaValidationIT (needs Docker)
cd frontend && npm run type-check && npm run test:cov
```

`SchemaValidationIT` is the one check that the JPA entities match the real
schema: Testcontainers starts a throwaway PostgreSQL, the application's own
Flyway configuration applies `db/migration`, and Hibernate validates every
mapping against the result - the same path a deployment takes. H2 cannot stand
in for it, because the schema uses `JSONB`, `gen_random_uuid()` and expression
indexes. It needs a Docker daemon, so it fails on machines without one - CI
always runs it, and it is the only thing that catches an entity that has drifted
from the DDL.

## Changing the API

The OpenAPI document is generated from the **running backend**, and the
frontend's TypeScript client is generated from that document. After changing a
controller or DTO:

```sh
cd backend && ./mvnw test -Dtest=OpenApiSpecGeneratorTest
cd ../frontend/client-code-gen && npm ci && npm run gen-api-client
cd .. && npm run type-check
```

CI fails if the committed `fam-openapi.json` is stale, so this cannot be
forgotten. `frontend/client-code-gen/README.md` documents the contract details
that are easy to break - property naming, enum schema names, and parameter order.

## Deployment

GitHub Actions deploys to OpenShift per pull request, then to test and prod on
merge. Set these on the repository, or per **environment** (`test`, `prod`) where
they differ. The CSS credentials, `css_own_integration_id` and `smtp_from`
usually do; so do `keycloak_issuer_uri`, `user_lookup_issuer_uri` and
`keycloak_client_id` once prod points at a different realm from test.

### Secrets

| Secret | Required | What it is |
| ------ | -------- | ---------- |
| `oc_namespace` | yes | OpenShift namespace |
| `oc_token` | yes | OpenShift token |
| `db_host` | yes | On-prem PostgreSQL host |
| `db_port` | if not 5432 | Port. Defaults to `5432`. |
| `db_name` | yes | Database name |
| `db_user` | yes | Runtime role. **Needs `CREATE` on `app_fam`** - the application runs Flyway at start-up. |
| `db_password` | yes | Password for that role |
| `css_api_client_id` | in practice | CSS API account (CSS app -> My Teams -> CSS API Account) |
| `css_api_client_secret` | in practice | As above |
| `user_lookup_base_url` | in practice | Base URL of nr-user-lookup-api |
| `user_lookup_provider` | optional | `api` (default): people searches go to nr-user-lookup-api. `css`: they go to the CSS API's own user search through the CSS API account instead, so none of the `user_lookup_*` values are needed. CSS finds IDIR people by name, email or GUID (not by login), Business BCeID by GUID only, and only once they have signed in to a CSS application in that environment. |
| `fc_api_token_test` | in practice | Forest Client API key for the API's TEST instance, used by every non-PROD application. Needed in all three environments. See below. |
| `fc_api_token_prod` | prod only | Forest Client API key for the API's PROD instance. A separate key, and deliberately unset outside prod. See below. |
| `keycloak_sa_client_id` | optional | Keycloak admin client that provisions FAM's user-lookup service account. See below. |
| `keycloak_sa_client_secret` | optional | Secret for that admin client |

Not used by the deploy: `sonar_token_backend` and `sonar_token_frontend` (code
analysis, one project each) and `SYSDIG_API_TOKEN` (`analysis.yml` and
`scheduled.yml`). Absent, those jobs fail but no deployment is affected.
`GITHUB_TOKEN` is supplied automatically.

"In practice" means the deploy succeeds without them and the application starts,
but every CSS-backed screen then fails at call time - the credentials are checked
when they are used, not at boot.

### Variables

| Variable | Required | What it is |
| -------- | -------- | ---------- |
| `oc_server` | yes | OpenShift API URL. No default. |
| `keycloak_issuer_uri` | yes | Realm users **sign in** to, e.g. `https://test.loginproxy.gov.bc.ca/auth/realms/standard`. |
| `user_lookup_issuer_uri` | with the SA secrets | Realm the **user-lookup service account** lives in - a different one, e.g. `https://test.loginproxy.gov.bc.ca/auth/realms/forests`. See below. |
| `keycloak_client_id` | yes | FAM's browser client. Feeds both the SPA's login redirect and the backend's `FAM_OIDC_CLIENT_ID`, which is the `azp` it requires on every token - so the two cannot be set apart. A client id is public, not a secret. |
| `css_own_integration_id` | in practice | FAM's own CSS integration id, e.g. `12345`. The administrator tabs and appointing administrators **fail** without it. See below. |
| `smtp_host` | for email | Mail relay. **Blank disables sending entirely.** |
| `smtp_from` | for email | Envelope sender. Required, or nothing is sent. |
| `fc_api_base_url_test` | in practice | Forest Client API TEST instance, e.g. `https://nr-forest-client-api-test.api.gov.bc.ca`. **Blank disables forest-client search** for every non-PROD application. See below. |
| `fc_api_base_url_prod` | prod only | Forest Client API PROD instance. Blank everywhere but prod, which is intended. See below. |
| `css_api_url` | optional | Defaults to the production CSS API. |
| `css_token_url` | optional | Defaults to the production token endpoint. |

### Not settings, but worth knowing

These are template parameters with defaults, so they need no repository setting -
but they are what the deploy applies, and both changed recently:

| Parameter | Default | Notes |
| --------- | ------- | ----- |
| `TZ` | `America/Vancouver` | Container timezone, which is what log timestamps render against. An OpenShift container is UTC otherwise, putting every line 7-8 hours off the BC day. Do not add `-Duser.timezone` to the image: it would override this. |
| `MIN_REPLICAS` | `2`, or **`3` in prod** | The autoscaler floor, set per environment by `reusable-deploy.yml`. `MAX_REPLICAS` stays 5, so prod sits at 3 and can scale. |

### Two worth understanding

**`css_own_integration_id`.** Administering FAM means deciding who administers
every other application, so FAM's own integration is reserved to `FAM_ADMIN`.
Nothing in a CSS response marks which integration is FAM's, so it has to be told.

It is also where every administrative role lives - `APP_ADMIN_<id>_<ENV>` and the
delegations `DELEGATED_ADMIN_<id>_<ENV>__<ROLE>` are held on FAM's own
integration, not on the application being administered. They sit in FAM's own
deployment environment (`FAM_DEPLOYMENT_ENVIRONMENT`), not the application's, so
FAM PROD keeps `APP_ADMIN_<id>_DEV` on its `prod` client, where its users' tokens
come from. See
[authentication.md](backend/docs/authentication.md#which-of-fams-environments-holds-them).
Without this id there is nowhere to read or write them, and three things break:

- the **Delegated admins** and **Application admins** tabs fail with a message
  naming this variable;
- **appointing** either kind of administrator fails the same way;
- **deleting a role** cannot withdraw the delegations that name it. That one
  degrades rather than fails: the role is removed, a warning is logged, and the
  orphaned delegations would let their holders recreate the role by granting it.

The protection itself also cannot be applied: FAM's own integration becomes
administrable by anyone holding a role for it rather than by `FAM_ADMIN` alone.
Start-up logs a warning and the deploy still succeeds, which is what lets a PR
preview run without it - but a real environment wants it set.

**Email needs both `smtp_host` and `smtp_from`.** Host blank and the application
logs "No SMTP host configured" once at start-up, and grants complete without
notifying anyone. Host set but `from` blank and nothing sends either, because a
relay rejects a message with no envelope sender - so the application refuses up
front rather than failing per message. No credentials are configured: the BC Gov
relay accepts unauthenticated mail from inside the network, so the mail client
keeps its defaults of port 25, no auth and no STARTTLS.

### The user-lookup service account

FAM resolves IDIR and Business BCeID identities from **nr-user-lookup-api**,
which replaced the IDIM proxy. Its client id and secret are **not** configured by
hand: `.github/scripts/ensure-keycloak-service-account.sh` runs on deploy and
idempotently creates a confidential client (`nr-fam-backend`), assigns the three
scopes that API enforces, reads the secret back, and emits both as masked step
outputs. The deploy then stores them in a Secret.

That needs an admin service-account client with the realm-management
`manage-clients` role - `keycloak_sa_client_id` / `keycloak_sa_client_secret`.
Without them the step is skipped, which is what lets a PR preview deploy; the
backend then fails identity lookups loudly rather than returning empty results.

**It is not the login realm.** Users sign in through `standard`, but
nr-user-lookup-api validates its callers against its own realm - `forests` - and
that is the only realm where the user-lookup client scopes are defined. So the
service account is created there, against `user_lookup_issuer_uri`, and the
backend requests its token from that realm too. The admin client has to live in
the same realm, because `manage-clients` is realm-scoped. Point this at the
login realm and the client lands where the scopes do not exist; if it got a
token at all, the API would reject it as issued by the wrong issuer.

The scopes (`user-lookup:idir:search`, `user-lookup:idir:read`,
`user-lookup:business-bceid:read`) are owned by nr-user-lookup-api and must
already exist in the realm. The script errors rather than creating them.

### The Forest Client API

Forest-client-scoped roles need client numbers resolved to names, which comes
from the Forest Client API.

The API publishes **two instances**, TEST and PROD, holding different data and
taking **different keys** - a key is issued per instance by the BC API Service
Portal and they are not interchangeable. Hence two secrets, `fc_api_token_test`
and `fc_api_token_prod`.

Which instance a request uses is decided per **application** environment, not per
deployment, by `ApiInstanceEnvResolver`: **both** the deployment and the target
application must be prod before PROD is chosen. So FAM PROD administering a PROD
application reads live client data, while FAM PROD administering that same
application's DEV and TEST roles - and all of FAM DEV and TEST - reads the TEST
instance. Anything unrecognised resolves to TEST, because guessing wrong towards
PROD means real data.

That split is worth keeping rather than collapsing to one endpoint. Test client
records exist only in the TEST instance, so a lower environment pointed at PROD
would fail to resolve exactly the clients it is meant to be working with, and
would be reading production data to do it.

Accordingly `fc_api_token_prod` belongs on the **prod environment only**. Leaving
it unset in dev and test is the intended configuration: it is the second of two
things standing between a lower environment and live client data, the first being
the resolver. A blank key fails late - the instance still starts and the first
search returns 401 - so it is a backstop, not the mechanism.

Both base URLs are **variables**, not template defaults: an endpoint that moves
should be a settings change rather than a code change. They are public hostnames
published in the portal directory, so they are variables rather than secrets -
only the keys are secret, and they travel as `X-API-KEY`.

**A non-prod FAM cannot act on a production application**, and that is settled by
credentials rather than by code. Each deployment has its own CSS API account and
sees only its own integrations: the lower environments run against a full
parallel set, for FAM itself and for the applications they administer, and only
the PROD deployment holds the account that can see the production ones. A `prod`
environment named by FAM DEV is the `prod` environment of a lower-environment
integration, which is not production anything — so a lower environment can never
need the PROD Forest Client key.

A blank base URL disables that instance outright. The application logs
`Forest Client API <ENV> instance not configured` at start-up and every
forest-client search against it fails, so an unset `fc_api_base_url_test` breaks
scoped-role screens in every environment. Blank `fc_api_base_url_prod` is the
correct state everywhere except the PROD deployment.

The two failure modes differ, which is worth knowing when reading logs. A blank
**base URL** disables an instance at start-up and logs `Forest Client API PROD
instance not configured`; a blank **key** does not, so the instance initialises
and the first search fails with a 401. A missing key is therefore silent until
someone uses the screen.

Every secret the deploy needs, it creates: the database, CSS, user-lookup and
Forest Client secrets all come from `common/openshift.init.yml`, and SMTP is two
plain template parameters. Nothing is created out of band any more - the
`<name>-<zone>-integrations` secret held only the user-info refresh key, and that
endpoint is gone.

Only the frontend has a Route. The backend is reached through it at `/api`.


## Before this can run

- The Keycloak realm must map the claims listed in
  `backend/docs/authentication.md` into the **access** token, not just the ID
  token.
- **The runtime database role needs `CREATE` on `app_fam`.** The application
  runs Flyway on start-up, so it creates tables rather than only reading and
  writing rows. `migrations/dba/02_app_user_grants.sql` grants it; a DBA runs
  that before the first deploy.
- **FAM's own CSS integration needs its admin roles.** Admin rights are read from
  the caller's token, so `FAM_ADMIN` and `<APPLICATION_NAME>_ADMIN` have to exist
  in CSS and be assigned. Without them `authorize()` rejects every request. See
  `backend/docs/authentication.md`.

## Not carried over

- **Browser tests.** Upstream had none, and the quickstart's demo suites targeted
  other stacks. `vue-tsc` and the unit tests are the current safety net.
- **BC Services Card decryption** (`kms_lookup`, `bcsc_*`, the BCSC proxy route).
  It decrypted payloads with an AWS KMS key; both the IdP and the key management
  are gone.
