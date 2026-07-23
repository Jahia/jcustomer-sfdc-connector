# jCustomer ⇄ Salesforce Connector

A Jahia OSGi module that provides **bidirectional** synchronization between **Salesforce** and
**Apache Unomi (jCustomer)** profiles, with a jContent back-office admin panel to configure the
connection, map fields, and drive the sync in both directions.

- **Inbound (SFDC → jCustomer):** Salesforce **Contact, Account and Lead** data → jCustomer profile
  properties (scheduled batch, on-login, or manual).
- **Outbound (jCustomer → SFDC):** jCustomer profiles → a Salesforce **Lead _or_ Contact** (a single
  chosen target object), poll-based (scheduled or manual), matched by a stored SFDC id or email,
  created when missing.

<!-- -->

- **Module:** `org.jahia.community.modules:jcustomer-sfdc-connector`
- **Version:** `2.1.0`
- **Platform:** Jahia 8.2 (parent POM `org.jahia.modules:jahia-modules:8.2.0.0`), Java 17
- **Front-end:** React 18 UI extension (Webpack / Module Federation, `@jahia/ui-extender`, `@jahia/moonstone`)

---

## How it works

```
                     ┌──────────── inbound (SFDC → jCustomer) ────────────┐
Salesforce  ◀────────┤  JWT Bearer OAuth2 + REST (SOQL / Describe)        │
   Contact           │  match profile by SFDC id / email → update props   ├───▶  jCustomer / Unomi
   Account           └────────────────────────────────────────────────────┘        profiles
   Lead              ┌──────────── outbound (jCustomer → SFDC) ───────────┐
            ◀────────┤  poll /cxs/profiles/search + /cxs/events/search     │◀──────
                     │  push profile → Lead/Contact (update or create)     │
                     └────────────────────────────────────────────────────┘
                                          ▲
                                Admin UI (jContent) + JCR config; shared connection
```

Authentication is the Salesforce **JWT Bearer OAuth 2.0** flow (RS256, no client secret); the RSA
private key is stored in JCR and never leaves the server. The same connection is shared by both
directions.

### Inbound (SFDC → jCustomer)

1. Query Salesforce **Contact** (+ related **Account** fields) and **Lead** via SOQL, with pagination.
2. Match each record to a jCustomer profile — by a stored SFDC id (`SFDC_ContactId`), else by email.
3. Update the profile's properties via the Unomi event collector, per the field mapping.

| Trigger | Component | When |
|---|---|---|
| Scheduled batch | `SfdcSyncJob` (Quartz) | cron (default daily 05:00), delta by `LastModifiedDate` |
| On login | `SfdcLoginEventHandler` | every user login (single-record sync) |
| Manual | `TriggerSyncAction` | admin clicks "Sync now" |

### Outbound (jCustomer → SFDC)

Poll-based, following the approach of Jahia's official [Workato Unomi connector](https://github.com/Jahia/workato-unomi-connector) — no custom Unomi plugin, no webhook, nothing deployed into the jCustomer container:

1. Poll `/cxs/profiles/search` for profiles changed since the last run (`systemProperties.lastUpdated`), and `/cxs/events/search` for recent **form** events.
2. For each changed profile (with an email), push to the configured **target object** — **Lead** or **Contact** (chosen in the Triggers tab).
3. Resolve the target record by the stored id (`SFDC_LeadId` / `SFDC_ContactId`) then email; **update** if found, **create** if not. The new id is written back to the profile for idempotent future updates.

Create requirements are per object (Salesforce rules): **Lead** needs `LastName` + `Company`, **Contact** needs `LastName`. Missing → skipped with a warning (updates still work). Converted Leads are excluded from matching.

| Trigger | Component | When |
|---|---|---|
| Scheduled poll | `SfdcOutboundReconcileJob` (Quartz) | cron set via a friendly builder (Every N minutes / hour / day / week / month) |
| Manual | `TriggerOutboundReconcileAction` | admin clicks "Sync now" |

---

## Requirements

- A Jahia 8.2 instance with **jExperience** and a reachable **jCustomer / Unomi** service.
- A **Salesforce Connected App** for the JWT Bearer flow: "Use digital signatures" enabled with the
  public certificate uploaded; OAuth scopes including `api`; the running user pre-authorized.
- Build toolchain: Maven, **Java 17** (`JAVA_HOME` at a JDK 17), Node `v20.18.0` + Yarn `1.22.22`
  (installed automatically by `frontend-maven-plugin`).

---

## Build & deploy

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn clean install
```

Produces `target/jcustomer-sfdc-connector-2.1.0.jar`. Deploy into a running instance's hot-deploy
folder (remove any older versioned jar first so only one version is present):

```bash
docker cp target/jcustomer-sfdc-connector-2.1.0.jar <jahia-container>:/var/jahia/modules/
```

Alternatively use the Provisioning API or the jContent module manager.

---

## Configuration

### Runtime behaviour — OSGi config

PID `org.jahia.community.jcustomer.sfdc` (`src/main/resources/META-INF/configurations/org.jahia.community.jcustomer.sfdc.cfg`):

| Key | Default | Purpose |
|---|---|---|
| `scheduler.cron` | `0 0 5 * * ?` | Quartz cron for the inbound batch sync |
| `sfdc.api.version` | `v66.0` | Salesforce REST API version |
| `sfdc.batch.size` | `200` | Max records per SOQL page |
| `sync.delta.buffer.minutes` | `5` | Clock-skew buffer for delta / poll |
| `sync.on.login` | `true` | Sync a user immediately on login |
| `jcustomer.site.key` | `mysoprahr` | jExperience site key + **the site whose administrators may manage this connector** |
| `jcustomer.url` / `jcustomer.username` / `jcustomer.password` | *(unset)* | jCustomer REST access for the property-listing call — supplied per-environment, never committed |

> **Credentials are not shipped in source.** When jCustomer URL/credentials are unset, the mapping UI falls back to a built-in property list (fail-closed). Use the jCustomer container hostname (e.g. `http://jcustomer_3:8181`), **not** `localhost`.

