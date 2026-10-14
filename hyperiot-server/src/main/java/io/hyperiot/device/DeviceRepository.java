package io.hyperiot.device;

import com.fasterxml.jackson.core.type.TypeReference;
import io.hyperiot.common.Json;
import io.hyperiot.common.PageResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class DeviceRepository {

    private static final String COLUMNS = "id, product_key, device_name, nickname, device_secret, status, enabled, "
            + "firmware_version, ip, last_online_at, last_offline_at, last_seen_at, tags, created_at";

    private final JdbcClient jdbc;

    public DeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Device map(ResultSet rs, int i) throws SQLException {
        String tagsJson = rs.getString("tags");
        Map<String, String> tags = tagsJson == null || tagsJson.isBlank() ? Map.of()
                : Json.read(tagsJson, new TypeReference<Map<String, String>>() { });
        return new Device(rs.getLong("id"), rs.getString("product_key"), rs.getString("device_name"),
                rs.getString("nickname"), rs.getString("status"), rs.getBoolean("enabled"),
                rs.getString("firmware_version"), rs.getString("ip"), nullableLong(rs, "last_online_at"),
                nullableLong(rs, "last_offline_at"), nullableLong(rs, "last_seen_at"), tags,
                rs.getLong("created_at"), rs.getString("device_secret"));
    }

    public long insert(String productKey, String deviceName, String nickname, String secret, Map<String, String> tags, long now) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO device(product_key, device_name, nickname, device_secret, status, enabled, tags, created_at) "
                        + "VALUES (:pk, :dn, :nick, :secret, 'INACTIVE', TRUE, :tags, :now)")
                .param("pk", productKey).param("dn", deviceName).param("nick", nickname).param("secret", secret)
                .param("tags", tags == null || tags.isEmpty() ? null : Json.write(tags)).param("now", now)
                .update(kh, "id");
        return kh.getKey().longValue();
    }

    public Optional<Device> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM device WHERE id = :id").param("id", id)
                .query(DeviceRepository::map).optional();
    }

    public Optional<Device> find(String productKey, String deviceName) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM device WHERE product_key = :pk AND device_name = :dn")
                .param("pk", productKey).param("dn", deviceName).query(DeviceRepository::map).optional();
    }

    public List<Device> findByProduct(String productKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM device WHERE product_key = :pk ORDER BY id")
                .param("pk", productKey).query(DeviceRepository::map).list();
    }

    public List<Device> findByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM device WHERE id IN (:ids) ORDER BY id")
                .param("ids", ids).query(DeviceRepository::map).list();
    }

    public PageResult<Device> page(String productKey, String status, String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object[]> params = new ArrayList<>();
        if (productKey != null && !productKey.isBlank()) {
            where.append(" AND product_key = :pk");
            params.add(new Object[]{"pk", productKey});
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = :status");
            params.add(new Object[]{"status", status.toUpperCase()});
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (LOWER(device_name) LIKE :kw OR LOWER(nickname) LIKE :kw)");
            params.add(new Object[]{"kw", "%" + keyword.trim().toLowerCase() + "%"});
        }
        var count = jdbc.sql("SELECT COUNT(*) FROM device" + where);
        var list = jdbc.sql("SELECT " + COLUMNS + " FROM device" + where + " ORDER BY id DESC LIMIT :size OFFSET :offset");
        for (Object[] p : params) {
            count = count.param((String) p[0], p[1]);
            list = list.param((String) p[0], p[1]);
        }
        long total = count.query(Long.class).single();
        List<Device> items = list.param("size", PageResult.normalizeSize(size))
                .param("offset", PageResult.offset(page, size)).query(DeviceRepository::map).list();
        return new PageResult<>(total, items);
    }

    public void update(long id, String nickname, Map<String, String> tags, boolean enabled) {
        jdbc.sql("UPDATE device SET nickname = :nick, tags = :tags, enabled = :en WHERE id = :id")
                .param("nick", nickname).param("tags", tags == null || tags.isEmpty() ? null : Json.write(tags))
                .param("en", enabled).param("id", id).update();
    }

    public void updateSecret(long id, String secret) {
        jdbc.sql("UPDATE device SET device_secret = :s WHERE id = :id").param("s", secret).param("id", id).update();
    }

    public void markOnline(long id, String ip, long now) {
        jdbc.sql("UPDATE device SET status = 'ONLINE', ip = :ip, last_online_at = :now, last_seen_at = :now WHERE id = :id")
                .param("ip", ip).param("now", now).param("id", id).update();
    }

    public void markOffline(long id, long now) {
        jdbc.sql("UPDATE device SET status = 'OFFLINE', last_offline_at = :now WHERE id = :id")
                .param("now", now).param("id", id).update();
    }

    /** At service start nobody is connected: everything that was ONLINE is now unknown, reset to OFFLINE. */
    public int markAllOffline(long now) {
        return jdbc.sql("UPDATE device SET status = 'OFFLINE', last_offline_at = :now WHERE status = 'ONLINE'")
                .param("now", now).update();
    }

    public void updateLastSeen(long id, long ts) {
        jdbc.sql("UPDATE device SET last_seen_at = :ts WHERE id = :id AND (last_seen_at IS NULL OR last_seen_at < :ts)")
                .param("ts", ts).param("id", id).update();
    }

    public void updateFirmwareVersion(long id, String version) {
        jdbc.sql("UPDATE device SET firmware_version = :v WHERE id = :id").param("v", version).param("id", id).update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM device_shadow WHERE device_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM device_event WHERE device_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM device_log WHERE device_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM command WHERE device_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM alarm WHERE device_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM ota_task_device WHERE device_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM device WHERE id = :id").param("id", id).update();
    }

    public long countAll() {
        return jdbc.sql("SELECT COUNT(*) FROM device").query(Long.class).single();
    }

    public long countByStatus(String status) {
        return jdbc.sql("SELECT COUNT(*) FROM device WHERE status = :s").param("s", status).query(Long.class).single();
    }
}
