package io.hyperiot.alarm;

import io.hyperiot.common.ApiException;
import io.hyperiot.common.PageResult;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Service
public class AlarmService {

    /** Published whenever an alarm is raised, acknowledged or recovered. */
    public record AlarmChanged(Alarm alarm) {
    }

    private static final String COLUMNS = "id, rule_id, rule_name, device_id, product_key, device_name, level, status, message, "
            + "trigger_value, triggered_at, recovered_at, acked_at";

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    public AlarmService(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Alarm map(ResultSet rs, int i) throws SQLException {
        return new Alarm(rs.getLong("id"), rs.getLong("rule_id"), rs.getString("rule_name"), rs.getLong("device_id"),
                rs.getString("product_key"), rs.getString("device_name"), rs.getString("level"), rs.getString("status"),
                rs.getString("message"), rs.getString("trigger_value"), rs.getLong("triggered_at"),
                nullableLong(rs, "recovered_at"), nullableLong(rs, "acked_at"));
    }

    public Optional<Alarm> findActive(long ruleId, long deviceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM alarm WHERE rule_id = :r AND device_id = :d AND status IN ('ACTIVE', 'ACKED')")
                .param("r", ruleId).param("d", deviceId).query(AlarmService::map).optional();
    }

    /**
     * Raises an alarm unless the same rule already has an active alarm on that device.
     *
     * @return the new alarm, or empty if one was already active
     */
    public Optional<Alarm> raise(long ruleId, String ruleName, long deviceId, String productKey, String deviceName,
                                 String level, String message, String triggerValue) {
        if (findActive(ruleId, deviceId).isPresent()) {
            return Optional.empty();
        }
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO alarm(rule_id, rule_name, device_id, product_key, device_name, level, status, message, trigger_value, triggered_at) "
                        + "VALUES (:r, :rn, :d, :pk, :dn, :l, 'ACTIVE', :m, :tv, :now)")
                .param("r", ruleId).param("rn", ruleName).param("d", deviceId).param("pk", productKey).param("dn", deviceName)
                .param("l", level).param("m", truncate(message, 500)).param("tv", truncate(triggerValue, 250))
                .param("now", System.currentTimeMillis()).update(kh, "id");
        Alarm a = get(kh.getKey().longValue());
        events.publishEvent(new AlarmChanged(a));
        return Optional.of(a);
    }

    /** Marks the active alarm of this rule/device as recovered, if there is one. */
    public void recover(long ruleId, long deviceId) {
        findActive(ruleId, deviceId).ifPresent(a -> {
            jdbc.sql("UPDATE alarm SET status = 'RECOVERED', recovered_at = :now WHERE id = :id")
                    .param("now", System.currentTimeMillis()).param("id", a.id()).update();
            events.publishEvent(new AlarmChanged(get(a.id())));
        });
    }

    public void ack(long id) {
        Alarm a = get(id);
        if (Alarm.RECOVERED.equals(a.status())) {
            throw ApiException.conflict("告警已恢复");
        }
        jdbc.sql("UPDATE alarm SET status = 'ACKED', acked_at = :now WHERE id = :id AND status = 'ACTIVE'")
                .param("now", System.currentTimeMillis()).param("id", id).update();
        events.publishEvent(new AlarmChanged(get(id)));
    }

    public Alarm get(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM alarm WHERE id = :id").param("id", id).query(AlarmService::map).optional()
                .orElseThrow(() -> ApiException.notFound("告警不存在"));
    }

    public PageResult<Alarm> page(String status, String level, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null && !status.isBlank()) {
            where.append(" AND status = :s");
        }
        if (level != null && !level.isBlank()) {
            where.append(" AND level = :l");
        }
        var count = jdbc.sql("SELECT COUNT(*) FROM alarm" + where);
        var list = jdbc.sql("SELECT " + COLUMNS + " FROM alarm" + where + " ORDER BY id DESC LIMIT :size OFFSET :offset");
        if (status != null && !status.isBlank()) {
            count = count.param("s", status.toUpperCase());
            list = list.param("s", status.toUpperCase());
        }
        if (level != null && !level.isBlank()) {
            count = count.param("l", level.toUpperCase());
            list = list.param("l", level.toUpperCase());
        }
        long total = count.query(Long.class).single();
        List<Alarm> items = list.param("size", PageResult.normalizeSize(size)).param("offset", PageResult.offset(page, size))
                .query(AlarmService::map).list();
        return new PageResult<>(total, items);
    }

    public List<Alarm> recent(int n) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM alarm ORDER BY id DESC LIMIT :n").param("n", n).query(AlarmService::map).list();
    }

    public long countActive() {
        return jdbc.sql("SELECT COUNT(*) FROM alarm WHERE status IN ('ACTIVE', 'ACKED')").query(Long.class).single();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