### Connection & mapping — stored in JCR (`/settings/jcustomer-sfdc-connector`)

- `connection/` — `instanceUrl`, `clientId`, `sfUsername`, `privateKey` (PEM), `apiVersion`, `enabled` (shared by both directions)
- `mapping/{Contact,Account,Lead}` — inbound `sfField → jcProperty` entries
- `triggers/` — inbound `loginEnabled`, `schedulerEnabled`, `schedulerCron`
- `syncState/` — last inbound run
- `outbound/mapping/{Lead,Contact}` — outbound `jcProperty → sfField` entries
- `outbound/triggers/` — `outboundObject` (Lead | Contact), `onProfileUpdate`, `onFormSubmission`, `reconcileEnabled`, `reconcileCron`
- `outbound/syncState/` — last outbound run + poll watermark

---

## Admin UI

jContent → **Administration → SF Connector** (route `/sfdc-connector`). Three top-level tabs:

- **Connection** *(shared)* — Salesforce Connected App credentials (PEM private key); "Test connection".
- **SFDC → jCustomer** — Field Mapping (Contact/Account/Lead) · Synchronisation (state + "Sync now") · inbound triggers.
- **jCustomer → SFDC** — Field Mapping (Lead/Contact) · Triggers: **target object** (Lead or Contact) + what to sync (profile updates / form submissions) + Schedule (friendly cron builder, down to every minute) + "Sync now" · last outbound run.

---

## Security model

Admin operations are Jahia Actions at `POST /cms/render/default/{lang}/sites/systemsite.{action}.do`. Every action extends `AbstractSfdcAdminAction`, which enforces two controls before any logic (HTTP 403 otherwise):

1. **Authorization** — the caller must be a **server administrator** (`root` / global `administrators`) **or** a **site administrator** of the connector's configured site.
2. **Same-origin (CSRF) defense** — the request `Origin`/`Referer` host must match the Jahia host.

Additional hardening:
- **SSRF allow-list** — the Salesforce `instanceUrl` is validated on save (`https` + `*.salesforce.com` / `*.force.com`) before any outbound call carrying the org's signed JWT.
- **No secrets in source** — jCustomer credentials supplied per-environment; the private key is stored in JCR and never returned to the browser.
- **SOQL escaping** on user-derived values.

---

## Project layout

```
src/
├── main/java/org/jahia/community/modules/jcustomer/sfdc/
│   ├── actions/     # Jahia Actions (AbstractSfdcAdminAction base + inbound & outbound actions)
│   ├── auth/        # SfdcAuthService — JWT Bearer OAuth2 (RS256)
│   ├── client/      # SfdcApiClient — SOQL query, Describe, create/update sObject
│   ├── config/      # ConnectorConfigService — JCR-backed config (inbound + outbound)
│   ├── jcustomer/   # JCustomerProfileService — profile find/get/update + change/event polls
│   ├── sync/        # SfdcSyncService + SfdcSyncJob (inbound); SfdcOutboundSyncService + SfdcOutboundReconcileJob (outbound)
│   └── events/      # SfdcLoginEventHandler (login-triggered inbound sync)
├── main/resources/
│   ├── META-INF/configurations/   # OSGi .cfg + CSRF-guard whitelist
│   └── javascript/                # compiled React UI + locales (en/fr)
└── javascript/SfdcAdmin/          # React 18 admin UI sources
```

---

## Troubleshooting

- **Admin action returns 403** — caller is not a server/site admin, or the request is cross-origin.
- **Admin action returns 404** — caller is not authenticated (guest cannot resolve the host node). Repeatedly authenticating with fresh sessions can exhaust the authenticated-visitor license cap → `guest` fallback; reuse a session.
- **Mapping UI shows only the built-in property list** — jCustomer URL/credentials unset or unreachable (log: `Could not load jCustomer properties live`). Use the container hostname, not `localhost`.
- **`saveConnection` rejects the instance URL** — must be `https` and a Salesforce domain.
- **Inbound: no profiles updated** — a jCustomer profile must exist for the record's email (matched, not created).
- **Outbound: a profile edit isn't reaching Salesforce** — the poll runs on its schedule (default 15 min); use "Sync now" for an immediate run. Console/REST profile edits are caught by the profile poll (via `systemProperties.lastUpdated`).
- **Outbound: "Cannot create Lead/Contact"** — Salesforce requires `LastName` (+ `Company` for Lead) to create; map them, or existing records still update.

---

## License

Provided as a Jahia community module. See the repository for license details.
