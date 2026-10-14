package io.hyperiot.product;

import io.hyperiot.common.PageResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ProductRepository {

    private static final String SELECT = "SELECT p.id, p.product_key, p.name, p.description, p.node_type, p.protocol, "
            + "(SELECT COUNT(*) FROM device d WHERE d.product_key = p.product_key) AS device_count, "
            + "p.created_at, p.updated_at FROM product p ";

    private final JdbcClient jdbc;

    public ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Product map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Product(rs.getLong("id"), rs.getString("product_key"), rs.getString("name"),
                rs.getString("description"), rs.getString("node_type"), rs.getString("protocol"),
                rs.getLong("device_count"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    public long insert(String productKey, String name, String description, String nodeType, String protocol,
                       String thingModelJson, long now) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO product(product_key, name, description, node_type, protocol, thing_model, created_at, updated_at) "
                        + "VALUES (:k, :n, :d, :nt, :p, :tm, :now, :now)")
                .param("k", productKey).param("n", name).param("d", description).param("nt", nodeType)
                .param("p", protocol).param("tm", thingModelJson).param("now", now)
                .update(kh, "id");
        return kh.getKey().longValue();
    }

    public Optional<Product> findById(long id) {
        return jdbc.sql(SELECT + "WHERE p.id = :id").param("id", id).query(ProductRepository::map).optional();
    }

    public Optional<Product> findByKey(String productKey) {
        return jdbc.sql(SELECT + "WHERE p.product_key = :k").param("k", productKey).query(ProductRepository::map).optional();
    }

    public PageResult<Product> page(String keyword, int page, int size) {
        String where = keyword == null || keyword.isBlank() ? "" : "WHERE LOWER(p.name) LIKE :kw OR p.product_key LIKE :kw ";
        var count = jdbc.sql("SELECT COUNT(*) FROM product p " + where);
        var list = jdbc.sql(SELECT + where + "ORDER BY p.id DESC LIMIT :size OFFSET :offset");
        if (!where.isEmpty()) {
            String kw = "%" + keyword.trim().toLowerCase() + "%";
            count = count.param("kw", kw);
            list = list.param("kw", kw);
        }
        long total = count.query(Long.class).single();
        List<Product> items = list.param("size", PageResult.normalizeSize(size))
                .param("offset", PageResult.offset(page, size)).query(ProductRepository::map).list();
        return new PageResult<>(total, items);
    }

    public Optional<String> findThingModel(String productKey) {
        return jdbc.sql("SELECT thing_model FROM product WHERE product_key = :k").param("k", productKey)
                .query(String.class).optional();
    }

    public void update(long id, String name, String description, long now) {
        jdbc.sql("UPDATE product SET name = :n, description = :d, updated_at = :now WHERE id = :id")
                .param("n", name).param("d", description).param("now", now).param("id", id).update();
    }

    public void updateThingModel(long id, String json, long now) {
        jdbc.sql("UPDATE product SET thing_model = :tm, updated_at = :now WHERE id = :id")
                .param("tm", json).param("now", now).param("id", id).update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM product WHERE id = :id").param("id", id).update();
    }
}
