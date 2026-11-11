package org.telegram.ui.Components.Reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ReelsBackendApiUrlTest {

    @Test
    public void preservesHttpPublicBaseUrl() {
        assertEquals("http://10.0.234.148:2401",
                ReelsBackendApi.resolveBaseUrl("http://10.0.234.148:2401/", "10.0.234.148:2401"));
    }

    @Test
    public void preservesHttpsPublicBaseUrl() {
        assertEquals("https://chat.example.com",
                ReelsBackendApi.resolveBaseUrl("https://chat.example.com/", "chat.example.com"));
    }

    @Test
    public void fallsBackToLegacyHttpsLinkPrefix() {
        assertEquals("https://10.0.234.148:2401",
                ReelsBackendApi.resolveBaseUrl("", "10.0.234.148:2401"));
    }

    @Test
    public void returnsNullWithoutAnyBase() {
        assertNull(ReelsBackendApi.resolveBaseUrl("", ""));
    }
}
