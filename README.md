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
- **Repository:** https://github.com/Jahia/jcustomer-sfdc-connector
- **Changelog:** [CHANGELOG.md](CHANGELOG.md)
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

Produces `target/jcustomer-sfdc-connector-2.3.4.jar`. Deploy into a running instance's hot-deploy
folder (remove any older versioned jar first so only one version is present):

```bash
docker cp target/jcustomer-sfdc-connector-2.3.4.jar <jahia-container>:/var/jahia/modules/
```

Alternatively use the Provisioning API or the jContent module manager.

---

## Configuration

### Runtime behaviour — OSGi config

PID `org.jahia.community.jcustomer.sfdc` (`src/main/resources/META-INF/configurations/org.jahia.community.jcustomer.sfdc.cfg`):

| Key | Default | Purpose |
|---|---|---|
| `scheduler.cron` | `0 0 5 * * ?` | Quartz cron for the inbound batch sync |
| `sfdc.api.version` | `v66.0` | Salesforce REST API version used when the Connection tab leaves *API version* empty (the Connection value wins when set). Not repeated anywhere in code. |
| `sfdc.batch.size` | `200` | Max records per SOQL page |
| `sync.delta.buffer.minutes` | `5` | Clock-skew buffer for delta / poll |
| `sync.on.login` | `true` | Sync a user immediately on login |
| `jcustomer.site.key` | *(empty: auto-detect)* | Site the connector works for: its jExperience settings select the jCustomer instance and the key used to update profiles, and its **site administrators may manage this connector**. Empty = the single site with the `jexperience` module installed; set it when several sites have jExperience. A value that is not a site on this server is ignored with a warning. Manual runs from the admin UI use the site the UI is open on. |
| `jcustomer.url` / `jcustomer.username` / `jcustomer.password` | *(unset)* | jCustomer REST access for the property-listing call — supplied per-environment, never committed |

> **Credentials are not shipped in source.** Set `jcustomer.url` / `jcustomer.username` / `jcustomer.password` so the mapping UI lists the **connected jCustomer's own** profile properties (fetched live from `/cxs/profiles/properties/targets/profiles`). Use the jCustomer container hostname (e.g. `http://jcustomer:8181`), **not** `localhost`. When unset or unreachable, the UI falls back to a small set of **generic** Unomi well-known properties plus the connector's `SFDC_ContactId/AccountId/LeadId` writeback ids (fail-closed), with no customer-specific names. The shipped `.cfg` carries a `# default configuration` marker, so the values you set survive a module redeploy instead of reverting to the defaults.

### Connection & mapping — stored in JCR (`/settings/jcustomer-sfdc-connector`)

The config is stored under **typed** node types (namespace `jcsfdcnt`, defined in `META-INF/definitions.cnd`), so it is browsable and self-describing in the JCR / Definitions browser. Property types are real JCR types (`boolean`, `long`, `date`, `string`) rather than untyped strings.

