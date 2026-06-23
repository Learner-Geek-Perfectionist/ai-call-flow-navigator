package com.ouyang.asbridge;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

record OpenRequest(String relativePath, int line, int column) {
    OpenRequest {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        if (line < 1) {
            throw new IllegalArgumentException("line must be a positive integer");
        }
        if (column < 1) {
            throw new IllegalArgumentException("column must be a positive integer");
        }
    }

    static OpenRequest parse(String rawUrl) {
        URI uri = URI.create(rawUrl);
        if (!"/open".equals(uri.getPath())) {
            throw new IllegalArgumentException("Unsupported endpoint: " + uri.getPath());
        }

        Map<String, String> query = parseQuery(uri.getRawQuery());
        String path = query.get("path");
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }

        int line = parsePositiveInteger(query.getOrDefault("line", "1"), "line");
        int column = parsePositiveInteger(
                query.getOrDefault("col", query.getOrDefault("column", "1")),
                "column"
        );
        return new OpenRequest(path, line, column);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }

        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals >= 0 ? pair.substring(0, equals) : pair;
            String value = equals >= 0 ? pair.substring(equals + 1) : "";
            values.put(decode(key), decode(value));
        }
        return values;
    }

    private static int parsePositiveInteger(String value, String name) {
        if (value == null || !value.matches("\\d+")) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be a positive integer", error);
        }
        if (parsed < 1) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
        return parsed;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
