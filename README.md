# jCustomer – Salesforce Connector

A Jahia OSGi module that syncs **Salesforce** Contact, Account and Lead data into **Apache Unomi (jCustomer)** profile properties. It ships a jContent back-office admin panel to configure the Salesforce connection, map Salesforce fields to jCustomer profile properties, and drive synchronization (scheduled batch, on-login, or manual).

Contact and Lead are top-level objects matched to jCustomer profiles by email (Contact also by a stored `SFDC_ContactId`); Account fields are synced as a relationship of the Contact. Converted Leads are skipped (they already exist as Contacts).

- **Module:** `org.jahia.community.modules:jcustomer-sfdc-connector`
- **Version:** `1.1.0-SNAPSHOT`
- **Platform:** Jahia 8.2 (parent POM `org.jahia.modules:jahia-modules:8.2.0.0`), Java 17
- **Front-end:** React 18 UI extension (Webpack / Module Federation, `@jahia/ui-extender`, `@jahia/moonstone`)

---

## How it works

```
Salesforce  ──(JWT Bearer OAuth2, REST)──▶  Connector  ──(/cxs/eventcollector)──▶  jCustomer / Unomi profiles
                                              ▲
                                     Admin UI (jContent) + JCR config
```

1. **Authenticate** to Salesforce with the JWT Bearer OAuth 2.0 flow (RS256, no client secret). The RSA private key is stored in JCR and never leaves the server.
2. **Query** Salesforce Contacts (and related Account fields) via SOQL, following pagination.
3. **Match** each Contact to a jCustomer profile — first by `SFDC_ContactId`, then by email (the ContactId is then stored on the profile for fast future lookups).
4. **Update** the matched profile's properties via the Unomi event collector, according to the configured field mapping.

Sync is triggered three ways:

| Trigger | Component | When |
|---|---|---|
| Scheduled batch | `SfdcSyncJob` (Quartz) | cron (default daily 05:00), delta by `LastModifiedDate` |
| On login | `SfdcLoginEventHandler` | every user login (single-contact sync) |
| Manual | `TriggerSyncAction` | admin clicks "Sync now" |

---

## Requirements

- A local or remote Jahia 8.2 instance with **jExperience** and a reachable **jCustomer / Unomi** service.
- A **Salesforce Connected App** configured for the JWT Bearer flow:
  - "Use digital signatures" enabled, with the public certificate uploaded.
  - OAuth scopes including `api` (and `refresh_token` where relevant).
  - The running user pre-authorized (or admin-approved).
- Build toolchain: Maven, **Java 17** (`JAVA_HOME` must point at a JDK 17), Node `v20.18.0` + Yarn `1.22.22` (installed automatically by `frontend-maven-plugin`).

---

## Build

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn clean install
```

This compiles the Java, runs the Webpack production build, and produces `target/jcustomer-sfdc-connector-1.1.0-SNAPSHOT.jar`.

## Deploy

Drop the bundle into a running instance's hot-deploy folder. For a Dockerized Jahia:

```bash
docker cp target/jcustomer-sfdc-connector-1.1.0-SNAPSHOT.jar <jahia-container>:/var/jahia/modules/
```

Remove any older versioned jar from `/var/jahia/modules/` first so only one version of the bundle is present. Alternatively use the Provisioning API or the jContent module manager.

---

## Configuration

### Runtime behaviour — OSGi config

PID `org.jahia.community.jcustomer.sfdc` (file: `src/main/resources/META-INF/configurations/org.jahia.community.jcustomer.sfdc.cfg`):

| Key | Default | Purpose |
|---|---|---|
| `scheduler.cron` | `0 0 5 * * ?` | Quartz cron for the batch sync |
| `sfdc.api.version` | `v66.0` | Salesforce REST API version |
| `sfdc.batch.size` | `200` | Max records per SOQL page |
| `sync.delta.buffer.minutes` | `5` | Clock-skew buffer for delta sync |
| `sync.on.login` | `true` | Sync a user immediately on login |
| `jcustomer.site.key` | `mysoprahr` | jExperience site key + **the site whose administrators may manage this connector** |
| `jcustomer.url` | *(unset)* | jCustomer base URL for the property-listing REST call |
| `jcustomer.username` | *(unset)* | jCustomer Basic-auth user |
| `jcustomer.password` | *(unset)* | jCustomer Basic-auth password |

> **Credentials are not shipped in source.** When `jcustomer.url`/`username`/`password` are unset, the mapping UI falls back to a built-in property list (the live lookup is disabled — fail-closed). Set them per-environment via the OSGi config, not in the committed file. In a multi-container setup use the jCustomer container hostname (e.g. `http://jcustomer_3:8181`), **not** `localhost`.

### Connection & mapping — stored in JCR

