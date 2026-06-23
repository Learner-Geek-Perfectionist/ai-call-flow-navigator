package com.ouyang.asbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class OpenRequestTest {
    @Test
    void parsesOpenUrlWithColumnAlias() {
        OpenRequest request = OpenRequest.parse(
                "/open?path=app/src/main/java/com/example/MainActivity.kt&line=42&col=7"
        );

        assertEquals("app/src/main/java/com/example/MainActivity.kt", request.relativePath());
        assertEquals(42, request.line());
        assertEquals(7, request.column());
    }

    @Test
    void defaultsLineAndColumnToOne() {
        OpenRequest request = OpenRequest.parse("/open?path=app/src/main/AndroidManifest.xml");

        assertEquals(1, request.line());
        assertEquals(1, request.column());
    }

    @Test
    void acceptsColumnParameterName() {
        OpenRequest request = OpenRequest.parse("/open?path=app/build.gradle.kts&line=3&column=11");

        assertEquals(11, request.column());
    }

    @Test
    void rejectsAnythingExceptOpenEndpoint() {
        assertThrows(IllegalArgumentException.class, () ->
                OpenRequest.parse("/as-src/o?path=app/src/main/AndroidManifest.xml&line=1")
        );
        assertThrows(IllegalArgumentException.class, () ->
                OpenRequest.parse("/health?path=app/src/main/AndroidManifest.xml&line=1")
        );
    }

    @Test
    void rejectsMissingPathAndInvalidPositions() {
        assertThrows(IllegalArgumentException.class, () -> OpenRequest.parse("/open?line=1"));
        assertThrows(IllegalArgumentException.class, () -> OpenRequest.parse("/open?path=a.kt&line=0"));
        assertThrows(IllegalArgumentException.class, () -> OpenRequest.parse("/open?path=a.kt&column=-1"));
    }
}
