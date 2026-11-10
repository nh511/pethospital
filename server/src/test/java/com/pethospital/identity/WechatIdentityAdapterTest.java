package com.pethospital.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pethospital.shared.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WechatIdentityAdapterTest {
    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void realModeSendsServerCredentialsAndOnlyReturnsOpenId() {
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/ok", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = "{\"openid\":\"real-openid-001\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        String secretFixture = "server-only-secret-fixture";
        var adapter = new WechatIdentityConfiguration.RealWechatIdentityAdapter(
            "wx-app-id-fixture",
            secretFixture,
            endpoint("/ok")
        );

        assertThat(adapter.exchange("temporary-code").openId()).isEqualTo("real-openid-001");
        assertThat(query.get()).contains(
            "appid=wx-app-id-fixture",
            "secret=server-only-secret-fixture",
            "js_code=temporary-code",
            "grant_type=authorization_code"
        );
    }

    @Test
    void realModeMapsWechatErrorWithoutLeakingCredentialOrProviderDetail() {
        server.createContext("/bad", exchange -> {
            byte[] body = "{\"errcode\":40029,\"errmsg\":\"invalid code\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        String secretFixture = "do-not-expose-this-fixture";
        var adapter = new WechatIdentityConfiguration.RealWechatIdentityAdapter(
            "wx-app-id-fixture",
            secretFixture,
            endpoint("/bad")
        );

        assertThatThrownBy(() -> adapter.exchange("rejected-code"))
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.code).isEqualTo("WECHAT_CODE_INVALID");
                assertThat(exception.getMessage()).doesNotContain(secretFixture, "40029", "invalid code");
            });
    }

    private String endpoint(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }
}
