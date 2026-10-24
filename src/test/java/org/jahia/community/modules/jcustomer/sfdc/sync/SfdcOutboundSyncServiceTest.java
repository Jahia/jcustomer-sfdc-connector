package org.jahia.community.modules.jcustomer.sfdc.sync;

import org.jahia.community.modules.jcustomer.sfdc.client.SfdcApiClient;
import org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService;
import org.jahia.community.modules.jcustomer.sfdc.jcustomer.JCustomerProfileService;
import org.jahia.community.modules.jcustomer.sfdc.sync.SfdcOutboundSyncService.PushResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Outbound push: mapping with default values, and Leads converted to Contacts. Salesforce and jCustomer are fakes. */
class SfdcOutboundSyncServiceTest {

    private static Map<String, String> row(String jcProperty, String sfField, String defaultValue) {
        Map<String, String> m = new HashMap<>();
        if (jcProperty != null) m.put("jcProperty", jcProperty);
        m.put("sfField", sfField);
        if (defaultValue != null) m.put("defaultValue", defaultValue);
        return m;
    }

    // ── buildFields ───────────────────────────────────────────────────────────

    @Test
    void propertyValueWinsOverDefault() {
        var f = SfdcOutboundSyncService.buildFields(List.of(row("company", "Company", "Web visitor")),
                Map.of("company", "Leroy Merlin"));
        assertEquals("Leroy Merlin", f.values.get("Company"));
        assertEquals(1, f.fromProfile);
    }

    @Test
    void defaultUsedWhenPropertyEmptyOrMissing() {
        Map<String, Object> props = new HashMap<>();
        props.put("company", "  ");
        var f = SfdcOutboundSyncService.buildFields(List.of(
                row("company", "Company", "Web visitor"),
                row("title", "Title", "Visitor")), props);
        assertEquals("Web visitor", f.values.get("Company"));
        assertEquals("Visitor", f.values.get("Title"));
        assertEquals(0, f.fromProfile, "default values do not count as values from the profile");
    }

    @Test
    void rowWithoutPropertyIsAFixedValue() {
        var f = SfdcOutboundSyncService.buildFields(List.of(
                row(null, "LeadSource", "Web"), row("lastName", "LastName", null)), Map.of("lastName", "Nair"));
        assertEquals("Web", f.values.get("LeadSource"));
        assertEquals("Nair", f.values.get("LastName"));
        assertEquals(1, f.fromProfile);
    }

    @Test
    void emptyPropertyWithoutDefaultIsSentEmptyAsBefore() {
        var f = SfdcOutboundSyncService.buildFields(List.of(row("title", "Title", null)), Map.of("title", ""));
        assertEquals("", f.values.get("Title"));
        assertEquals(1, f.fromProfile);
    }

    @Test
    void listsAreJoinedForMultiSelectPicklists() {
        var f = SfdcOutboundSyncService.buildFields(List.of(row("industries", "Industries__c", null)),
                Map.of("industries", List.of("a", "b")));
        assertEquals("a;b", f.values.get("Industries__c"));
    }

    // ── pushProfile with fakes ────────────────────────────────────────────────

    /** Records the Salesforce calls; Lead {@code convertedLeadId} is converted to {@code contactId}. */
    static class FakeSalesforce extends SfdcApiClient {
        final List<String> calls = new ArrayList<>();
        final Map<String, Map<String, Object>> updates = new HashMap<>();
        String convertedLeadId;
        String contactId = "003000000000001AAA";
        String openLeadByEmail;
        boolean convertedByEmail;

        @Override public String findSObjectIdByEmail(String type, String email) {
            calls.add("find " + type);
            return "Lead".equals(type) ? openLeadByEmail : null;
        }
        @Override public void updateSObject(String type, String id, Map<String, Object> fields) {
            calls.add("update " + type + " " + id);
            if (id.equals(convertedLeadId)) throw new RuntimeException("CANNOT_UPDATE_CONVERTED_LEAD");
            updates.put(type + " " + id, new HashMap<>(fields));
        }
        @Override public String createSObject(String type, Map<String, Object> fields) {
            calls.add("create " + type);
            updates.put("create " + type, new HashMap<>(fields));
            return "00Q000000000002AAA";
        }
        @Override public String findConvertedContactId(String leadId) {
            calls.add("converted? " + leadId);
            return leadId.equals(convertedLeadId) ? contactId : null;
        }
        @Override public String findConvertedContactIdByEmail(String email) {
            calls.add("converted by email?");
            return convertedByEmail ? contactId : null;
        }
        @Override public List<Map<String, String>> describeObject(String sobjectName) {
            calls.add("describe " + sobjectName);
            List<Map<String, String>> fields = new ArrayList<>();
            for (String name : new String[]{"FirstName", "LastName", "Email", "Title"}) fields.add(Map.of("name", name));
            return fields;
        }
    }

