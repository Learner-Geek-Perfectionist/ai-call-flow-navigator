package com.youngx.aicallflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class UastCallFlowGeneratorTest {
    @Test
    void matchesComposeCallbacksBySourceSymbol() {
        assertEquals(
                "content",
                UastCallFlowGenerator.frameworkCallbackParameter(
                        "androidx.compose.material3.Scaffold",
                        null,
                        null
                )
        );
    }

    @Test
    void matchesComposeCallbacksByJvmFacade() {
        assertEquals(
                "content",
                UastCallFlowGenerator.frameworkCallbackParameter(
                        null,
                        "androidx.activity.compose.ComponentActivityKt",
                        "setContent"
                )
        );
        assertEquals(
                "content",
                UastCallFlowGenerator.frameworkCallbackParameter(
                        null,
                        "androidx.compose.material3.MaterialThemeKt",
                        "MaterialTheme"
                )
        );
    }

    @Test
    void matchesMangledScaffoldJvmMethods() {
        assertEquals(
                "content",
                UastCallFlowGenerator.frameworkCallbackParameter(
                        null,
                        "androidx.compose.material3.ScaffoldKt",
                        "Scaffold-TvnljyQ"
                )
        );
        assertEquals(
                "content",
                UastCallFlowGenerator.frameworkCallbackParameter(
                        null,
                        "androidx.compose.material3.ScaffoldKt",
                        "Scaffold-TvnljyQ$default"
                )
        );
    }

    @Test
    void doesNotMatchSameMethodOnAnotherOwner() {
        assertNull(UastCallFlowGenerator.frameworkCallbackParameter(
                null,
                "example.ScaffoldKt",
                "Scaffold-TvnljyQ"
        ));
    }
}
