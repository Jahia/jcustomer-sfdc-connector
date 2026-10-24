# Architecture

An OSGi module (Java 17, Maven, parent `jahia-modules` 8.2.0.0) with a React 18 admin screen built by
webpack Module Federation (`frontend-maven-plugin`). Package root:
`org.jahia.community.modules.jcustomer.sfdc`.

## Components

| Package | Classes | Role |
|---|---|---|
| `actions` | `AbstractSfdcAdminAction` and one `*Action` per admin call (`get*`, `save*`, `trigger*`, `testConnection`, `purgeSyncHistory`) | `POST /cms/render/default/en/sites/systemsite.<name>.do`; the base class allows server administrators and site administrators of the connector's site, and checks `Origin`/`Referer` |
| `auth` | `SfdcAuthService` | Salesforce JWT Bearer flow (RS256) with the Connected App's private key (PKCS#8, stored in JCR, never sent to the browser) |
| `client` | `SfdcApiClient` | SOQL (all pages), describe, create/update; checks object names and record ids before URLs and queries |
| `config` | `ConnectorConfigService`, `SiteKeyResolver` | Typed JCR configuration; which site the connector works for |
| `jcustomer` | `JCustomerProfileService` | jCustomer through jExperience's `ContextServerService`: profile search, keyset-paged change and form-event searches, `updateProperties` writes with the site's jCustomer key |
| `sync` | `SfdcSyncService`, `SfdcSyncJob`, `SfdcOutboundSyncService`, `SfdcOutboundReconcileJob` | Inbound and outbound sync and their Quartz jobs |
| `events` | `SfdcLoginEventHandler` | Inbound sync of the visitor's own record at login |
| `history` | `SyncHistoryService`, `SyncHistoryPurgeJob` | Sync history in JCR and its hourly automatic purge |

## Data flows

**Inbound (Salesforce → jCustomer):** Contact / Account / Lead fields mapped to profile properties.
Triggered at login, by a scheduled delta (`LastModifiedDate`), or by *Sync now*. Profiles matched by
stored Salesforce id, else email. Only changed values are sent, typed after the jCustomer property
(lists for multi-valued properties).

**Outbound (jCustomer → Salesforce):** poll-based (no Unomi plugin). While *Profile updates* or *Form
submissions* is on, `SfdcOutboundReconcileJob` runs `runReconcile` (every 5 minutes by default): it
reads every profile changed and every form submitted in (watermark minus buffer, run start], 200 at a
time by keyset on (date, `itemId`), pushes each to the single target object (Lead or Contact), retries
failed pushes from `retryProfiles` (5 attempts), then moves the watermark to the run start. One poll at
a time. A push maps fields (value if empty, fixed values), finds the record by stored id else email,
updates or creates, and writes the id back to the profile. With the target Lead, a converted Lead's
Contact is updated instead.

## JCR storage (default workspace)

- `/settings/jcustomer-sfdc-connector` (`jcsfdcnt:connectorConfig`): `connection`, `mapping/<Object>`,
  `triggers`, `syncState`, `outbound/{mapping,triggers,syncState}`. Outbound `syncState` holds
  `lastReconcileAt` (the watermark) and `retryProfiles`.
- `/settings/jcustomer-sfdc-connector-history/<inbound|outbound>/<yyyy>/<MM>/<dd>/<run>/<record>`: one
  run per node in the folder of its UTC day; `purgeEnabled` / `retentionDays` on each direction node.
- Node types in `src/main/resources/META-INF/definitions.cnd`. Changes must stay additive on released
  versions (new types, optional properties, new child definitions).

## Configuration

PID `org.jahia.community.jcustomer.sfdc`, shipped with `# default configuration` (never rewritten on
upgrade): product defaults live in the components' `@interface Config` defaults, documented commented
out in the `.cfg`. The CSRF guard whitelist `org.jahia.modules.jahiacsrfguard-jcustomer-sfdc.cfg` has no
marker on purpose: the module rewrites it on every deploy. Reference of keys: README, "Configuration".

## Admin screen

`src/javascript/SfdcAdmin`: registered by `SfdcAdmin.routes.jsx` as a Site Settings route
(`administration-sites`, `requireModuleInstalledOnSite`). Tabs Overview, Connection, then one panel per
direction with Mapping (`MappingEditor`), Triggers and History (`HistoryTab`, `HistoryRetention`).
Layout helpers in `ui/` (`Section`, `layout.module.css`, `cell()` for the host `DataTable` render
signature). Calls go through `api.js` (`XMLHttpRequest`).

## Tests

JUnit 5 under `src/test/java`, run by every `mvn clean package` and by CI: mapping with default values,
converted Leads, Salesforce id checks, the objects a mapping accepts, with fakes subclassing the services. No Cypress suite yet.
