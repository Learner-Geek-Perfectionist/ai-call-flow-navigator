package com.ouyang.asbridge;

record BridgeRuntimeConfig(String projectRoot, int port, boolean enabled) {
    BridgeRuntimeConfig {
        if (projectRoot == null || projectRoot.isBlank()) {
            throw new IllegalArgumentException("projectRoot is required");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
    }
}
