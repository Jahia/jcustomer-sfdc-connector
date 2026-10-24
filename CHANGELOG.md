# Changelog

All notable changes to the jCustomer to Salesforce Connector are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.6.2] - 2026-10-24

### Added
- **Default and fixed values in the outbound mapping.** Each jCustomer to Salesforce row has a *Value if empty*, sent when the profile has no value for the property: `Company` = *Web visitor* lets a Lead be created for visitors who gave no company, where they were skipped on every poll. A row with *No property (fixed value)* sends its value always (`LeadSource` = *Web*). A profile is pushed only when at least one mapped property has a value. Exports include the column. Stored as `defaultValue` on the mapping row (`jcsfdcnt:fieldMapping`).
- Unit tests (JUnit 5), run by the build.

### Fixed
- **Converted Leads.** Once Salesforce converted a person's Lead to a Contact, every push failed (Salesforce refuses updates of a converted Lead), and a profile without a stored Lead id created a duplicate Lead. With the target Lead, the push now updates the Contact the Lead was converted to, with the outbound Contact mapping when one is configured, otherwise the fields of the Lead mapping that Contact also has; the Contact id is written back as `SFDC_ContactId` and the History tab says which Lead was converted.
- Salesforce record ids read from profiles are checked (15 or 18 letters and digits) before they reach a request URL or a query.

## [2.6.1] - 2026-10-24

### Fixed
- **jCustomer to Salesforce: changes beyond 500 profiles were never pushed.** A poll read at most 500 changed profiles (and 500 form events), oldest first, then moved its watermark to the start of the run: after a bulk import or a busy campaign, the rest were skipped until they changed again. A poll now reads every change since the last run, 200 at a time, continuing after the last profile read (by update time and id, so profiles sharing the same second are neither skipped nor read twice), up to the start of the run.
- **jCustomer to Salesforce: a profile whose push failed was not retried.** It was logged, then left behind by the watermark until it changed again. Failed profiles are now kept in a retry list and pushed again by the next polls, 5 attempts in all, then dropped with an error in the History tab (each error says which attempt it was). At most 1000 profiles wait for a retry.
- *Sync now* and the scheduled poll could run at the same time and race on the watermark; only one poll runs at a time now (the other is skipped).

## [2.6.0] - 2026-10-24

### Added
- **Automatic purge of the sync history**, set per direction in its History tab (on/off and number of days, **30 days** by default): runs older than that are deleted every hour by a new job on the processing server (`history.purge.cron`, default `0 7 * * * ?`). Saving applies it at once and says how many runs it removed. The default for directions not set in the UI is `history.retention.days`.

### Changed
- **History stored by day:** runs live in `<direction>/<yyyy>/<MM>/<dd>/` (UTC) under `/settings/jcustomer-sfdc-connector-history` instead of all under one node per direction, so no node holds more than a day of runs (JCR slows down past about a thousand children). New node type `jcsfdcnt:historyFolder`; runs stored by earlier versions are moved into their day folder when the module starts. Purges remove one day per save, never the whole history in a single transaction.
- `history.max.records.per.run` is capped at 1000 (higher values are lowered, with a warning).
- The history is always read and written in the `default` workspace, whatever the workspace of the request that recorded a run.
- An error answered by an action is shown with its message in the admin screen, not only its HTTP status.

### Fixed
- **History: opening a run on page 2 or later went back to page 1.** The table's own pagination returned to the first page whenever its data changed, and opening a run changes it (its records arrive). The runs are now paged by the screen itself, under the table; the pagination also stays inside its card in a narrow pane.

### Removed
- `history.max.runs`: the history is bounded by the retention in days instead.

## [2.5.0] - 2026-10-24

### Added
- **Overview tab**, opened first: the connection (tested on open) and one card per direction with its status, triggers in plain words, mapping coverage per object and last run, with *Sync now*, *Triggers* and *History* shortcuts. Problems (connection not configured, disabled or failing, a direction without mapping, errors in the last run) are listed on top, each with a button that opens the screen that fixes it.
- Schedules are described in words next to the cron builder (*Every day at 05:00*, *Every 5 min*).
- Mapping warns about a target mapped twice; *Unsaved changes* chips on every screen, and *Save* is enabled only when something changed.
- Push one profile: Enter in the email field pushes.

### Changed
- **Admin screen redesigned on Moonstone**, following Jahia's administration layout: cards with a title and description, setting rows (label and help on the left, switch or control on the right), chips for states, banners for results and problems, empty states. Each direction has three sub-tabs: **Mapping**, **Triggers**, **History**. The two mapping screens are one component, the object picker shows how many fields each object maps and which have unsaved changes.
- Connection grouped into org, Connected App and private key, with a how-to for the key; the result of *Test connection* is shown in place.
- Run-now results are shown next to the button that produced them, not at the top of the page.
- History dates are relative (*5 hours ago*), the exact date in a tooltip.
- **History: every changed field is visible.** Moonstone table rows are a fixed 48 px and cut what does not fit, so a record that changed several fields showed only part of them. Rows now grow with their content, each field reads *name* (bold) and value, the Details column gets the most room, long emails and ids wrap, and column labels wrap instead of ending in an ellipsis.
- Spacing uses Moonstone's tokens.

