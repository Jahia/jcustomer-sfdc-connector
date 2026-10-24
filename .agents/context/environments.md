# Environments and testing

## Local: molex-demo stack

The module is developed against the molex-demo Docker stack (repository `Jahia/molex-demo`).

| What | Where |
|---|---|
| Jahia | container `molex-demo-jahia-1`, `http://localhost:8085` (local default credentials) |
| jCustomer | container `molex-demo-jcustomer-1`, reachable from Jahia as `http://jcustomer:8181` |
| Site | `molex-classic`; admin screen at `/jahia/administration/molex-classic/jcustomer-sfdc-connector` |
| Salesforce | a sandbox org configured on the Connection tab (Lead target, Lead mapping with Molex custom fields; Contact has no Molex fields) |

Deploy a build: `docker cp target/jcustomer-sfdc-connector-<version>.jar molex-demo-jahia-1:/var/jahia/modules/`,
then wait for `Finished starting DX OSGi bundle jcustomer-sfdc-connector v<version>` in `docker logs`.
Removing a newer jar from the modules folder leaves the older version stopped: start it with the
provisioning API (`- startBundle: "jcustomer-sfdc-connector/<version>"`).

## Cloud

Stephane's Molex cloud instance runs jContent 3.8 (Moonstone 2.20.3, as jContent 3.7.1): screens must
be checked there too for layout (an admin root sized on its content pushed buttons off screen, 2.4.1).

## Testing without writing to Salesforce

The sandbox is shared: do not create or change Salesforce records for a test unless the user asked.
- **Paging and skips:** set the outbound target to Contact (no Contact mapping), so every push is
  skipped: `mutateNode` on `/settings/jcustomer-sfdc-connector/outbound/triggers`, property
  `outboundObject`. Put it back to `Lead` afterwards, and delete test profiles first: the next poll's
  window still contains them.
- **Failures and retries:** a profile with an invalid email (`not-an-email`) plus `lastName` and
  `company` makes Salesforce reject the Lead create (validation error, nothing created).
- **Test profiles:** `POST http://jcustomer:8181/cxs/profiles` with a UUID `itemId` (the poll skips other
  ids), `DELETE /cxs/profiles/<id>` afterwards (about 50 per minute).
- **Running a poll or a push synchronously:** a Groovy script through the provisioning API
  (`executeScript`). Module classes are not visible to the script's class loader: load them through the
  service, `svc.getClass().getClassLoader().loadClass('...SyncHistoryService$Trigger')`.
- **Emptying a poll window** to exercise the retry list alone: set `lastReconcileAt` on the outbound
  `syncState` ten minutes in the future; the poll resets it to its own start.
- **Reading Salesforce** (read-only SOQL) from Groovy: `SfdcApiClient.query(...)`.
- The admin pages keep a hidden `main.page-error` ("Something wrong happened"): a page-text reader
  reports it, it is not shown.
