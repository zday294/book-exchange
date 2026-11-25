package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookexchange.cover.CoverProperties;
import com.bookexchange.link.LinkCheckProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The safety net for every other test: a Spring test that says nothing about it must not be able to reach the stores
 * (it would get real 404s for the sample links and fail for a confusing reason). See src/test/resources/application.properties.
 */
class NoInternetByDefaultTest extends AbstractIntegrationTest {

    @Autowired LinkCheckProperties linkCheck;
    @Autowired CoverProperties cover;

    @Test
    void theLinkCheckAndTheCoverLookupAreOffUnlessATestTurnsThemOn() {
        assertThat(linkCheck.enabled()).isFalse();
        assertThat(cover.enabled()).isFalse();
    }
}
