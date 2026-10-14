package io.hyperiot.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.hyperiot.common.ApiException;
import io.hyperiot.common.Hmac;
import io.hyperiot.config.HyperIotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AuthService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK_WINDOW = Duration.ofMinutes(5);

    public record Token(String token, long expiresIn, String username) {
    }

    private final JdbcClient jdbc;
    private final JwtEncoder encoder;
    private final HyperIotProperties.Security cfg;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final String dummyHash = passwords.encode("not-a-real-password");
    /** Failed login counter per username and per client address; expires after the lock window. */
    private final Cache<String, AtomicInteger> failures = Caffeine.newBuilder().expireAfterWrite(LOCK_WINDOW).maximumSize(100_000).build();

    public AuthService(JdbcClient jdbc, JwtEncoder encoder, HyperIotProperties props) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.cfg = props.getSecurity();
    }

    /** Creates the first admin account when the user table is empty. */
    @Override
    public void run(ApplicationArguments args) {
        long users = jdbc.sql("SELECT COUNT(*) FROM sys_user").query(Long.class).single();
        if (users > 0) {
            return;
        }
        String password = cfg.getAdminPassword();
        boolean generated = password == null || password.isBlank();
        if (generated) {
            password = Hmac.randomString(16);
        } else if (password.length() < 8) {
            throw new IllegalStateException("hyperiot.security.admin-password must be at least 8 characters");
        }
        jdbc.sql("INSERT INTO sys_user(username, password_hash, role, created_at) VALUES (:u, :p, 'ADMIN', :now)")
                .param("u", cfg.getAdminUsername()).param("p", passwords.encode(password)).param("now", System.currentTimeMillis()).update();
        if (generated) {
            log.warn("""

                    ============================================================
                     Created initial admin account '{}' with a generated password:
                       {}
                     Change it after the first login, or set HYPERIOT_ADMIN_PASSWORD before the first start.
                    ============================================================""", cfg.getAdminUsername(), password);
        } else {
            log.info("Created initial admin account '{}'", cfg.getAdminUsername());
        }
    }

    public Token login(String username, String password, String clientAddress) {
        String userKey = "u:" + username;
        String addrKey = "a:" + clientAddress;
        if (count(userKey) >= MAX_FAILURES || count(addrKey) >= MAX_FAILURES * 4) {
            throw ApiException.tooMany("登录失败次数过多，请 5 分钟后再试");
        }
        Optional<String> hash = jdbc.sql("SELECT password_hash FROM sys_user WHERE username = :u").param("u", username)
                .query(String.class).optional();
        // always run one BCrypt comparison so that response time does not reveal whether the user exists
        boolean ok = passwords.matches(password, hash.orElse(dummyHash)) && hash.isPresent();
        if (!ok) {
            failures.get(userKey, k -> new AtomicInteger()).incrementAndGet();
            failures.get(addrKey, k -> new AtomicInteger()).incrementAndGet();
            throw ApiException.unauthorized("用户名或密码错误");
        }
        failures.invalidate(userKey);
        return issue(username);
    }

    private int count(String key) {
        AtomicInteger n = failures.getIfPresent(key);
        return n == null ? 0 : n.get();
    }

    private Token issue(String username) {
        Instant now = Instant.now();
        Duration ttl = cfg.getJwtTtl();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(username).issuedAt(now).expiresAt(now.plus(ttl))
                .claim("role", "ADMIN").build();
        String jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new Token(jwt, ttl.toSeconds(), username);
    }

    public void changePassword(String username, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw ApiException.badRequest("新密码至少 8 位");
        }
        String hash = jdbc.sql("SELECT password_hash FROM sys_user WHERE username = :u").param("u", username)
                .query(String.class).optional().orElseThrow(() -> ApiException.unauthorized("用户不存在"));
        if (!passwords.matches(oldPassword == null ? "" : oldPassword, hash)) {
            throw ApiException.badRequest("原密码不正确");
        }
        jdbc.sql("UPDATE sys_user SET password_hash = :p WHERE username = :u").param("p", passwords.encode(newPassword))
                .param("u", username).update();
    }
}
