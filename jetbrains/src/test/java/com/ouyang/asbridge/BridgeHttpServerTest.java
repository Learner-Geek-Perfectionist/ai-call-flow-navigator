package com.ouyang.asbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BridgeHttpServerTest {
    @TempDir
    Path root;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void opensSourceLinkWithNoContentResponse() throws Exception {
        List<ResolvedOpenRequest> opened = new ArrayList<>();
        BridgeHttpServer server = BridgeHttpServer.start(
                new BridgeRuntimeConfig(root.toString(), 0, true),
                opened::add
        );

        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(server.uri("/open?path=app/src/main/Foo.kt&line=42&col=7"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(204, response.statusCode());
            assertEquals("", response.body());
            assertEquals(1, opened.size());
            assertEquals(root.resolve("app/src/main/Foo.kt").normalize(), opened.get(0).absolutePath());
            assertEquals(42, opened.get(0).line());
            assertEquals(7, opened.get(0).column());
        } finally {
            server.close();
        }
    }

    @Test
    void acceptsPostRequestsFromSendBeacon() throws Exception {
        List<ResolvedOpenRequest> opened = new ArrayList<>();
        BridgeHttpServer server = BridgeHttpServer.start(
                new BridgeRuntimeConfig(root.toString(), 0, true),
                opened::add
        );

        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(server.uri("/open?path=app/src/main/Foo.kt&line=2&column=3"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(204, response.statusCode());
            assertEquals(1, opened.size());
        } finally {
            server.close();
        }
    }

    @Test
    void servesHealthAndQuietHelperScript() throws Exception {
        BridgeHttpServer server = BridgeHttpServer.start(
                new BridgeRuntimeConfig(root.toString(), 0, true),
                ignored -> {
                }
        );

        try {
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(server.uri("/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, health.statusCode());
            assertEquals("OK", health.body());

            HttpResponse<String> script = client.send(
                    HttpRequest.newBuilder(server.uri("/as-bridge.js")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, script.statusCode());
            assertTrue(script.headers().firstValue("content-type").orElse("").contains("application/javascript"));
            assertTrue(script.body().contains("data-as-path"));
        } finally {
            server.close();
        }
    }

    @Test
    void rejectsUnsupportedEndpointAndEscapingPath() throws Exception {
        BridgeHttpServer server = BridgeHttpServer.start(
                new BridgeRuntimeConfig(root.toString(), 0, true),
                ignored -> {
                }
        );

        try {
            HttpResponse<String> unsupported = client.send(
                    HttpRequest.newBuilder(server.uri("/as-src/o?path=app/Foo.kt")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(404, unsupported.statusCode());

            HttpResponse<String> escaping = client.send(
                    HttpRequest.newBuilder(server.uri("/open?path=../outside/Foo.kt")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(403, escaping.statusCode());
        } finally {
            server.close();
        }
    }

    @Test
    void reportsAddressInUseWhenConfiguredPortIsAlreadyOwned() throws Exception {
        BridgeHttpServer first = BridgeHttpServer.start(
                new BridgeRuntimeConfig(root.toString(), 0, true),
                ignored -> {
                }
        );

        try {
            Exception error = assertThrows(Exception.class, () ->
                    BridgeHttpServer.start(
                            new BridgeRuntimeConfig(root.toString(), first.port(), true),
                            ignored -> {
                            }
                    )
            );

            assertTrue(BridgeHttpServer.isAddressInUse(error));
        } finally {
            first.close();
        }
    }
}
