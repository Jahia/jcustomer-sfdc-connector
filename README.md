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
2. Match each record to a jCustomer profile - by a stored SFDC id (`SFDC_ContactId`), else by email.
3. Update the profile's properties via the Unomi event collector, per the field mapping.

| Trigger | Component | When |
|---|---|---|
| Scheduled batch | `SfdcSyncJob` (Quartz) | cron (default daily 05:00), delta by `LastModifiedDate` |
| On login | `SfdcLoginEventHandler` | every user login (single-record sync) |
| Manual | `TriggerSyncAction` | admin clicks "Sync now" |

### Outbound (jCustomer → SFDC)

Poll-based, following the approach of Jahia's official [Workato Unomi connector](https://github.com/Jahia/workato-unomi-connector) - no custom Unomi plugin, no webhook, nothing deployed into the jCustomer container:

1. Poll `/cxs/profiles/search` for profiles changed since the last run (`systemProperties.lastUpdated`), and `/cxs/events/search` for recent **form** events. Every change since the last run is read, 200 at a time, oldest first, up to the start of the run: a bulk import is pushed whole, however many profiles it touched and even when thousands share the same second.
2. For each changed profile (with an email), push to the configured **target object** - **Lead** or **Contact** (chosen in the Triggers tab).
3. Resolve the target record by the stored id (`SFDC_LeadId` / `SFDC_ContactId`) then email; **update** if found, **create** if not. The new id is written back to the profile for idempotent future updates.
4. A profile whose push fails (Salesforce unreachable, a validation rule, an API limit) is kept in a retry list (`retryProfiles` on `/settings/jcustomer-sfdc-connector/outbound/syncState`) and pushed again by the next polls, **5 attempts** in all; then it is dropped with an error in the History tab. At most 1000 profiles wait for a retry. A poll that fails as a whole (jCustomer unreachable) leaves the watermark where it was, so the next poll reads the same changes again. Only one poll runs at a time: *Sync now* while a poll runs is skipped.

Create requirements are per object (Salesforce rules): **Lead** needs `LastName` + `Company`, **Contact** needs `LastName`. Missing → skipped with a warning (updates still work), unless the mapping gives a **value if empty** (for example `Company` = *Web visitor*). A mapping row can also send a **fixed value** (no jCustomer property, for example `LeadSource` = *Web*). A profile is pushed only when at least one mapped property has a value: default and fixed values alone never push.

**Converted Leads.** When the target is **Lead** and Salesforce has converted the person's Lead to a Contact, the push updates that **Contact** instead: after Salesforce refuses the update of a stored `SFDC_LeadId` because the Lead is converted, and before creating a Lead when the only Lead with this email is converted (so a person already a Contact is never created again as a Lead). The Contact gets the outbound **Contact** mapping when one is configured, otherwise the fields of the Lead mapping that Contact also has (`Company`, for one, is not on Contact). Its id is written back as `SFDC_ContactId`, and the History tab says which Lead was converted.

| Trigger | Component | When |
|---|---|---|
| Profile updates / Form submissions | `SfdcOutboundReconcileJob` (Quartz poll) | runs while either trigger is on, every 5 minutes by default (`outbound.poll.cron`) or on the schedule chosen in the Triggers tab; pushes changes made since the trigger was turned on (no backfill) |
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

The build runs the unit tests (`src/test/java`, JUnit 5: mapping with default values, converted Leads, Salesforce id checks). Produces `target/jcustomer-sfdc-connector-2.6.3.jar`. Deploy into a running instance's hot-deploy
folder (remove any older versioned jar first so only one version is present):

```bash
docker cp target/jcustomer-sfdc-connector-2.6.3.jar <jahia-container>:/var/jahia/modules/
```

Alternatively use the Provisioning API or the jContent module manager.

---

## Configuration

### Runtime behaviour - OSGi config

PID `org.jahia.community.jcustomer.sfdc` (`src/main/resources/META-INF/configurations/org.jahia.community.jcustomer.sfdc.cfg`):

