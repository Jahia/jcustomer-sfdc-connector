package org.jahia.community.modules.jcustomer.sfdc.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Salesforce record ids read from profiles are checked before they reach a URL or a SOQL query. */
class SfdcApiClientTest {

    @Test
    void acceptsFifteenAndEighteenCharacterIds() {
        assertEquals("00QTE00000drbHW", SfdcApiClient.requireSObjectId("00QTE00000drbHW"));
        assertEquals("00QTE00000drbHW2AY", SfdcApiClient.requireSObjectId("00QTE00000drbHW2AY"));
    }

    @Test
    void refusesAnythingElse() {
        for (String bad : new String[]{null, "", "00QTE00000drbHW2", "../limits", "00QTE00000drbHW2AY'", "00QTE00000drbHW2AY/x"}) {
            assertThrows(IllegalArgumentException.class, () -> SfdcApiClient.requireSObjectId(bad), String.valueOf(bad));
        }
    }
}
