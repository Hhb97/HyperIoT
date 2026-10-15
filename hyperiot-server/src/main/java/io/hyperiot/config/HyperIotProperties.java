package io.hyperiot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * All platform settings live under the {@code hyperiot.*} prefix, see application.yml.
 */
@ConfigurationProperties(prefix = "hyperiot")
public class HyperIotProperties {

    private final Mqtt mqtt = new Mqtt();
    private final Bus bus = new Bus();
    private final Security security = new Security();
    private final Tcp tcp = new Tcp();
    private final Model model = new Model();
    private final Telemetry telemetry = new Telemetry();
    private final Command command = new Command();
    private final Webhook webhook = new Webhook();
    private final Ota ota = new Ota();
    private final Access access = new Access();
    private final Timeseries timeseries = new Timeseries();
    private String publicBaseUrl = "http://localhost:8080";

    public Mqtt getMqtt() { return mqtt; }
    public Bus getBus() { return bus; }
    public Security getSecurity() { return security; }
    public Tcp getTcp() { return tcp; }
    public Model getModel() { return model; }
    public Telemetry getTelemetry() { return telemetry; }
    public Command getCommand() { return command; }
    public Webhook getWebhook() { return webhook; }
    public Ota getOta() { return ota; }
    public Access getAccess() { return access; }
    public Timeseries getTimeseries() { return timeseries; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }

    public static class Mqtt {
        /** embedded: built-in broker (standalone); external: connect to EMQX or any MQTT 3.1.1 broker. */
        private String mode = "embedded";
        private String host = "0.0.0.0";
        private int port = 1883;
        private int maxMessageBytes = 65536;
        /** external mode */
        private String brokerUrl = "tcp://localhost:1883";
        private String username = "hyperiot-platform";
        private String password = "";
        private String clientId = "hyperiot-server";
        /** Use a shared subscription so several platform nodes split the uplink traffic. */
        private String subscription = "$share/hyperiot/hyperiot/#";

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public int getMaxMessageBytes() { return maxMessageBytes; }
        public void setMaxMessageBytes(int maxMessageBytes) { this.maxMessageBytes = maxMessageBytes; }
        public String getBrokerUrl() { return brokerUrl; }
        public void setBrokerUrl(String brokerUrl) { this.brokerUrl = brokerUrl; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        /** Threads that process uplink messages and device connect / disconnect events from the external broker. */
        private int workers = 8;
        /** Messages each worker may have waiting; beyond that messages are dropped (and counted in the log). */
        private int workerQueue = 20_000;

        public int getWorkers() { return workers; }
        public void setWorkers(int workers) { this.workers = workers; }
        public int getWorkerQueue() { return workerQueue; }
        public void setWorkerQueue(int workerQueue) { this.workerQueue = workerQueue; }
        public String getSubscription() { return subscription; }
        public void setSubscription(String subscription) { this.subscription = subscription; }
        public boolean isEmbedded() { return "embedded".equalsIgnoreCase(mode); }

        private final EmqxApi emqxApi = new EmqxApi();

        public EmqxApi getEmqxApi() { return emqxApi; }

        /**
         * Optional: EMQX's REST API (an API key created under Management -> API Keys). When set, the platform compares the
         * clients EMQX reports as connected with the online state in its database, which repairs state after missed
         * connect / disconnect events (for example while the platform's own MQTT connection was down).
         */
        public static class EmqxApi {
            private String url = "";
            private String key = "";
            private String secret = "";
            private Duration interval = Duration.ofSeconds(60);

            public String getUrl() { return url; }
            public void setUrl(String url) { this.url = url; }
            public String getKey() { return key; }
            public void setKey(String key) { this.key = key; }
            public String getSecret() { return secret; }
            public void setSecret(String secret) { this.secret = secret; }
            public Duration getInterval() { return interval; }
            public void setInterval(Duration interval) { this.interval = interval; }
            public boolean isConfigured() { return url != null && !url.isBlank(); }
        }
    }

    public static class Bus {
        /** memory | kafka */
        private String type = "memory";
        private int partitions = 4;
        private int queueSize = 20000;
        private String bootstrapServers = "localhost:9092";
        private String topicPrefix = "hyperiot.";
        private String groupPrefix = "hyperiot-";

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public int getPartitions() { return partitions; }
        public void setPartitions(int partitions) { this.partitions = partitions; }
        public int getQueueSize() { return queueSize; }
        public void setQueueSize(int queueSize) { this.queueSize = queueSize; }
        public String getBootstrapServers() { return bootstrapServers; }
        public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }
        public String getTopicPrefix() { return topicPrefix; }
        public void setTopicPrefix(String topicPrefix) { this.topicPrefix = topicPrefix; }
        public String getGroupPrefix() { return groupPrefix; }
        public void setGroupPrefix(String groupPrefix) { this.groupPrefix = groupPrefix; }
    }

    public static class Security {
        private String adminUsername = "admin";
        /** Empty: a random password is generated on first start and printed once in the log. */
        private String adminPassword = "";
        /** Empty: a random key is generated at every start (tokens are invalidated by a restart). */
        private String jwtSecret = "";
        private Duration jwtTtl = Duration.ofHours(8);
        /** Shared secret EMQX must send in the X-Hook-Token header when it calls /api/mqtt/**. */
        private String hookToken = "";
        /**
         * Accept the device secret itself as MQTT password (simplest for devices, but only safe over TLS).
         * The HMAC signature of the secret is accepted either way.
         */
        private boolean allowPlainDevicePassword = true;