| Key | Default | Purpose |
|---|---|---|
| `scheduler.cron` | `0 0 5 * * ?` | Quartz cron for the inbound batch sync |
| `sfdc.api.version` | `v66.0` | Salesforce REST API version used when the Connection tab leaves *API version* empty (the Connection value wins when set). Not repeated anywhere in code. |
| `sfdc.batch.size` | `200` | Max records per SOQL page |
| `sync.delta.buffer.minutes` | `5` | Clock-skew buffer for delta / poll |
| `outbound.poll.cron` | `0 0/5 * * * ?` | jCustomer to Salesforce poll schedule while *Profile updates* or *Form submissions* is on, unless one is chosen in the Triggers tab. The default lives in the component configuration, so it also applies to existing installs (whose `.cfg` is never rewritten on upgrade). |
| `history.retention.days` | `30` | Days a run is kept in the sync history when the History tab has not set the automatic purge of its direction. |
| `history.purge.cron` | `0 7 * * * ?` | When the automatic purge runs (every hour by default), on the processing server. |
| `history.max.records.per.run` | `500` | Records detailed per run, at most 1000 (counts stay exact; the run is marked truncated beyond it). |
| `history.max.value.length` | `200` | Field values longer than this are cut in the history. |
| `sync.on.login` | `true` | Sync a user immediately on login |
| `jcustomer.site.key` | *(empty: auto-detect)* | Site the connector works for: its jExperience settings select the jCustomer instance and the key used to update profiles, and its **site administrators may manage this connector**. Empty = the single site with the `jexperience` module installed; set it when several sites have jExperience. A value that is not a site on this server is ignored with a warning. Manual runs from the admin UI use the site the UI is open on. |
| `jcustomer.url` / `jcustomer.username` / `jcustomer.password` | *(unset)* | jCustomer REST access for the property-listing call - supplied per-environment, never committed |

> **Credentials are not shipped in source.** Set `jcustomer.url` / `jcustomer.username` / `jcustomer.password` so the mapping UI lists the **connected jCustomer's own** profile properties (fetched live from `/cxs/profiles/properties/targets/profiles`). Use the jCustomer container hostname (e.g. `http://jcustomer:8181`), **not** `localhost`. When unset or unreachable, the UI falls back to a small set of **generic** Unomi well-known properties plus the connector's `SFDC_ContactId/AccountId/LeadId` writeback ids (fail-closed), with no customer-specific names. The shipped `.cfg` carries a `# default configuration` marker, so the values you set survive a module redeploy instead of reverting to the defaults.

### Connection & mapping - stored in JCR (`/settings/jcustomer-sfdc-connector`)

The config is stored under **typed** node types (namespace `jcsfdcnt`, defined in `META-INF/definitions.cnd`), so it is browsable and self-describing in the JCR / Definitions browser. Property types are real JCR types (`boolean`, `long`, `date`, `string`) rather than untyped strings.

