package io.hyperiot.command;

import io.hyperiot.common.Json;
import io.hyperiot.common.PageResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class CommandRepository {

    private static final String COLUMNS = "id, request_id, device_id, product_key, device_name, identifier, params, status, "
            + "response, created_at, sent_at, replied_at, expire_at";

    private final JdbcClient jdbc;

    public CommandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Command map(ResultSet rs, int i) throws SQLException {
        String params = rs.getString("params");
        String response = rs.getString("response");
        return new Command(rs.getLong("id"), rs.getString("request_id"), rs.getLong("device_id"), rs.getString("product_key"),
                rs.getString("device_name"), rs.getString("identifier"), params == null ? null : Json.tree(params),
                rs.getString("status"), response == null ? null : Json.tree(response), rs.getLong("created_at"),
                nullableLong(rs, "sent_at"), nullableLong(rs, "replied_at"), rs.getLong("expire_at"));
    }

    public long insert(String requestId, long deviceId, String productKey, String deviceName, String identifier,
                       String paramsJson, long now, long expireAt) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO command(request_id, device_id, product_key, device_name, identifier, params, status, created_at, expire_at) "
                        + "VALUES (:rid, :d, :pk, :dn, :i, :p, 'PENDING', :now, :exp)")
                .param("rid", requestId).param("d", deviceId).param("pk", productKey).param("dn", deviceName)
                .param("i", identifier).param("p", paramsJson).param("now", now).param("exp", expireAt).update(kh, "id");
        return kh.getKey().longValue();
    }

    public Optional<Command> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM command WHERE id = :id").param("id", id).query(CommandRepository::map).optional();
    }

    public Optional<Command> findByRequestId(String requestId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM command WHERE request_id = :r").param("r", requestId)
                .query(CommandRepository::map).optional();
    }

    /** Only moves PENDING -> SENT, so a concurrent timeout or double delivery cannot be overwritten. */
    public boolean markSent(long id, long now) {
        return jdbc.sql("UPDATE command SET status = 'SENT', sent_at = :now WHERE id = :id AND status = 'PENDING'")
                .param("now", now).param("id", id).update() > 0;
    }

    public void revertToPending(long id) {
        jdbc.sql("UPDATE command SET status = 'PENDING', sent_at = NULL WHERE id = :id AND status = 'SENT'").param("id", id).update();
    }

    public boolean markReplied(long id, String status, String responseJson, long now) {
        return jdbc.sql("UPDATE command SET status = :s, response = :r, replied_at = :now WHERE id = :id AND status IN ('SENT', 'PENDING')")
                .param("s", status).param("r", responseJson).param("now", now).param("id", id).update() > 0;
    }

    public List<Command> pendingFor(long deviceId, long now) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM command WHERE device_id = :d AND status = 'PENDING' AND expire_at > :now ORDER BY id")
                .param("d", deviceId).param("now", now).query(CommandRepository::map).list();
    }

    public List<Command> timedOut(long now, long replyDeadline) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM command WHERE (status = 'SENT' AND sent_at < :deadline) "
                        + "OR (status = 'PENDING' AND expire_at < :now)")
                .param("deadline", replyDeadline).param("now", now).query(CommandRepository::map).list();
    }

    public boolean markTimeout(long id, long now) {
        return jdbc.sql("UPDATE command SET status = 'TIMEOUT', replied_at = :now WHERE id = :id AND status IN ('SENT', 'PENDING')")
                .param("now", now).param("id", id).update() > 0;
    }

    public PageResult<Command> page(Long deviceId, String status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (deviceId != null) {
            where.append(" AND device_id = :d");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = :s");
        }
        var count = jdbc.sql("SELECT COUNT(*) FROM command" + where);
        var list = jdbc.sql("SELECT " + COLUMNS + " FROM command" + where + " ORDER BY id DESC LIMIT :size OFFSET :offset");
        if (deviceId != null) {
            count = count.param("d", deviceId);
            list = list.param("d", deviceId);
        }
        if (status != null && !status.isBlank()) {
            count = count.param("s", status.toUpperCase());
            list = list.param("s", status.toUpperCase());
        }
        long total = count.query(Long.class).single();
        List<Command> items = list.param("size", PageResult.normalizeSize(size)).param("offset", PageResult.offset(page, size))
                .query(CommandRepository::map).list();
        return new PageResult<>(total, items);
    }
}
