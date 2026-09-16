package com.adarosatas.vocabtrim.security;
//安全全链测试
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.data.redis.RedisSessionRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisSessionIntegrationTest {
    //##常量
    private static final String PASSWORD = "Session-test-password-42!";
    private static final String SNAPSHOT = """
            {"format":"vocabtrim-snapshot","schemaVersion":1,"lists":[],
             "settings":{"defaultDetails":false,"keepDuration":60,"crossDuration":205},"savedAt":1}
            """;

    //##注入测试环境
    @Value("${local.server.port}") private int port;
    @Value("${spring.session.data.redis.namespace}") private String namespace;
    @Autowired private RedisSessionRepository sessions;
    @Autowired private RedisConnectionFactory redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JsonMapper json;

    //##测试过程的记录
    private final List<Browser> browsers = new ArrayList<>();
    private final Set<String> sessionIds = new HashSet<>();

    //##每个测试结束后清理
    //只移除该测试的session和账号，不要清掉redis和数据库
    @AfterEach
    void cleanup() {
        sessionIds.forEach(sessions::deleteById);
        for (Browser browser : browsers) {
            browser.client.close();
            jdbc.update("DELETE FROM users WHERE username = ?", browser.username);
        }
    }

    //##测试：登入、Redis Session、登出
    @Test
    void loginStoresAnErasedPrincipalAndLogoutInvalidatesTheCookie() throws Exception {
        Browser browser = register();
        String hash = passwordHash(browser);
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
        HttpResponse<String> login = browser.login(PASSWORD);
        expect(login, 200);
        assertThat(login.headers().allValues("Set-Cookie"))
                .anySatisfy(cookie -> assertThat(cookie)
                        .startsWith("VOCABTRIM_SESSION=").contains("HttpOnly", "SameSite=Lax"));

        String cookie = browser.sessionCookie();
        String id = sessionId(cookie);
        Session session = sessions.findById(id); //从真实redis里读session而非从登录请求
        assertThat(session).isNotNull();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofDays(30));
        SecurityContext context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().isAuthenticated()).isTrue();
        assertThat(context.getAuthentication().getCredentials()).isNull();
        VocabTrimPrincipal principal = (VocabTrimPrincipal) context.getAuthentication().getPrincipal();
        assertThat(principal.getUsername()).isEqualTo(browser.username);
        assertThat(principal.getPassword()).isNull();
        try (var connection = redis.getConnection()) {
            byte[] key = sessionKey(id);
            assertThat(connection.keyCommands().ttl(key)).isBetween(2_591_900L, 2_592_000L);
            byte[] stored = connection.hashCommands().hGet(key,
                    "sessionAttr:SPRING_SECURITY_CONTEXT".getBytes(StandardCharsets.UTF_8));
            assertThat(stored).isNotNull();
            assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(hash, PASSWORD);
        }
        assertThat(browser.refresh().path("user").path("username").asText()).isEqualTo(browser.username);
        assertThat(passwordHash(browser)).isEqualTo(hash);
        expect(browser.send("POST", "/auth/logout", "", false), 403);
        assertThat(browser.refresh().path("authenticated").asBoolean()).isTrue();
        expect(browser.send("POST", "/auth/logout", "", true), 200);
        assertThat(sessions.findById(id)).isNull();

        try (HttpClient replay = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(uri("/snapshot"))
                    .timeout(Duration.ofSeconds(10)).header("Cookie", "VOCABTRIM_SESSION=" + cookie).build();
            expect(replay.send(request, HttpResponse.BodyHandlers.ofString()), 401);
        }
        assertThat(browser.refresh().path("authenticated").asBoolean()).isFalse();
        expect(browser.login(PASSWORD), 200);
        assertThat(browser.refresh().path("authenticated").asBoolean()).isTrue();
    }

    //##测试：原有功能不因 Redis 改坏
    @Test
    void registrationLoginCsrfAndSnapshotPreconditionsStillApply() throws Exception {
        Browser browser = new Browser();
        browser.refresh();
        expect(browser.send("POST", "/auth/register", browser.registration(), false), 403);
        expect(browser.send("POST", "/auth/register", browser.registration(), true), 201);
        expect(browser.send("POST", "/auth/register", browser.registration(), true), 409);
        expect(browser.login("wrong-password"), 401);
        expect(browser.send("POST", "/auth/login", "username=x&password=x", false,
                "Content-Type", "application/x-www-form-urlencoded"), 403);
        expect(browser.login(PASSWORD), 200);
        browser.refresh();

        expect(browser.send("GET", "/snapshot", null, false), 404);
        expect(browser.send("PUT", "/snapshot?force=true", SNAPSHOT, false), 403);
        expect(browser.send("PUT", "/snapshot", "{}", true, "If-None-Match", "*"), 400);
        expect(browser.send("PUT", "/snapshot", SNAPSHOT, true), 428);
        var first = browser.send("PUT", "/snapshot", SNAPSHOT, true, "If-None-Match", "*");
        expect(first, 200);
        String etag = first.headers().firstValue("ETag").orElseThrow();
        var download = browser.send("GET", "/snapshot", null, false);
        expect(download, 200);
        assertThat(download.body()).isEqualTo(SNAPSHOT);
        assertThat(download.headers().firstValue("ETag")).contains(etag);
        expect(browser.send("PUT", "/snapshot", SNAPSHOT, true, "If-None-Match", "*"), 409);
        var second = browser.send("PUT", "/snapshot", SNAPSHOT, true, "If-Match", etag);
        expect(second, 200);
        assertThat(second.headers().firstValue("ETag").orElseThrow()).isNotEqualTo(etag);
        expect(browser.send("PUT", "/snapshot", SNAPSHOT, true, "If-Match", etag), 409);
        expect(browser.send("PUT", "/snapshot?force=true", SNAPSHOT, true), 200);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM snapshots s JOIN users u ON s.user_id = u.id WHERE u.username = ?
                """, Integer.class, browser.username)).isEqualTo(2);
    }

    //##测试：用户间数据隔离
    @Test
    void differentSessionsKeepUsersSnapshotsSeparate() throws Exception {
        Browser first = register();
        Browser second = register();
        expect(first.login(PASSWORD), 200);
        expect(second.login(PASSWORD), 200);
        first.refresh();
        second.refresh();
        expect(first.send("PUT", "/snapshot", SNAPSHOT, true, "If-None-Match", "*"), 200);
        expect(second.send("GET", "/snapshot", null, false), 404);
        String otherSnapshot = SNAPSHOT.replace("\"savedAt\":1", "\"savedAt\":2");
        expect(second.send("PUT", "/snapshot", otherSnapshot, true, "If-None-Match", "*"), 200);
        assertThat(first.send("GET", "/snapshot", null, false).body()).isEqualTo(SNAPSHOT);
        assertThat(second.send("GET", "/snapshot", null, false).body()).isEqualTo(otherSnapshot);
    }

    //##测试：redis丢了，但mysql不能丢
    @Test
    void losingTheRedisSessionRequiresLoginButPreservesMysqlData() throws Exception {
        Browser browser = register();
        expect(browser.login(PASSWORD), 200);
        browser.refresh();
        expect(browser.send("PUT", "/snapshot", SNAPSHOT, true, "If-None-Match", "*"), 200);
        try (var connection = redis.getConnection()) {
            //只让这个session过期，以模拟session丢失，而不需要重启redis
            connection.keyCommands().expire(sessionKey(sessionId(browser.sessionCookie())), 0);
        }
        expect(browser.send("GET", "/snapshot", null, false), 401);
        assertThat(browser.refresh().path("authenticated").asBoolean()).isFalse();
        expect(browser.login(PASSWORD), 200);
        assertThat(browser.send("GET", "/snapshot", null, false).body()).isEqualTo(SNAPSHOT);
    }

    //##缩写方法：assertThat
    private static void expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    }

    //##辅助方法：模拟注册
    private Browser register() throws Exception {
        Browser browser = new Browser();
        assertThat(browser.refresh().path("authenticated").asBoolean()).isFalse();
        expect(browser.send("POST", "/auth/register", browser.registration(), true), 201);
        return browser;
    }

    //##辅助方法：直接从 MySQL 查这个用户的哈希
    private String passwordHash(Browser browser) {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE username = ?", String.class, browser.username);
    }

    //辅助方法：生成URI
    private URI uri(String path) {
        return URI.create("http://localhost:" + port + "/api/v1" + path);
    }

    //##辅助方法：生成redis中对应的session key
    private byte[] sessionKey(String id) {
        return (namespace + ":sessions:" + id).getBytes(StandardCharsets.UTF_8);
    }

    //##辅助方法：Cookie->Session ID
    private static String sessionId(String cookie) {
        return new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
    }

    //##工具类：模拟浏览器
    private class Browser {
        final String username = "redis_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        String csrfToken;

        Browser() { browsers.add(this); }

        String registration() {
            return json.writeValueAsString(Map.of("username", username, "password", PASSWORD, "confirmPassword", PASSWORD));
        }

        HttpResponse<String> login(String password) throws Exception {
            return send("POST", "/auth/login", "username=" + username + "&password="
                    + URLEncoder.encode(password, StandardCharsets.UTF_8), true,
                    "Content-Type", "application/x-www-form-urlencoded");
        }

        JsonNode refresh() throws Exception {
            var response = send("GET", "/auth/me", null, false);
            expect(response, 200);
            JsonNode state = json.readTree(response.body());
            csrfToken = state.path("csrfToken").asText();
            assertThat(csrfToken).isNotBlank();
            return state;
        }

        String sessionCookie() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("VOCABTRIM_SESSION"))
                    .findFirst().orElseThrow().getValue();
        }

        HttpResponse<String> send(String method, String path, String body, boolean csrf, String... headers) throws Exception {
            var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json");
            if (csrf) request.header("X-XSRF-TOKEN", csrfToken);
            for (int i = 0; i < headers.length; i += 2) request.setHeader(headers[i], headers[i + 1]);
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("VOCABTRIM_SESSION") && !cookie.getValue().isEmpty())
                    .forEach(cookie -> sessionIds.add(sessionId(cookie.getValue())));
            return response;
        }
    }
}