### Fixed
- **Inbound: multi-valued jCustomer properties looked empty after a sync.** A Salesforce value was turned into a list only when it contained `;`, so a single choice of a multi-select picklist (`optical-solutions`) was written as plain text to a multi-valued jCustomer property. jCustomer stores it as given: jExperience shows the property empty and list conditions never match, while the history reports the field as updated. Values now take the shape of the jCustomer property type, read through jExperience (cached 5 minutes per site): a list for a multi-valued property (`a;b` split, a single value as a one-item list), the text unchanged for a single-valued one (a `;` in a text is no longer split). Profiles already holding such a text are rewritten as lists on their next sync.
- The admin screen overflowed narrow panes: Moonstone's content wrapper sized itself on the widest content, so cards were cut on the right, a header tab wrapped under the content could not be clicked, and table cells overlapped. Cards now stack, controls drop under their text, header tabs scroll sideways and wide tables scroll inside their card.
- Column renderers work with the `DataTable` of Moonstone 2.20+ (jContent 3.7.1 and later), which passes one object instead of `(value, row)`; tables no longer paginate by 5 rows.
- Labels with a count no longer show as raw keys (the host's i18next does not resolve plural keys).
- Texts referred to the old *Field Mapping* tab; em dashes replaced in labels.

### Security
- **Actions that change something are back under Jahia's CSRF guard.** The guard whitelist listed every connector action, so a page on another site could make a signed-in administrator's browser save the connection or mappings, change triggers, start syncs or pushes, or purge the history (the `Origin`/`Referer` check of the actions remained the only control). The whitelist now holds only the read-only actions (`get*`, `testConnection`); `save*`, `trigger*` and `purgeSyncHistory` require the guard's token, and a call without it is refused.
- The admin screen calls the actions with `XMLHttpRequest` instead of `fetch`, so the guard's page script adds the token; a refused call shows an error instead of being read as data.

## [2.4.1] - 2026-10-23

### Fixed
- **Inbound: a profile already up to date was reported as an error** (`jCustomer did not update profile ... updated=0`, with advice about the jCustomer key). jCustomer answers `updated=0` both when it refuses the event and when the profile already holds the values, so every login or manual sync of an unchanged profile failed. The sync now sends only the mapped values that differ from the profile and records the others as *skipped: already up to date*; when jCustomer still answers `0`, the profile is read back and the error is raised only if the values are not in place.

- **History tab: Refresh, Export and Purge out of sight on jContent 3.8.** A run with a long status (an error message) widened the table, and on jContent 3.8 the connector screen grew with it past the window, so the buttons (and the Status / Details column) were off screen and could not be scrolled to. The screen now keeps the width of its container, long statuses and messages wrap, and a table still too wide scrolls on its own.

### Changed
- An inbound history record lists only the fields that changed, not every mapped field.

## [2.4.0] - 2026-10-23

### Added
- **Sync history**, one **History** tab per direction: each run (when, what triggered it, created / updated / skipped / error counts) as a collapsible row; expanding it shows its records: who (email, profile id), outcome, the Salesforce record, the fields written and why a record was skipped or failed. Built with Moonstone's structured `DataTable`, records loaded on expand.
- **Export CSV / Export JSON** of a direction's whole history, and **Purge history** (with confirmation).
- History stored as typed JCR nodes under its own root `/settings/jcustomer-sfdc-connector-history` (new types `jcsfdcnt:historyRoot`, `syncHistory`, `syncRun`, `syncRecord`), with retention from `history.max.runs` / `history.max.records.per.run` / `history.max.value.length`. Scheduled runs that found no change are not recorded; writing history never fails a sync.

## [2.3.5] - 2026-10-23

### Fixed
- **jCustomer to Salesforce: "Profile updates" did not push anything automatically.** The poll that implements the triggers ran only when a separate schedule switch was also on, and nothing said so: a checked trigger with the switch off never fired. The poll now runs exactly while *Profile updates* or *Form submissions* is on.
- The triggers tab showed *Profile updates* / *Form submissions* checked when nothing was stored (a default injected by the loader). It now shows what is stored; an absent trigger is off.
- The poll counted a profile it did not send (no mapping, no mapped value, or a record that cannot be created) as "updated". Results are now `created` / `updated` / `skipped` / `errors`, and the manual push reports *skipped* instead of *pushed*.

### Changed
- Default poll frequency is **every 5 minutes** (`outbound.poll.cron`), defined once in the component configuration so it also reaches existing installs. A schedule chosen in the Triggers tab still wins; a schedule stored while the old switch was off (never in effect) is ignored.
- Turning a trigger on pushes the changes made **from that moment** (no 24-hour backfill on the first run); turning all triggers off resets it.
- The poll is skipped while the Salesforce connection is disabled or not configured.
- The separate "Enable scheduled sync" switch is removed.

## [2.3.4] - 2026-10-23

### Fixed
- `sfdc.api.version` from the OSGi configuration was documented but never read: the API version was hardcoded in the API client, the configuration service, the CND default and the admin UI. It now has one source: the Connection's value when set, else `sfdc.api.version`. A missing version raises a clear error instead of a guessed value.
- Connection tab: labels, placeholders and hints were hardcoded in the code, some in French for every language. They now come from the EN/FR locale files.
- Connection tab: the key guidance said "PKCS#8 or PKCS#1" and showed a PKCS#1 header, but only **PKCS#8** keys work. The hint now names PKCS#8 and gives the `openssl pkcs8 -topk8 -nocrypt` conversion.

### Changed
- The API version field stays empty unless set, and shows the configured `sfdc.api.version` as its placeholder, so saving the form no longer pins the default on the connection.

## [2.3.3] - 2026-10-23

### Fixed
- **Salesforce to jCustomer updates were silently dropped** on any jCustomer without a project-specific rule: profile updates sent a custom `updateUserEfficyPortalData` event that only one demo jCustomer handled, so jCustomer accepted it (`updated: 0`) and changed nothing while the log said "Login sync complete". Updates now use Unomi's standard **`updateProperties`** event, signed (`X-Unomi-Peer`) with the jCustomer key from the site's jExperience settings, so they work wherever jExperience works, on Jahia Cloud or on-premise. The event collector's result is checked: a dropped update raises an explicit error instead of a success log.
- Login sync logging tells apart "profile updated", "Salesforce record found but nothing updated" and "no Salesforce record".
- "Sync now" (inbound) always ran against a hardcoded site, and the outbound reconcile job ran with the class defaults instead of the configured site and buffer (Quartz instantiates the job per run).
- A failed Salesforce-id writeback no longer fails an outbound push that Salesforce already accepted.

### Changed
- **No project-specific site default.** The site is resolved in one place: the site the admin UI is open on (manual runs), else `jcustomer.site.key` when it names an existing site, else the single site with jExperience installed. `jcustomer.site.key` now ships empty; a stale value that is not a site on the server is ignored with a warning. Site administrators of the resolved site may manage the connector.

## [2.3.2] - 2026-10-23

### Changed
- Mapping UI: the jCustomer-property and Salesforce-field dropdowns (both sync directions) are now sorted **alphabetically** (case-insensitive) instead of fetch order.

### Fixed
- The shipped OSGi configuration (`org.jahia.community.jcustomer.sfdc.cfg`) now carries the `# default configuration` marker, so operator-set values (`jcustomer.url` / credentials) **survive a module redeploy** instead of being overwritten back to the defaults, which silently dropped the live jCustomer property lookup to fallback-only.

## [2.3.1] - 2026-10-23

### Fixed
- Removed hardcoded, customer-specific `MySopraHR_*` entries from the jCustomer property fallback list. The mapping dropdown now reflects the **connected jCustomer's own** properties; the fallback is limited to generic Unomi well-known properties plus the connector's `SFDC_ContactId` / `SFDC_AccountId` / `SFDC_LeadId` writeback ids (added `SFDC_LeadId`).

## [2.3.0] - 2026-08-05

### Security
- Hardened JSON handling in the authentication flow and added sink-type validation in the Salesforce API client.

## [2.2.1] - 2026-08-04

### Added
- MIT license (`LICENSE`, `pom.xml` `<licenses>`, `package.json` `license`).
- Repository metadata (`url`, `organization`, `scm`, `issueManagement`; `repository` / `homepage` / `bugs`) pointing at the Jahia organization repository.

## [2.2.0] - 2026-08-04

### Added
- Typed JCR configuration storage under a proper CND (`jcsfdcnt:*` node types) replacing `nt:unstructured`, browsable in the JCR / Definitions browser, with a one-time Groovy migration patch (`migration/2100-migrate-config-to-typed.groovy`).
- Per-object **CSV / JSON export** of field mappings (both directions) from each Field Mapping tab.

## [2.1.0] - 2026-07-23

### Changed
- Outbound sync targets a **single** object (Lead **or** Contact), chosen in the Triggers tab.
- Friendly cron builder for the outbound schedule, down to sub-hourly (every N minutes); consistent trigger-row alignment.

## [2.0.0] - 2026-07-23

### Added
- **Bidirectional** synchronization: outbound (jCustomer to Salesforce) added alongside the existing inbound (Salesforce to jCustomer), poll-based following the Workato Unomi connector approach, triggered on profile update and form submission.
- Security hardening: admin actions gated to server/site administrators, same-origin (CSRF) defense, SSRF allow-list on the Salesforce instance URL, SOQL escaping.
- Salesforce **Lead** object support; README.

[2.4.0]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.4.0
[2.3.5]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.3.5
[2.3.4]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.3.4
[2.3.3]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.3.3
[2.3.2]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.3.2
[2.3.1]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.3.1
[2.3.0]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.3.0
[2.2.1]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.2.1
[2.2.0]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.2.0
[2.1.0]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.1.0
[2.0.0]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.0.0
