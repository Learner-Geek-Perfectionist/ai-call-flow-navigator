package com.youngx.aicallflow;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.impl.DocumentImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CallFlowNavigatorTest {
    @Test
    void relocatesUniqueAnchorWhenStaleLineIsPastEndOfFile() {
        String source = "first\nsecond\ntargetCall()\n";
        Document document = new DocumentImpl(source);
        CallFlowLocation location = new CallFlowLocation(
                "app/Main.kt",
                20,
                5,
                null,
                null,
                null,
                "targetCall()"
        );

        CallFlowNavigator.ResolvedLocation resolved = CallFlowNavigator.resolveLocation(
                document,
                location
        );

        int expectedOffset = source.indexOf("targetCall()");
        assertEquals(expectedOffset, resolved.startOffset());
        assertEquals(
                expectedOffset + "targetCall()".length(),
                resolved.endOffset()
        );
        assertEquals(CallFlowNavigator.AnchorMatch.RELOCATED, resolved.anchorMatch());
    }

    @Test
    void rejectsStaleOutOfRangeCoordinatesWhenAnchorCannotRepairThem() {
        Document document = new DocumentImpl("first\nsecond\n");
        CallFlowLocation location = new CallFlowLocation(
                "app/Main.kt",
                20,
                1,
                null,
                null,
                null,
                "missingCall()"
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> CallFlowNavigator.resolveLocation(document, location)
        );

        assertEquals("Line 20 is outside the source document", error.getMessage());
    }

    @Test
    void reportsAmbiguousAnchorAndFallsBackToValidCoordinates() {
        Document document = new DocumentImpl("call()\ncall()\n");
        CallFlowLocation location = new CallFlowLocation(
                "app/Main.kt",
                1,
                1,
                null,
                null,
                null,
                "call()"
        );

        CallFlowNavigator.ResolvedLocation resolved = CallFlowNavigator.resolveLocation(
                document,
                location
        );

        assertEquals(0, resolved.startOffset());
        assertEquals(1, resolved.endOffset());
        assertEquals(CallFlowNavigator.AnchorMatch.AMBIGUOUS, resolved.anchorMatch());
    }
}
