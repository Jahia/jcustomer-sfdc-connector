# jCustomer → SFDC outbound webhook (Unomi plugin)

A custom Apache Unomi **action** that calls the Jahia SFDC connector's outbound webhook when a rule
matches an event, so the affected profile is pushed to Salesforce as a Lead. Deployed into the
**jCustomer / Unomi Karaf container** — NOT into Jahia. (Apache Unomi ships no HTTP/webhook action,
so this bridges the gap.)

## Build

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn clean package
```
Produces `target/unomi-sfdc-webhook-action-<version>.jar`.

## Deploy (into the jCustomer container)

Copy the jar into Karaf's hot-deploy folder:

```bash
docker cp target/unomi-sfdc-webhook-action-*.jar <jcustomer-container>:/opt/jcustomer/deploy/
```

On start it registers action type `sfdcWebhookAction` (executor `sfdcWebhook`). Verify:

```bash
curl -u <user>:<pass> http://<jcustomer>:8181/cxs/definitions/actions/sfdcWebhookAction
```

## Rules

Create rules via `POST /cxs/rules` (see `rules/`). Each rule triggers `sfdcWebhookAction` with two
parameters:
- `url`    — the Jahia webhook: `http://<jahia-host>:8080/cms/render/live/en/sites/systemsite.sfdcOutboundWebhook.do`
- `secret` — the shared secret from Jahia (JCR `/settings/jcustomer-sfdc-connector/outbound/webhook/secret`),
  sent as the `X-Sfdc-Webhook-Secret` header and validated by the Jahia webhook.

Shipped rules:
- `sfdcOutboundOnForm.json` — fires on `form` events.
- `sfdcOutboundOnProfileUpdate.json` — fires on `updateProperties` events **when the profile has an email**.

Replace `JAHIA_HOST` and `REPLACE_WITH_WEBHOOK_SECRET` before posting.

## Notes

- The action is **fire-and-forget** (async, short timeout) because Unomi runs rule actions
  synchronously in the event pipeline — it must not add latency.
- It only calls out when the profile has an `email` (needed to match/create the Lead); otherwise no-op.
- Loop-safety: the profile-update rule keys on `updateProperties`, which is distinct from the
  connector's inbound `updateUserEfficyPortalData` event, so inbound writes don't bounce back out.
