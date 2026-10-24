# jcustomer-sfdc-connector: agent harness

What an agent needs to work on this module across sessions. Generic Jahia knowledge (OSGi, JCR,
Moonstone, CSRF, jCustomer, CRM integrations, releases) lives in the AIStartupKit harness, the sole
reference: this folder holds only what is specific to this module and links there for the rest.

| File | Read it when |
|---|---|
| [context/architecture.md](context/architecture.md) | You change any behaviour: components, data flows, JCR storage, admin screen |
| [context/decisions.md](context/decisions.md) | You are tempted to change how something works: the choices made and why |
| [context/environments.md](context/environments.md) | You run, deploy or test: local molex-demo stack, Salesforce sandbox, cloud, test techniques that do not write to Salesforce |
| [skills/release/SKILL.md](skills/release/SKILL.md) | You merge and release a version |

## Rules for this module

- Every PR carries its `CHANGELOG.md` section, the README lines that describe the change, and these
  `.agents/` files when what they say changes. Sweep the Javadoc of the classes touched too.
- The module is generic: nothing project, customer or environment specific in code, CND, UI or the
  shipped `.cfg` (AIStartupKit CLAUDE.md rule 29). Molex and mysoprahr are test sites, never defaults.
- Every UI string comes from `src/main/resources/javascript/locales/en.json` and `fr.json`, kept in
  parity; the admin screen is built from Moonstone only (rule 30).
- Actions that change something are never in the CSRF guard whitelist; the screen calls every action
  with `XMLHttpRequest`, to which the guard's page script adds the token.

## Generic references (AIStartupKit)

- `CLAUDE.md` rules 13 (CSRF), 29 (no hardcoding), 30 (Moonstone in the host jContent)
- `.agents/skills/jahia-unomi-profile/SKILL.md`: jCustomer reads and writes, paging, change polls
- `.agents/context/jahia-integration-gateways.md` section 9: pushing people to a CRM, converted Leads
- `.agents/skills/jahia-java-jcr/SKILL.md`: many children under one node (the history tree)
- `.agents/context/jahia-release-and-ci.md`: release PR steps, Maven archive repositories, Store pack
