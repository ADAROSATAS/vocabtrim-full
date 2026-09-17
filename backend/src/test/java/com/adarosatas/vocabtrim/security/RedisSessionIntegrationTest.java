package com.adarosatas.vocabtrim.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.data.redis.RedisSessionRepository;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

//启动完整后端，用真实 HTTP 和 Redis 跑一条基本登录流程。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisSessionIntegrationTest {
    //##注入测试环境
    @Value("${local.server.port}") private int port;
    @Autowired private RedisSessionRepository sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JsonMapper json;

    private final String username = "redis_" + UUID.randomUUID().toString().substring(0, 8);
    private String sessionId;

    @Test
    void loginStoresSessionWithoutPasswordHash() throws Exception {
        //1. 准备测试账号：MySQL 中仍保存 BCrypt 哈希。
        jdbc.update("INSERT INTO users (username, password_hash, enabled) VALUES (?, ?, true)",
                username, passwordEncoder.encode("test-password"));

        CookieManager cookies = new CookieManager();
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            URI base = URI.create("http://localhost:" + port + "/api/v1/auth/");

            //2. 和前端一样，先取得 CSRF token，再携带它登录。
            var me = client.send(HttpRequest.newBuilder(base.resolve("me")).build(),
                    HttpResponse.BodyHandlers.ofString());
            String csrfToken = json.readTree(me.body()).path("csrfToken").asText();
            var request = HttpRequest.newBuilder(base.resolve("login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-XSRF-TOKEN", csrfToken)
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=test-password"))
                    .build();
            var login = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(200);

            //3. Spring Session 的默认 Cookie 值是 Base64 编码的 Session ID。
            String cookie = cookies.getCookieStore().getCookies().stream()
                    .filter(item -> item.getName().equals("VOCABTRIM_SESSION"))
                    .findFirst().orElseThrow().getValue();
            sessionId = new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);

            //4. 从真实 Redis 读回 Session，验证身份和凭据擦除。
            Session session = sessions.findById(sessionId);
            assertThat(session).isNotNull();
            SecurityContext context = session.getAttribute("SPRING_SECURITY_CONTEXT");
            VocabTrimPrincipal principal = (VocabTrimPrincipal) context.getAuthentication().getPrincipal();
            assertThat(principal.getUsername()).isEqualTo(username);
            assertThat(principal.getPassword()).isNull();
        }
    }

    //##每个测试结束后清理：只删除自己的 Session 和账号。
    @AfterEach
    void cleanup() {
        if (sessionId != null) sessions.deleteById(sessionId);
        jdbc.update("DELETE FROM users WHERE username = ?", username);
    }
}
