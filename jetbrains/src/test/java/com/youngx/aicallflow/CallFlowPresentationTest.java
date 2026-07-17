package com.youngx.aicallflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CallFlowPresentationTest {
    @Test
    void headerExplainsExactCallDepthBreadcrumbProgressAndRoute() {
        CallFlowNode source = node("call-site", null);
        CallFlowNode target = node("callee", null);
        CallFlowPlayback.Visit previous = visit(
                source,
                null,
                CallFlowPlayback.TransitionType.ENTRY,
                List.of("MainActivity.onCreate"),
                true
        );
        CallFlowEdge edge = new CallFlowEdge(
                source.id(),
                target.id(),
                EdgeKind.STEP_INTO,
                "Enter repository",
                new CallFlowTransition(TransitionKind.CALL)
        );
        CallFlowPlayback.Visit current = new CallFlowPlayback.Visit(
                target,
                edge,
                CallFlowPlayback.TransitionType.CALL,
                new CallFlowPlayback.ExecutionState(
                        "Main thread",
                        List.of("MainActivity.onCreate", "Repository.load"),
                        "Load data",
                        true
                )
        );

        CallFlowPresentation.Header header = CallFlowPresentation.header(
                current,
                previous,
                4,
                3,
                12,
                2,
                1
        );

        assertEquals("Step 4 · Depth L1 · Visited 3/12 · 2 unexplored", header.position());
        assertEquals(
                "Main thread · MainActivity.onCreate  ›  Repository.load · Phase: Load data",
                header.breadcrumb()
        );
        assertEquals(
                "↳ CALL / INTO · call-site  →  callee · Enter repository",
                header.transition()
        );
    }

    @Test
    void inferredAndDirectJumpAreAlwaysExplicit() {
        CallFlowPlayback.Visit source = visit(
                node("source", null),
                null,
                CallFlowPlayback.TransitionType.ENTRY,
                List.of("source"),
                false
        );
        CallFlowPlayback.Visit target = visit(
                node("target", null),
                null,
                CallFlowPlayback.TransitionType.DIRECT_JUMP,
                List.of("target"),
                false
        );

        CallFlowPresentation.Header header = CallFlowPresentation.header(
                target,
                source,
                2,
                2,
                2,
                0,
                0
        );

        assertTrue(header.position().contains("Depth L0 (inferred)"));
        assertTrue(header.position().endsWith("End of current path"));
        assertEquals(
                "⌖ DIRECT JUMP · source  →  target · Source location only",
                header.transition()
        );
    }

    @Test
    void nodeRowsDistinguishCurrentVisitedAndExactUnvisitedNodes() {
        CallFlowNode visitedNode = node("visited", null);
        CallFlowPlayback.Visit deepVisit = visit(
                visitedNode,
                null,
                CallFlowPlayback.TransitionType.CALLBACK_ENTER,
                List.of(
                        "f0", "f1", "f2", "f3", "f4", "f5", "f6",
                        "f7", "f8", "f9", "f10", "f11", "f12"
                ),
                true
        );

        CallFlowPresentation.NodeRow current = CallFlowPresentation.nodeRow(
                visitedNode,
                deepVisit,
                "f12",
                true,
                true
        );
        assertEquals("▶", current.marker());
        assertEquals("L12", current.level());
        assertEquals("↪ CB IN", current.badge());
        assertEquals(CallFlowPresentation.BadgeTone.CALLBACK, current.badgeTone());
        assertFalse(current.pending());
        assertEquals("visited", current.title());
        assertEquals(240, current.indentWidth());
        assertTrue(current.indentTruncated());
        assertTrue(current.exact());

        CallFlowNode planned = node(
                "planned",
                new CallFlowExecution("main", List.of("root", "child"), null)
        );
        CallFlowPresentation.NodeRow unvisited = CallFlowPresentation.nodeRow(
                planned,
                null,
                "child frame",
                false,
                false
        );
        assertEquals("○", unvisited.marker());
        assertEquals(24, unvisited.indentWidth());
        assertFalse(unvisited.indentTruncated());
        assertEquals("L1", unvisited.level());
        assertEquals("[call]", unvisited.badge());
        assertEquals(CallFlowPresentation.BadgeTone.PLANNED, unvisited.badgeTone());
        assertTrue(unvisited.pending());
        assertTrue(unvisited.exact());

        CallFlowPresentation.NodeRow visited = CallFlowPresentation.nodeRow(
                visitedNode,
                deepVisit,
                "f12",
                true,
                false
        );
        assertEquals("✓", visited.marker());
        assertEquals(240, visited.indentWidth());
    }

    @Test
    void compactCallbackFlowShowsTheBoundaryAsContextInsteadOfAStandaloneNode() {
        CallFlowNode setContent = new CallFlowNode(
                "set-content-call",
                NodeKind.CALL,
                new CallFlowLocation(
                        "app/src/main/java/com/example/myapplication/MainActivity.kt",
                        20,
                        9,
                        null,
                        null,
                        "androidx.activity.compose.setContent",
                        "setContent {"
                ),
                "向 Compose 注册根 content lambda。",
                new CallFlowExecution("android-main", List.of("main-on-create"), "Activity creation")
        );
        CallFlowNode themeCall = new CallFlowNode(
                "theme-call",
                NodeKind.CALL,
                new CallFlowLocation(
                        "app/src/main/java/com/example/myapplication/MainActivity.kt",
                        21,
                        13,
                        null,
                        null,
                        "com.example.myapplication.ui.theme.MyApplicationTheme",
                        "MyApplicationTheme {"
                ),
                "Compose 进入 setContent content lambda，并执行首个表达式：调用 MyApplicationTheme。",
                new CallFlowExecution(
                        "android-main",
                        List.of("main-on-create", "set-content-callback"),
                        "Compose content"
                )
        );
        CallFlowEdge callbackEnter = new CallFlowEdge(
                setContent.id(),
                themeCall.id(),
                EdgeKind.STEP_INTO,
                "Enter setContent content lambda and call MyApplicationTheme",
                new CallFlowTransition(TransitionKind.CALLBACK_ENTER)
        );
        CallFlowPlayback.Visit previous = visit(
                setContent,
                null,
                CallFlowPlayback.TransitionType.CONTINUE,
                List.of("MainActivity.onCreate"),
                true
        );
        CallFlowPlayback.Visit current = new CallFlowPlayback.Visit(
                themeCall,
                callbackEnter,
                CallFlowPlayback.TransitionType.CALLBACK_ENTER,
                new CallFlowPlayback.ExecutionState(
                        "Android main thread",
                        List.of("MainActivity.onCreate", "setContent content lambda"),
                        "Compose content",
                        true
                )
        );

        // The callback boundary is represented by the edge and stack frame, not a third node.
        List<CallFlowNode> semanticNodes = List.of(setContent, themeCall);
        assertEquals(2, semanticNodes.size());
        assertTrue(semanticNodes.stream().noneMatch(node -> node.kind() == NodeKind.CALLBACK));
        CallFlowPresentation.NodeRow row = CallFlowPresentation.nodeRow(
                themeCall,
                current,
                "setContent content lambda",
                true,
                true
        );
        assertEquals("▶", row.marker());
        assertEquals("L1", row.level());
        assertEquals("↪ CB IN", row.badge());
        assertEquals("MyApplicationTheme", row.title());

        CallFlowPresentation.Header header = CallFlowPresentation.header(
                current,
                previous,
                2,
                2,
                2,
                0,
                1
        );
        assertEquals(
                "Android main thread · MainActivity.onCreate  ›  setContent content lambda · Phase: Compose content",
                header.breadcrumb()
        );
        assertEquals(
                "↪ CALLBACK ENTER · setContent  →  MyApplicationTheme · "
                        + "Enter setContent content lambda and call MyApplicationTheme",
                header.transition()
        );
        assertEquals(
                "Compose 进入 setContent content lambda，并执行首个表达式：调用 MyApplicationTheme。",
                CallFlowPresentation.nodeDetails(themeCall)
        );
    }

    @Test
    void nodeDetailsContainOnlyTheSemanticSummary() {
        CallFlowNode node = new CallFlowNode(
                "technical-id",
                NodeKind.CALL,
                new CallFlowLocation(
                        "src/Main.kt",
                        18,
                        9,
                        null,
                        null,
                        "Main.onCreate",
                        "super.onCreate()"
                ),
                "调用父类 onCreate，继续执行 Activity 初始化。",
                new CallFlowExecution("main", List.of("Main.onCreate"), "Activity creation")
        );

        assertEquals(
                "调用父类 onCreate，继续执行 Activity 初始化。",
                CallFlowPresentation.nodeDetails(node)
        );
    }

    @Test
    void nodeTitlesUseSourceSymbolsAndNeverExposeInternalIdsOrSummaries() {
        CallFlowNode entryNode = new CallFlowNode(
                "on-create-entry",
                NodeKind.ENTRY,
                new CallFlowLocation(
                        "app/src/MainActivity.kt",
                        17,
                        5,
                        null,
                        null,
                        "com.example.myapplication.MainActivity.onCreate",
                        "override fun onCreate"
                ),
                "Android 进入入口。"
        );
        assertEquals(
                "MainActivity.onCreate",
                CallFlowPresentation.nodeTitle(entryNode, null)
        );

        CallFlowNode superCallNode = new CallFlowNode(
                "super-on-create",
                NodeKind.CALL,
                new CallFlowLocation(
                        "app/src/MainActivity.kt",
                        18,
                        9,
                        null,
                        null,
                        null,
                        "super.onCreate(savedInstanceState)"
                ),
                "调用父类实现。"
        );
        assertEquals(
                "super.onCreate",
                CallFlowPresentation.nodeTitle(superCallNode, null)
        );

        CallFlowNode symbolNode = new CallFlowNode(
                "set-content-call",
                NodeKind.CALL,
                new CallFlowLocation(
                        "app/src/MainActivity.kt",
                        20,
                        9,
                        null,
                        null,
                        "androidx.activity.compose.setContent",
                        "setContent {"
                ),
                "向 Compose 注册根 content callback。"
        );
        assertEquals("setContent", CallFlowPresentation.nodeTitle(symbolNode, null));

        CallFlowNode anchorNode = new CallFlowNode(
                "legacy-set-content-call",
                NodeKind.CALL,
                new CallFlowLocation(
                        "app/src/MainActivity.kt",
                        20,
                        9,
                        null,
                        null,
                        null,
                        "setContent {"
                ),
                "旧 Flow 也应显示源码调用名。"
        );
        assertEquals("setContent", CallFlowPresentation.nodeTitle(anchorNode, null));

        CallFlowNode callbackNode = new CallFlowNode(
                "set-content-body",
                NodeKind.CALLBACK,
                new CallFlowLocation(
                        "app/src/MainActivity.kt",
                        21,
                        13,
                        null,
                        null,
                        null,
                        null
                ),
                "Compose 进入回调。"
        );
        assertEquals(
                "setContent content callback",
                CallFlowPresentation.nodeTitle(callbackNode, "setContent content callback")
        );

        CallFlowNode fallbackNode = new CallFlowNode(
                "opaque-machine-id",
                NodeKind.CALL,
                new CallFlowLocation(
                        "app/src/MainActivity.kt",
                        23,
                        21,
                        null,
                        null,
                        null,
                        null
                ),
                "这段 summary 只能出现在 Node details。"
        );
        assertEquals("MainActivity.kt:23", CallFlowPresentation.nodeTitle(fallbackNode, null));
    }

    @Test
    void sourceSymbolShortNamesHandleKotlinAndJvmCompatibilityForms() {
        assertEquals(
                "setContent",
                CallFlowPresentation.shortSourceSymbol("androidx.activity.compose.setContent")
        );
        assertEquals(
                "create",
                CallFlowPresentation.shortSourceSymbol("com.foo.Outer.Companion.create")
        );
        assertEquals(
                "`when.called`",
                CallFlowPresentation.shortSourceSymbol("com.foo.`when.called`")
        );
        assertEquals(
                "User()",
                CallFlowPresentation.shortSourceSymbol("com.foo.User.<init>(java.lang.String)")
        );
        assertEquals("plus", CallFlowPresentation.shortSourceSymbol("com.foo.Vector.plus"));
        assertEquals("invoke", CallFlowPresentation.shortSourceSymbol("com.foo.Block.invoke"));
        assertEquals("load", CallFlowPresentation.shortSourceSymbol("com.foo.Repo.load$default"));
        assertEquals(
                "onCreate",
                CallFlowPresentation.shortSourceSymbol("com.foo.Main.onCreate$lambda$2")
        );
    }

    @Test
    void inferredLegacyStacksNeverBecomeUserVisibleNodeIds() {
        CallFlowNode source = new CallFlowNode(
                "legacy-source-machine-id",
                NodeKind.ENTRY,
                new CallFlowLocation("src/Main.kt", 41, 1, null, null, null, null),
                "Legacy source summary"
        );
        CallFlowNode target = new CallFlowNode(
                "legacy-target-machine-id",
                NodeKind.CALLBACK,
                new CallFlowLocation("src/Main.kt", 42, 1, null, null, null, null),
                "Legacy target summary"
        );
        CallFlowPlayback.Visit sourceVisit = visit(
                source,
                null,
                CallFlowPlayback.TransitionType.ENTRY,
                List.of(source.id()),
                false
        );
        CallFlowPlayback.Visit targetVisit = visit(
                target,
                null,
                CallFlowPlayback.TransitionType.DIRECT_JUMP,
                List.of(target.id()),
                false
        );

        CallFlowPresentation.NodeRow row = CallFlowPresentation.nodeRow(
                target,
                targetVisit,
                target.id(),
                true,
                true
        );
        assertEquals("Main.kt:42", row.title());
        assertEquals(
                "● ENTRY · Main.kt:41",
                CallFlowPresentation.transitionDescription(sourceVisit, null)
        );
        assertEquals(
                "⌖ DIRECT JUMP · Main.kt:41  →  Main.kt:42 · Source location only",
                CallFlowPresentation.transitionDescription(targetVisit, sourceVisit)
        );
    }

    @Test
    void everyTransitionHasAReadableBadge() {
        for (CallFlowPlayback.TransitionType type : CallFlowPlayback.TransitionType.values()) {
            assertFalse(CallFlowPresentation.transitionBadge(type).isBlank());
            assertFalse(CallFlowPresentation.shortTransition(type).isBlank());
            assertTrue(CallFlowPresentation.badgeTone(type) != null);
        }
        assertEquals(
                "● ENTRY",
                CallFlowPresentation.shortTransition(CallFlowPlayback.TransitionType.ENTRY)
        );
        assertEquals(
                "→ NEXT",
                CallFlowPresentation.shortTransition(CallFlowPlayback.TransitionType.CONTINUE)
        );
        assertEquals(
                "↷ OVER",
                CallFlowPresentation.shortTransition(CallFlowPlayback.TransitionType.STEP_OVER)
        );
        assertEquals(
                "↪ CB IN",
                CallFlowPresentation.shortTransition(CallFlowPlayback.TransitionType.CALLBACK_ENTER)
        );
        assertEquals(
                "↩ CB OUT",
                CallFlowPresentation.shortTransition(CallFlowPlayback.TransitionType.CALLBACK_RETURN)
        );
        assertEquals(
                "⌖ DIRECT",
                CallFlowPresentation.shortTransition(CallFlowPlayback.TransitionType.DIRECT_JUMP)
        );
    }

    @Test
    void unvisitedNodeBadgesDescribePlannedRolesWithoutRepeatingPending() {
        assertEquals("[entry]", CallFlowPresentation.plannedNodeBadge(NodeKind.ENTRY));
        assertEquals("[decl]", CallFlowPresentation.plannedNodeBadge(NodeKind.DECLARATION));
        assertEquals("[call]", CallFlowPresentation.plannedNodeBadge(NodeKind.CALL));
        assertEquals("[branch]", CallFlowPresentation.plannedNodeBadge(NodeKind.BRANCH));
        assertEquals("[return]", CallFlowPresentation.plannedNodeBadge(NodeKind.RETURN));
        assertEquals("[async]", CallFlowPresentation.plannedNodeBadge(NodeKind.ASYNC));
        assertEquals("[callback]", CallFlowPresentation.plannedNodeBadge(NodeKind.CALLBACK));
        assertEquals("[note]", CallFlowPresentation.plannedNodeBadge(NodeKind.NOTE));
    }

    private static CallFlowPlayback.Visit visit(
            CallFlowNode node,
            CallFlowEdge edge,
            CallFlowPlayback.TransitionType type,
            List<String> stack,
            boolean exact
    ) {
        return new CallFlowPlayback.Visit(
                node,
                edge,
                type,
                new CallFlowPlayback.ExecutionState(null, stack, null, exact)
        );
    }

    private static CallFlowNode node(String id, CallFlowExecution execution) {
        return new CallFlowNode(
                id,
                NodeKind.CALL,
                new CallFlowLocation("src/Main.kt", 1, 1, null, null, id, null),
                "Node " + id,
                execution
        );
    }
}