    static class FakeConfig extends ConnectorConfigService {
        final Map<String, List<Map<String, String>>> outbound = new HashMap<>();
        @Override public Map<String, String> loadOutboundTriggers() { return Map.of("outboundObject", "Lead"); }
        @Override public List<Map<String, String>> loadOutboundMapping(String sfObject) {
            return outbound.getOrDefault(sfObject, List.of());
        }
    }

    static class FakeProfiles extends JCustomerProfileService {
        final Map<String, Object> written = new HashMap<>();
        @Override public boolean updateProfile(String profileId, Map<String, Object> properties, String siteKey) {
            written.putAll(properties);
            return true;
        }
    }

    private final FakeSalesforce sf = new FakeSalesforce();
    private final FakeConfig config = new FakeConfig();
    private final FakeProfiles profiles = new FakeProfiles();
    private final SfdcOutboundSyncService service = new SfdcOutboundSyncService();

    {
        service.setApiClient(sf);
        service.setConfigService(config);
        service.setProfileService(profiles);
        config.outbound.put("Lead", List.of(
                row("firstName", "FirstName", null), row("lastName", "LastName", null),
                row("company", "Company", "Web visitor"), row(null, "LeadSource", "Web")));
    }

    private static Map<String, Object> profile(Map<String, Object> props) {
        Map<String, Object> p = new HashMap<>();
        p.put("itemId", "7661a9f7-3a09-4ac2-88ba-f3a79261b926");
        p.put("properties", props);
        return p;
    }

    private static Map<String, Object> priya() {
        Map<String, Object> props = new HashMap<>();
        props.put("email", "priya@example.com");
        props.put("firstName", "Priya");
        props.put("lastName", "Nair");
        return props;
    }

    @Test
    void defaultCompanyLetsALeadBeCreated() throws Exception {
        assertSame(PushResult.CREATED, service.pushProfile(profile(priya()), "site"));
        Map<String, Object> sent = sf.updates.get("create Lead");
        assertEquals("Web visitor", sent.get("Company"));
        assertEquals("Web", sent.get("LeadSource"));
        assertEquals("00Q000000000002AAA", profiles.written.get("SFDC_LeadId"));
    }

    @Test
    void defaultValuesAloneNeverPush() throws Exception {
        assertSame(PushResult.SKIPPED, service.pushProfile(profile(new HashMap<>(Map.of("email", "x@example.com"))), "site"));
        assertTrue(sf.calls.isEmpty(), "no Salesforce call: " + sf.calls);
    }

    @Test
    void storedConvertedLeadUpdatesItsContactWithTheFieldsContactHas() throws Exception {
        Map<String, Object> props = priya();
        props.put("SFDC_LeadId", "00Q000000000001AAA");
        sf.convertedLeadId = "00Q000000000001AAA";

        assertSame(PushResult.UPDATED, service.pushProfile(profile(props), "site"));
        Map<String, Object> sent = sf.updates.get("Contact " + sf.contactId);
        assertEquals(Map.of("FirstName", "Priya", "LastName", "Nair"), sent,
                "Company and LeadSource are not Contact fields: dropped");
        assertEquals(sf.contactId, profiles.written.get("SFDC_ContactId"));
        assertFalse(sf.calls.contains("create Lead"));
    }

    @Test
    void configuredContactMappingWinsForAConvertedLead() throws Exception {
        config.outbound.put("Contact", List.of(row("lastName", "LastName", null), row(null, "Title", "Customer")));
        sf.convertedByEmail = true;

        assertSame(PushResult.UPDATED, service.pushProfile(profile(priya()), "site"));
        assertEquals(Map.of("LastName", "Nair", "Title", "Customer"), sf.updates.get("Contact " + sf.contactId));
        assertFalse(sf.calls.contains("create Lead"), "a converted person is never created again as a Lead");
        assertFalse(sf.calls.contains("describe Contact"), "the Contact mapping is used, no describe needed");
    }

    @Test
    void failedUpdateOfALeadNotConvertedIsReportedAsIs() {
        Map<String, Object> props = priya();
        props.put("SFDC_LeadId", "00Q000000000003AAA");
        sf.convertedLeadId = "00Q000000000009AAA";      // another Lead: this one is not converted
        SfdcApiClient failing = new FakeSalesforce() {
            @Override public void updateSObject(String type, String id, Map<String, Object> fields) {
                throw new RuntimeException("FIELD_CUSTOM_VALIDATION_EXCEPTION");
            }
        };
        service.setApiClient(failing);
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.pushProfile(profile(props), "site"));
        assertEquals("FIELD_CUSTOM_VALIDATION_EXCEPTION", e.getMessage());
    }
}
