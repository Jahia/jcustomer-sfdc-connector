# Decisions

Choices already made, with the reason. Change one only on purpose, and record the new decision here.

| Decision | Why |
|---|---|
| Outbound is a poll of jCustomer, no Unomi plugin or webhook | Follows Jahia's Workato Unomi connector; nothing to deploy in jCustomer, works on Jahia Cloud |
| One outbound target object, Lead or Contact, chosen in the Triggers tab (`outboundObject`) | The person exists once in Salesforce on the push side; the reason for the original choice was not recorded, ask before pushing to both |
| A poll reads its whole window before moving the watermark; failures go to a retry list (5 attempts) | A capped read followed by a watermark jump lost every change past the cap (before 2.6.1) |
| Converted Leads: push to their Contact (Contact mapping, else the Lead fields Contact has), never create a Lead again | Salesforce refuses updates of converted Leads; an email lookup excluding them created duplicates (before 2.6.2) |
| Default values never push alone: at least one value must come from the profile | A fixed value would otherwise push every changed profile, mapped or not |
| The site is resolved at run time: the screen's site, else `jcustomer.site.key` when it names a site, else the single site with jExperience | Generic for any project, cloud or on premise; a demo site key hardcoded once made every other install drop updates (2.3.3) |
| jCustomer profile writes use `updateProperties` with the site's jCustomer key (`X-Unomi-Peer`) | Custom events were silently dropped (`updated=0`) |
| Write actions under the CSRF guard; the screen uses `XMLHttpRequest` | A whitelist entry removes the protection for every signed-in user, administrators included (2.5.0) |
| History stored by UTC day, purged after 30 days by default, per direction from the UI | JCR slows down past about a thousand children; the history holds personal data (2.6.0) |
| History paged by the screen, not by `DataTable` | `DataTable` returns to page 1 whenever its data changes, and opening a run changes it (2.6.0) |
| Product defaults in the components' `@interface Config`, not the shipped `.cfg` | The `.cfg` carries `# default configuration` and is never rewritten on upgrade |
| Release jars built by CI from the published release's tag | A local build writes the build machine's path into the manifest (`Jahia-Source-Folders`) (2.6.3) |
| Releases are GitHub releases with the jar; the Jahia Store listing is uploaded by hand from the Store pack | The module has never been on Nexus |
