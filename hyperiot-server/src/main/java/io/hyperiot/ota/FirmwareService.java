package io.hyperiot.ota;

import io.hyperiot.common.ApiException;
import io.hyperiot.common.PageResult;
import io.hyperiot.config.HyperIotProperties;
import io.hyperiot.product.ProductService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/** Firmware files are stored content-addressed (named by SHA-256) in a local directory. */
@Service
public class FirmwareService {

    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final String COLUMNS = "id, product_key, version, name, description, size, sha256, created_at";

    private final JdbcClient jdbc;
    private final ProductService products;
    private final HyperIotProperties.Ota cfg;

    public FirmwareService(JdbcClient jdbc, ProductService products, HyperIotProperties props) {
        this.jdbc = jdbc;
        this.products = products;
        this.cfg = props.getOta();
    }

    private static Firmware map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Firmware(rs.getLong("id"), rs.getString("product_key"), rs.getString("version"), rs.getString("name"),
                rs.getString("description"), rs.getLong("size"), rs.getString("sha256"), rs.getLong("created_at"));
    }

    public Firmware upload(String productKey, String version, String name, String description, MultipartFile file) {
        products.getByKey(productKey);
        if (version == null || !VERSION.matcher(version).matches()) {
            throw ApiException.badRequest("版本号只能包含字母、数字和 . _ -，长度 1~64");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请选择固件文件");
        }
        if (file.getSize() > cfg.getMaxSizeBytes()) {
            throw ApiException.badRequest("固件超过大小限制（" + cfg.getMaxSizeBytes() / 1024 / 1024 + " MB）");
        }
        Path dir = Path.of(cfg.getDirectory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "upload-", ".tmp");
            String sha;
            try (InputStream in = file.getInputStream()) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (DigestInputStream din = new DigestInputStream(in, md)) {
                    Files.copy(din, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                sha = HexFormat.of().formatHex(md.digest());
            } catch (Exception e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            Path target = dir.resolve(sha + ".bin");
            if (Files.exists(target)) {
                Files.delete(tmp);
            } else {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            }
            KeyHolder kh = new GeneratedKeyHolder();
            try {
                jdbc.sql("INSERT INTO firmware(product_key, version, name, description, file_name, size, sha256, created_at) "
                                + "VALUES (:pk, :v, :n, :d, :f, :s, :sha, :now)")
                        .param("pk", productKey).param("v", version).param("n", name).param("d", description)
                        .param("f", sha + ".bin").param("s", file.getSize()).param("sha", sha)
                        .param("now", System.currentTimeMillis()).update(kh, "id");
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("该产品已存在版本 " + version);
            }
            return get(kh.getKey().longValue());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store firmware", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Firmware get(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM firmware WHERE id = :id").param("id", id).query(FirmwareService::map).optional()
                .orElseThrow(() -> ApiException.notFound("固件不存在"));
    }

    public PageResult<Firmware> page(String productKey, int page, int size) {
        boolean filter = productKey != null && !productKey.isBlank();
        var count = jdbc.sql("SELECT COUNT(*) FROM firmware" + (filter ? " WHERE product_key = :pk" : ""));
        var list = jdbc.sql("SELECT " + COLUMNS + " FROM firmware" + (filter ? " WHERE product_key = :pk" : "")
                + " ORDER BY id DESC LIMIT :size OFFSET :offset");
        if (filter) {
            count = count.param("pk", productKey);
            list = list.param("pk", productKey);
        }
        long total = count.query(Long.class).single();
        List<Firmware> items = list.param("size", PageResult.normalizeSize(size)).param("offset", PageResult.offset(page, size))
                .query(FirmwareService::map).list();
        return new PageResult<>(total, items);
    }

    public void delete(long id) {
        Firmware f = get(id);
        long used = jdbc.sql("SELECT COUNT(*) FROM ota_task WHERE firmware_id = :id").param("id", id).query(Long.class).single();
        if (used > 0) {
            throw ApiException.conflict("固件已被 " + used + " 个升级任务引用，不能删除");
        }
        jdbc.sql("DELETE FROM firmware WHERE id = :id").param("id", id).update();
        long shared = jdbc.sql("SELECT COUNT(*) FROM firmware WHERE sha256 = :s").param("s", f.sha256()).query(Long.class).single();
        if (shared == 0) {
            try {
                Files.deleteIfExists(file(f));
            } catch (IOException ignored) {
                // orphaned file is harmless
            }
        }
    }

    /** Path of the stored file; the name is the SHA-256 hex we computed, so it cannot escape the directory. */
    public Path file(Firmware f) {
        return Path.of(cfg.getDirectory()).toAbsolutePath().normalize().resolve(f.sha256() + ".bin");
    }
}
