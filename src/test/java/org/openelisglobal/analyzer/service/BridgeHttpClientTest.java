package org.openelisglobal.analyzer.service;

import static org.junit.Assert.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BridgeHttpClientTest {

    private HttpServer server;
    private final AtomicReference<String> authorizationHeader = new AtomicReference<>();

    @Before
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/analyzers", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void sendsBasicAuthWhenBridgeCredentialsAreConfigured() throws Exception {
        BridgeHttpClient client = new BridgeHttpClient();
        inject(client, "bridgeUsername", "admin");
        inject(client, "bridgePassword", "adminADMIN!");
        String credential = Base64.getEncoder().encodeToString("admin:adminADMIN!".getBytes(StandardCharsets.UTF_8));

        client.get(baseUrl() + "/api/analyzers", Duration.ofSeconds(5));

        assertEquals("Basic " + credential, authorizationHeader.get());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = BridgeHttpClient.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