| Node | Type | Holds |
|---|---|---|
| `connection/` | `jcsfdcnt:connection` | `instanceUrl`, `clientId`, `sfUsername`, `privateKey` (unencrypted PKCS#8 PEM), `apiVersion` (empty = `sfdc.api.version`), `enabled` (shared by both directions) |
| `mapping/{Contact,Account,Lead}` | `jcsfdcnt:objectMapping` → `jcsfdcnt:fieldMapping` | inbound `sfField → jcProperty` entries |
| `triggers/` | `jcsfdcnt:triggers` | inbound `loginEnabled`, `schedulerEnabled`, `schedulerCron` |
| `syncState/` | `jcsfdcnt:syncState` | last inbound run |
| `outbound/` | `jcsfdcnt:outbound` | outbound container |
| `outbound/mapping/{Lead,Contact}` | `jcsfdcnt:mappingSet` → `jcsfdcnt:objectMapping` | outbound `jcProperty → sfField` entries |
| `outbound/triggers/` | `jcsfdcnt:outboundTriggers` | `outboundObject` (Lead \| Contact), `onProfileUpdate`, `onFormSubmission` (the triggers: the poll runs while one is on), `reconcileCron` (empty = `outbound.poll.cron`); `reconcileEnabled` is legacy (up to 2.3.4) and ignored |
| `outbound/syncState/` | `jcsfdcnt:syncState` | last outbound run + poll watermark |

### Sync history - stored in JCR (`/settings/jcustomer-sfdc-connector-history`)

Its own root, typed so it is browsable, split by day (UTC) so that no node holds more than a day of runs: `jcsfdcnt:historyRoot` → `inbound` / `outbound` (`jcsfdcnt:syncHistory`) → `yyyy` → `MM` → `dd` (`jcsfdcnt:historyFolder`) → one `jcsfdcnt:syncRun` per run (trigger, status, start/end, created/updated/skipped/error counts) → one `jcsfdcnt:syncRecord` per record (outcome, email, profile id, Salesforce object and id, fields written, message). Runs stored flat by versions before 2.6.0 are moved into their day folder when the module starts.

It holds personal data (emails and field values) copied from both systems, so it is purged automatically: each direction keeps its runs for **30 days** by default, set in its History tab (on/off and number of days, stored as `purgeEnabled` / `retentionDays` on its `jcsfdcnt:syncHistory` node; `history.retention.days` when unset). The purge job runs every hour on the processing server, removes whole days at a time, and the **Purge history** button deletes everything at once.

**Migration from earlier versions:** releases before 2.2.0 stored this config as `nt:unstructured`. The one-time patch `migration/2100-migrate-config-to-typed.groovy` converts an existing `/settings/jcustomer-sfdc-connector` subtree to the typed node types. Deploy the 2.2.0+ jar first (so the CND is registered), then drop the patch into `<jahia>/patches/groovy/`; the Jahia Patcher runs it once and renames it `.installed`. It is idempotent and reads tolerantly of the legacy layout, so re-running or running on an already-typed config is a no-op.

---

## Admin UI

Site administration → **Salesforce ⇄ jCustomer Sync** (route `/jahia/administration/<siteKey>/jcustomer-sfdc-connector`). The screen follows Jahia's administration layout and is built only from Moonstone components (cards, setting rows with switches, chips, banners, tables). All labels and hints come from the EN/FR locale files.

Four top-level tabs:

- **Overview**: the state of the whole connector at a glance. The connection (tested on open), and one card per direction with its status (*Active* / *Paused* / *Off*), its triggers in plain words (*On login*, *Every day at 05:00*, *Profile updates*, *Every 5 min*), how many fields each object maps, and the last run (relative time, trigger, result counts), with *Sync now*, *Triggers* and *History* shortcuts. Anything that needs attention (connection not configured, disabled or failing, a direction without mapping, errors in the last run) is listed on top with a button that opens the screen that fixes it.
- **Connection** *(shared by both directions)*: the on/off switch, the Salesforce org (instance URL, API version), the Connected App (client id, integration user) and the private key, an unencrypted **PKCS#8** PEM (`-----BEGIN PRIVATE KEY-----`; convert PKCS#1 with `openssl pkcs8 -topk8 -nocrypt -in old.key -out key.pem`), with a how-to. *Save* is enabled only when something changed; *Test connection* shows its result in place.
- **SFDC → jCustomer** and **jCustomer → SFDC**, each with three sub-tabs:
  - **Mapping**: one table per Salesforce object (Contact / Account / Lead inbound, Lead / Contact outbound), the object buttons showing how many fields each maps and a dot when it has unsaved changes. Outbound rows also have a **Value if empty**, and *No property (fixed value)* sends a value always. Rows are edited in place with searchable dropdowns; the screen warns about a target mapped twice and, outbound, about fields Salesforce requires to create a record. The jCustomer-property dropdown lists the connected jCustomer's live profile properties (see the config note above) and the Salesforce-field dropdown the object's fields, both sorted **alphabetically**.
  - **Triggers**: inbound, *On login* and the scheduled batch (cron builder with its schedule in plain words) and *Sync now*. Outbound, the **target object** (Lead or Contact), the triggers (*Profile updates* / *Form submissions*: turning one on starts the automatic push), the schedule (how often jCustomer is polled, every 5 minutes by default), then *Run now*: push the latest changes, or push one profile by email (Enter or *Push now*), with the result shown next to the button.
  - **History**: see below.

The screen fits narrow panes: cards stack, setting controls drop under their text, header tabs scroll sideways and wide tables scroll inside their card.

### History

Each direction has a **History** tab: one row per run (when, trigger: *Scheduled*, *Sync now*, *Login*, *Manual push*; result counts), built with Moonstone's structured `DataTable`. Expanding a run loads its records: who (email, profile id), outcome (created / updated / skipped / error), the Salesforce record, the fields written, and why a record was skipped or failed. Scheduled runs that found no change are not recorded, so a frequent poll does not bury the useful runs; manual runs are always recorded.

- **Export CSV / Export JSON** download the whole history of the direction: CSV has one line per record with its run's columns (a run without records gets one line); JSON keeps runs with their `records`.
- **Automatic purge**: runs older than the chosen number of days (30 by default) are deleted every hour; turn it off to keep everything until a manual purge. Saving applies it at once and says how many runs it removed.
- **Purge history** deletes every recorded run of the direction, after a confirmation.

### Exporting a mapping

Each Mapping tab (both directions) has **Export CSV** and **Export JSON** buttons that download the currently-selected object's mapping. The export is generated in the browser - no server round-trip. Only complete rows (both the jCustomer property and the Salesforce field set) are written; the download filename encodes direction and object, e.g. `sfdc-inbound-Contact-mapping.csv`, `sfdc-outbound-Lead-mapping.json`.

- **CSV** - a `jcProperty,sfField` header plus one line per mapping (RFC-4180 quoting).
- **JSON** - a self-describing document: `{ "direction", "object", "exportedAt", "mappings": [ { "jcProperty", "sfField" } ] }`.

---

## Security model

Admin operations are Jahia Actions at `POST /cms/render/default/{lang}/sites/systemsite.{action}.do`. Every action extends `AbstractSfdcAdminAction`, which enforces two controls before any logic (HTTP 403 otherwise):

1. **Authorization** - the caller must be a **server administrator** (`root` / global `administrators`) **or** a **site administrator** of the connector's configured site.
2. **Same-origin (CSRF) defense** - the request `Origin`/`Referer` host must match the Jahia host.

On top of these, the actions that change something (`save*`, `trigger*`, `purgeSyncHistory`) are under Jahia's **CSRF guard**: a call without the session's token is refused (redirected to `/error.html`). The admin screen calls every action with `XMLHttpRequest`, to which the guard's page script adds the token. Only the read-only actions (`get*`, `testConnection`) are in the module's guard whitelist (`META-INF/configurations/org.jahia.modules.jahiacsrfguard-jcustomer-sfdc.cfg`), which the module rewrites on every deploy. A script calling a write action must send the guard's token (`CSRFTOKEN` header, read from `/modules/CsrfServlet`).

Additional hardening:
- **SSRF allow-list** - the Salesforce `instanceUrl` is validated on save (`https` + `*.salesforce.com` / `*.force.com`) before any outbound call carrying the org's signed JWT.
- **No secrets in source** - jCustomer credentials supplied per-environment; the private key is stored in JCR and never returned to the browser.
- **SOQL escaping** on user-derived values.

---

## Project layout

```
src/
├── main/java/org/jahia/community/modules/jcustomer/sfdc/
│   ├── actions/     # Jahia Actions (AbstractSfdcAdminAction base + inbound & outbound actions)
│   ├── auth/        # SfdcAuthService - JWT Bearer OAuth2 (RS256)
│   ├── client/      # SfdcApiClient - SOQL query, Describe, create/update sObject
│   ├── config/      # ConnectorConfigService - JCR-backed config (inbound + outbound)
│   ├── jcustomer/   # JCustomerProfileService - profile find/get/update + change/event polls
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

- **Admin action returns 403** - caller is not a server/site admin, or the request is cross-origin.
- **Admin action returns 404** - caller is not authenticated (guest cannot resolve the host node). Repeatedly authenticating with fresh sessions can exhaust the authenticated-visitor license cap → `guest` fallback; reuse a session.
- **Mapping UI shows only generic properties (not your jCustomer's own):** `jcustomer.url`/credentials unset or unreachable (log: `Could not load jCustomer properties live` or `live property listing disabled`), so only the generic Unomi fallback + `SFDC_*` ids are listed. Set them to the jCustomer container hostname, not `localhost`.
- **`saveConnection` rejects the instance URL** - must be `https` and a Salesforce domain.
- **Inbound: no profiles updated:** a jCustomer profile must exist for the record's email (matched, not created). The login sync logs which case applies: `jCustomer profile updated`, `Salesforce record found but nothing updated`, or `no Salesforce Contact or Lead with this email`.
- **Inbound: `jCustomer did not update profile ... (event collector returned updated=0)`:** profile updates use Unomi's privileged `updateProperties` event, signed with the jCustomer key of the site's jExperience settings (`X-Unomi-Peer`). jCustomer must allow that key from the Jahia server's address with `updateProperties` among the allowed events (`UNOMI_THIRDPARTY_PROVIDER1_KEY` / `_IPADDRESSES` / `_ALLOWEDEVENTS`). This is the same setup jExperience needs for its own login events; on Jahia Cloud it is provided. A profile that already holds the Salesforce values is not an error: it is recorded as *skipped: already up to date* (2.4.1).
- **Inbound: a multi-valued jCustomer property stays empty although the history lists it as updated:** before 2.5.0 a single Salesforce value was written as text to a multi-valued property. Values now follow the jCustomer property type (a list for a multi-valued property, a multi-select picklist split on `;`); install 2.5.0 and run **Sync now**, profiles holding a text are rewritten as lists.
- **`[SFDC-Site] ... set jcustomer.site.key`:** no site could be resolved (no site has jExperience, or several do). Set `jcustomer.site.key` to the site the connector serves.
- **Outbound: a profile edit isn't reaching Salesforce:** check that *Profile updates* is on (it is what starts the automatic push), then wait for the next poll (every 5 minutes by default) or use "Sync now". Only changes made after the trigger was turned on are pushed. Console/REST profile edits are caught too (via `systemProperties.lastUpdated`). The poll log reports `created` / `updated` / `skipped` / `errors`; `skipped` means nothing was sent (no mapping, no mapped value, or a record Salesforce cannot create).
- **Outbound: "Cannot create Lead/Contact"** - Salesforce requires `LastName` (+ `Company` for Lead) to create; map them, or existing records still update.

---

## License

[MIT](LICENSE) - free to use, modify, and distribute. Provided as-is, without warranty.
