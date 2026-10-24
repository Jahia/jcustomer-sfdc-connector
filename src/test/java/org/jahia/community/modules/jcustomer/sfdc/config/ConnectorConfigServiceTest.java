package org.jahia.community.modules.jcustomer.sfdc.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A mapping is read and saved only for the Salesforce objects its direction maps. */
class ConnectorConfigServiceTest {

    @Test
    void acceptsTheObjectsOfEachDirection() {
        for (String o : new String[]{"Contact", "Account", "Lead"}) assertEquals(o, ConnectorConfigService.requireMappedObject(false, o));
        for (String o : new String[]{"Lead", "Contact"}) assertEquals(o, ConnectorConfigService.requireMappedObject(true, o));
    }

    @Test
    void refusesAnyOtherValue() {
        for (String o : new String[]{null, "", "Opportunity", "contact", "LEAD", "Account "}) {
            assertThrows(IllegalArgumentException.class, () -> ConnectorConfigService.requireMappedObject(false, o), String.valueOf(o));
        }
        assertThrows(IllegalArgumentException.class, () -> ConnectorConfigService.requireMappedObject(true, "Account"),
                "Account is mapped inbound only");
    }
}