Configured through the admin UI and persisted under `/settings/jcustomer-sfdc-connector`:

- `connection/` — `instanceUrl`, `clientId`, `sfUsername`, `privateKey` (PEM), `apiVersion`, `enabled`
- `mapping/Contact`, `mapping/Account` — ordered `sfField → jcProperty` entries
- `triggers/` — `loginEnabled`, `schedulerEnabled`, `schedulerCron`
- `syncState/` — last run timestamp, status, counts

---

## Admin UI

jContent → **Administration → SF Connector** (route `/sfdc-connector`, under `administration-sites`). Tabs:

- **Connection** — Salesforce Connected App credentials; the private key must be PEM (PKCS#8 or PKCS#1). "Test connection" validates against the Salesforce `limits` endpoint.
- **Field Mapping** — map Salesforce Contact/Account fields to jCustomer profile properties.
- **Synchronisation** — trigger a full sync and view the last sync state.
- **Triggers** — enable/disable login sync and the scheduler; edit the cron (with a cron builder).

---

## Security model

All admin operations are Jahia Actions dispatched at `POST /cms/render/default/{lang}/sites/systemsite.{action}.do`. Every action extends `AbstractSfdcAdminAction`, which enforces two controls before any action logic runs (returning HTTP 403 otherwise):

1. **Authorization** — the caller must be an administrator: a **server administrator** (`root` / the global `administrators` group) **or** a **site administrator** of the connector's configured site (`jcustomer.site.key`).
2. **Same-origin (CSRF) defense** — the request's `Origin`/`Referer` host must match the Jahia host, blocking cross-origin confused-deputy requests even though the CSRF-guard whitelist exempts these endpoints from token checks.

Additional hardening:

- **SSRF allow-list** — the Salesforce `instanceUrl` is validated on save (`https` + a `*.salesforce.com` / `*.force.com` host) before it is used for any outbound call carrying the org's signed JWT.
- **No secrets in source** — jCustomer credentials are supplied per-environment (see above); the private key is stored in JCR and is never returned to the browser (the UI only sees whether a key is present).
- **SOQL escaping** — user-derived values (login email) are escaped before interpolation into SOQL.

### Actions

| Action | Type | Purpose |
|---|---|---|
| `getConnection` / `saveConnection` | read / write | Read (masked) / store Salesforce connection settings |
| `testConnection` | read | Validate the connection against Salesforce |
| `getSfdcFields` | read | List Salesforce Contact/Account fields (Describe API) |
| `getJcProperties` | read | List jCustomer profile properties (live + fallback) |
| `getMapping` / `saveMapping` | read / write | Read / store field mappings |
| `getTriggers` / `saveTriggers` | read / write | Read / store trigger config (reschedules Quartz) |
| `triggerSync` | write | Start an immediate batch sync |
| `getSyncState` | read | Last sync timestamp / status / counts |

---

## Project layout

```
src/
├── main/java/org/jahia/community/modules/jcustomer/sfdc/
│   ├── actions/            # Jahia Actions (AbstractSfdcAdminAction base + 11 actions)
│   ├── auth/               # SfdcAuthService — JWT Bearer OAuth2 (RS256)
│   ├── client/             # SfdcApiClient — SOQL query + Describe API
│   ├── config/             # ConnectorConfigService — JCR-backed config
│   ├── jcustomer/          # JCustomerProfileService — profile find/update via Unomi
│   ├── sync/               # SfdcSyncService + SfdcSyncJob (Quartz)
│   └── events/             # SfdcLoginEventHandler (login-triggered sync)
├── main/resources/
│   ├── META-INF/configurations/   # OSGi .cfg + CSRF-guard whitelist
│   └── javascript/                # compiled React UI (Module Federation) + locales
└── javascript/SfdcAdmin/          # React 18 admin UI sources
```

---

## Troubleshooting

- **Admin action returns 403** — the caller is not a server admin or a site admin of `jcustomer.site.key`, or the request is cross-origin. Log in to jContent on the same host.
- **Admin action returns 404** — the caller is not authenticated (falls back to `guest`, which cannot resolve the host node). Note: repeatedly authenticating with fresh sessions can exhaust the authenticated-visitor license cap and cause `guest` fallback — reuse a session.
- **Mapping UI shows only the built-in property list** — `jcustomer.url`/`username`/`password` are unset or unreachable (check the log for `Could not load jCustomer properties live`). Use the jCustomer container hostname, not `localhost`.
- **`saveConnection` rejects the instance URL** — it must be `https` and a Salesforce domain (`*.salesforce.com` / `*.force.com`).
- **No profiles updated** — a jCustomer profile must already exist for the contact's email (users are matched, not created); check the Contact has an email and the mapping is non-empty.

---

## License

Provided as a Jahia community module. See the repository for license details.