        public String getAdminUsername() { return adminUsername; }
        public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
        public String getAdminPassword() { return adminPassword; }
        public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
        public Duration getJwtTtl() { return jwtTtl; }
        public void setJwtTtl(Duration jwtTtl) { this.jwtTtl = jwtTtl; }
        public boolean isAllowPlainDevicePassword() { return allowPlainDevicePassword; }
        public void setAllowPlainDevicePassword(boolean allowPlainDevicePassword) { this.allowPlainDevicePassword = allowPlainDevicePassword; }
        public String getHookToken() { return hookToken; }
        public void setHookToken(String hookToken) { this.hookToken = hookToken; }
    }

    public static class Tcp {
        private boolean enabled = false;
        private int port = 9100;
        private Duration idleTimeout = Duration.ofMinutes(3);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public Duration getIdleTimeout() { return idleTimeout; }
        public void setIdleTimeout(Duration idleTimeout) { this.idleTimeout = idleTimeout; }
    }

    public static class Model {
        /** strict: reject properties that are not declared in the thing model. */
        private boolean strict = true;

        public boolean isStrict() { return strict; }
        public void setStrict(boolean strict) { this.strict = strict; }
    }

    public static class Telemetry {
        private int retentionDays = 30;
        private int batchSize = 500;
        private Duration flushInterval = Duration.ofMillis(500);

        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public Duration getFlushInterval() { return flushInterval; }
        public void setFlushInterval(Duration flushInterval) { this.flushInterval = flushInterval; }
    }

    public static class Command {
        /** How long a command may wait for the device to reply after it was sent. */
        private Duration replyTimeout = Duration.ofSeconds(30);
        /** Default time-to-live of a command that could not be delivered because the device is offline. */
        private Duration defaultTtl = Duration.ofMinutes(5);
        private Duration maxTtl = Duration.ofDays(7);

        public Duration getReplyTimeout() { return replyTimeout; }
        public void setReplyTimeout(Duration replyTimeout) { this.replyTimeout = replyTimeout; }
        public Duration getDefaultTtl() { return defaultTtl; }
        public void setDefaultTtl(Duration defaultTtl) { this.defaultTtl = defaultTtl; }
        public Duration getMaxTtl() { return maxTtl; }
        public void setMaxTtl(Duration maxTtl) { this.maxTtl = maxTtl; }
    }

    public static class Webhook {
        /** Rule webhooks to loopback / private / link-local addresses are blocked unless this is true. */
        private boolean allowPrivateAddresses = false;
        private Duration timeout = Duration.ofSeconds(5);

        public boolean isAllowPrivateAddresses() { return allowPrivateAddresses; }
        public void setAllowPrivateAddresses(boolean allowPrivateAddresses) { this.allowPrivateAddresses = allowPrivateAddresses; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    public static class Ota {
        private String directory = "./data/firmware";
        private long maxSizeBytes = 64L * 1024 * 1024;

        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
        public long getMaxSizeBytes() { return maxSizeBytes; }
        public void setMaxSizeBytes(long maxSizeBytes) { this.maxSizeBytes = maxSizeBytes; }
    }

    public static class Access {
        /** Per-device uplink limit (messages per second, burst = 2x). 0 disables the limit. */
        private int rateLimitPerSecond = 50;
        /**
         * MQTT devices usually subscribe to their downlink topics right after CONNECT. Everything that is delivered when a
         * device "comes online" (queued commands, desired state, OTA instructions) is held back this long so that it is not
         * published before the subscription exists, which the broker would silently drop.
         */
        private Duration onlineAnnounceDelay = Duration.ofSeconds(1);

        public Duration getOnlineAnnounceDelay() { return onlineAnnounceDelay; }
        public void setOnlineAnnounceDelay(Duration onlineAnnounceDelay) { this.onlineAnnounceDelay = onlineAnnounceDelay; }
        public int getRateLimitPerSecond() { return rateLimitPerSecond; }
        public void setRateLimitPerSecond(int rateLimitPerSecond) { this.rateLimitPerSecond = rateLimitPerSecond; }
    }

    public static class Timeseries {
        /** jdbc: telemetry table in the relational database; influx: InfluxDB 1.x. */
        private String type = "jdbc";
        private final Influx influx = new Influx();

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Influx getInflux() { return influx; }

        public static class Influx {
            private String url = "http://localhost:8086";
            private String database = "hyperiot";
            private String username = "";
            private String password = "";
            /** Name of the retention policy that is created (and made the default) on startup. */
            private String retentionPolicy = "hyperiot_rp";
            /** Create the database and retention policy at startup (needs an admin account). */
            private boolean autoCreate = true;
            private Duration timeout = Duration.ofSeconds(10);

            public String getUrl() { return url; }
            public void setUrl(String url) { this.url = url; }
            public String getDatabase() { return database; }
            public void setDatabase(String database) { this.database = database; }
            public String getUsername() { return username; }
            public void setUsername(String username) { this.username = username; }
            public String getPassword() { return password; }
            public void setPassword(String password) { this.password = password; }
            public String getRetentionPolicy() { return retentionPolicy; }
            public void setRetentionPolicy(String retentionPolicy) { this.retentionPolicy = retentionPolicy; }
            public boolean isAutoCreate() { return autoCreate; }
            public void setAutoCreate(boolean autoCreate) { this.autoCreate = autoCreate; }
            public Duration getTimeout() { return timeout; }
            public void setTimeout(Duration timeout) { this.timeout = timeout; }
        }
    }
}
