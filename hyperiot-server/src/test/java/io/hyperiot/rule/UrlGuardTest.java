package io.hyperiot.rule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlGuardTest {

    @Test
    void blocksInternalDestinationsByDefault() {
        assertThat(UrlGuard.check("http://127.0.0.1:8080/x", false)).isNotNull();
        assertThat(UrlGuard.check("http://localhost/x", false)).isNotNull();
        assertThat(UrlGuard.check("http://10.1.2.3/x", false)).isNotNull();
        assertThat(UrlGuard.check("http://192.168.0.10/x", false)).isNotNull();
        assertThat(UrlGuard.check("http://172.16.5.5/x", false)).isNotNull();
        assertThat(UrlGuard.check("http://169.254.169.254/latest/meta-data", false)).isNotNull();
        assertThat(UrlGuard.check("http://[::1]/x", false)).isNotNull();
        assertThat(UrlGuard.check("http://0.0.0.0/x", false)).isNotNull();
    }

    @Test
    void allowsPublicAddresses() {
        assertThat(UrlGuard.check("https://93.184.216.34/hook", false)).isNull();
        assertThat(UrlGuard.check("http://8.8.8.8/hook", false)).isNull();
    }

    @Test
    void rejectsOtherSchemesAndOddUrls() {
        assertThat(UrlGuard.check("file:///etc/passwd", true)).isNotNull();
        assertThat(UrlGuard.check("ftp://example.com/x", true)).isNotNull();
        assertThat(UrlGuard.check("gopher://example.com/x", true)).isNotNull();
        assertThat(UrlGuard.check("http://user:pass@93.184.216.34/x", true)).isNotNull();
        assertThat(UrlGuard.check("not a url", true)).isNotNull();
        assertThat(UrlGuard.check("http:///nohost", true)).isNotNull();
    }

    @Test
    void privateAddressesCanBeAllowedExplicitly() {
        assertThat(UrlGuard.check("http://127.0.0.1:9000/x", true)).isNull();
    }
}
