package io.hyperiot;

import io.hyperiot.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorHandlingIT extends IntegrationTestBase {

    @Test
    void missingStaticFilesAreAPlain404NotAServerError() {
        ResponseEntity<String> r = rest.getForEntity("/.well-known/appspecific/com.chrome.devtools.json", String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(r.getBody()).contains("NOT_FOUND");
    }
}
