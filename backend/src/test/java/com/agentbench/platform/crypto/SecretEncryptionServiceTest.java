package com.agentbench.platform.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SecretEncryptionServiceTest {

    @Test
    void roundTripEncryptDecrypt() {
        SecretEncryptionService service = new SecretEncryptionService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        byte[] cipher = service.encrypt("ghp_test_token_value");
        assertNotEquals("ghp_test_token_value", new String(cipher));
        assertEquals("ghp_test_token_value", service.decrypt(cipher));
    }
}
