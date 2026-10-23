# Changelog

All notable changes to the jCustomer to Salesforce Connector are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[2.3.3]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.3.3
[2.3.2]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.3.2
[2.3.1]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.3.1
[2.3.0]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.3.0
[2.2.1]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.2.1
[2.2.0]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.2.0
[2.1.0]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.1.0
[2.0.0]: https://github.com/Jahia/jcustomer-sfdc-connector/releases/tag/v2.0.0