| Node | Type | Holds |
|---|---|---|
| `connection/` | `jcsfdcnt:connection` | `instanceUrl`, `clientId`, `sfUsername`, `privateKey` (unencrypted PKCS#8 PEM), `apiVersion` (empty = `sfdc.api.version`), `enabled` (shared by both directions) |
| `mapping/{Contact,Account,Lead}` | `jcsfdcnt:objectMapping` → `jcsfdcnt:fieldMapping` | inbound `sfField → jcProperty` entries |
| `triggers/` | `jcsfdcnt:triggers` | inbound `loginEnabled`, `schedulerEnabled`, `schedulerCron` |
| `syncState/` | `jcsfdcnt:syncState` | last inbound run |
| `outbound/` | `jcsfdcnt:outbound` | outbound container |
| `outbound/mapping/{Lead,Contact}` | `jcsfdcnt:mappingSet` → `jcsfdcnt:objectMapping` | outbound `jcProperty → sfField` entries |
| `outbound/triggers/` | `jcsfdcnt:outboundTriggers` | `outboundObject` (Lead \| Contact), `onProfileUpdate`, `onFormSubmission`, `reconcileEnabled`, `reconcileCron` |
| `outbound/syncState/` | `jcsfdcnt:syncState` | last outbound run + poll watermark |

**Migration from earlier versions:** releases before 2.2.0 stored this config as `nt:unstructured`. The one-time patch `migration/2100-migrate-config-to-typed.groovy` converts an existing `/settings/jcustomer-sfdc-connector` subtree to the typed node types. Deploy the 2.2.0+ jar first (so the CND is registered), then drop the patch into `<jahia>/patches/groovy/`; the Jahia Patcher runs it once and renames it `.installed`. It is idempotent and reads tolerantly of the legacy layout, so re-running or running on an already-typed config is a no-op.

---

## Admin UI

jContent → **Administration → SF Connector** (route `/sfdc-connector`). Three top-level tabs:

- **Connection** *(shared)*: Salesforce Connected App credentials; the private key is an unencrypted **PKCS#8** PEM (`-----BEGIN PRIVATE KEY-----`; convert PKCS#1 with `openssl pkcs8 -topk8 -nocrypt -in old.key -out key.pem`); "Test connection". All labels and hints come from the EN/FR locale files.
- **SFDC → jCustomer** — Field Mapping (Contact/Account/Lead) · Synchronisation (state + "Sync now") · inbound triggers.
- **jCustomer → SFDC** — Field Mapping (Lead/Contact) · Triggers: **target object** (Lead or Contact) + what to sync (profile updates / form submissions) + Schedule (friendly cron builder, down to every minute) + "Sync now" · last outbound run.

In the Field Mapping tabs, the jCustomer-property dropdown lists the connected jCustomer's live profile properties (see the config note above) and the Salesforce-field dropdown lists the object's fields; both are sorted **alphabetically**.

### Exporting a mapping

Each Field Mapping tab (both directions) has **Export CSV** and **Export JSON** buttons that download the currently-selected object's mapping. The export is generated in the browser — no server round-trip. Only complete rows (both the jCustomer property and the Salesforce field set) are written; the download filename encodes direction and object, e.g. `sfdc-inbound-Contact-mapping.csv`, `sfdc-outbound-Lead-mapping.json`.

- **CSV** — a `jcProperty,sfField` header plus one line per mapping (RFC-4180 quoting).
- **JSON** — a self-describing document: `{ "direction", "object", "exportedAt", "mappings": [ { "jcProperty", "sfField" } ] }`.

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
│   ├── META-INF/definitions.cnd   # JCR node types for the typed config (jcsfdcnt:*)
│   ├── META-INF/configurations/   # OSGi .cfg + CSRF-guard whitelist
│   └── javascript/                # compiled React UI + locales (en/fr)
├── javascript/SfdcAdmin/          # React 18 admin UI sources
migration/                         # one-time Groovy patches (nt:unstructured -> typed)
```

---

## Troubleshooting

- **Admin action returns 403** — caller is not a server/site admin, or the request is cross-origin.
- **Admin action returns 404** — caller is not authenticated (guest cannot resolve the host node). Repeatedly authenticating with fresh sessions can exhaust the authenticated-visitor license cap → `guest` fallback; reuse a session.
- **Mapping UI shows only generic properties (not your jCustomer's own):** `jcustomer.url`/credentials unset or unreachable (log: `Could not load jCustomer properties live` or `live property listing disabled`), so only the generic Unomi fallback + `SFDC_*` ids are listed. Set them to the jCustomer container hostname, not `localhost`.
- **`saveConnection` rejects the instance URL** — must be `https` and a Salesforce domain.
- **Inbound: no profiles updated:** a jCustomer profile must exist for the record's email (matched, not created). The login sync logs which case applies: `jCustomer profile updated`, `Salesforce record found but nothing updated`, or `no Salesforce Contact or Lead with this email`.
- **Inbound: `jCustomer did not update profile ... (event collector returned updated=0)`:** profile updates use Unomi's privileged `updateProperties` event, signed with the jCustomer key of the site's jExperience settings (`X-Unomi-Peer`). jCustomer must allow that key from the Jahia server's address with `updateProperties` among the allowed events (`UNOMI_THIRDPARTY_PROVIDER1_KEY` / `_IPADDRESSES` / `_ALLOWEDEVENTS`). This is the same setup jExperience needs for its own login events; on Jahia Cloud it is provided.
- **`[SFDC-Site] ... set jcustomer.site.key`:** no site could be resolved (no site has jExperience, or several do). Set `jcustomer.site.key` to the site the connector serves.
- **Outbound: a profile edit isn't reaching Salesforce** — the poll runs on its schedule (default 15 min); use "Sync now" for an immediate run. Console/REST profile edits are caught by the profile poll (via `systemProperties.lastUpdated`).
- **Outbound: "Cannot create Lead/Contact"** — Salesforce requires `LastName` (+ `Company` for Lead) to create; map them, or existing records still update.

---

## License

[MIT](LICENSE) — free to use, modify, and distribute. Provided as-is, without warranty.
