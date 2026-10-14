package io.hyperiot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** The "prod" profile must switch on the whole production stack: PostgreSQL, Kafka, EMQX and InfluxDB. */
class ProdProfileTest {

    @Test
    void prodProfileExpandsToTheProductionStack() {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("prod");
        ConfigDataEnvironmentPostProcessor.applyTo(env);

        assertThat(env.getActiveProfiles()).contains("prod", "postgres", "kafka", "emqx", "influx");
        assertThat(env.getProperty("hyperiot.bus.type")).isEqualTo("kafka");
        assertThat(env.getProperty("hyperiot.mqtt.mode")).isEqualTo("external");
        assertThat(env.getProperty("hyperiot.timeseries.type")).isEqualTo("influx");
        assertThat(env.getProperty("spring.datasource.url")).startsWith("jdbc:postgresql:");
    }

    @Test
    void defaultProfileStaysStandalone() {
        StandardEnvironment env = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(env);

        assertThat(env.getProperty("hyperiot.bus.type")).isEqualTo("memory");
        assertThat(env.getProperty("hyperiot.mqtt.mode")).isEqualTo("embedded");
        assertThat(env.getProperty("hyperiot.timeseries.type")).isEqualTo("jdbc");
        assertThat(env.getProperty("spring.datasource.url")).startsWith("jdbc:h2:");
    }
}
