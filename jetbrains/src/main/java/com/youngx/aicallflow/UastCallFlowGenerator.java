package com.youngx.aicallflow;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.uast.UBlockExpression;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UIdentifier;
import org.jetbrains.uast.UIfExpression;
import org.jetbrains.uast.ULambdaExpression;
import org.jetbrains.uast.UMethod;
import org.jetbrains.uast.UNamedExpression;
import org.jetbrains.uast.UParenthesizedExpression;
import org.jetbrains.uast.UReferenceExpression;
import org.jetbrains.uast.USwitchClauseExpressionWithBody;
import org.jetbrains.uast.USwitchExpression;
import org.jetbrains.uast.UastContextKt;
import org.jetbrains.uast.visitor.AbstractUastVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministically projects a current Java/Kotlin function into Call Flow 1.1.
 *
 * <p>The generator expands project functions and a small, resolved Compose callback model.
 * Unknown library calls remain opaque and only expose Step Over.</p>
 */
final class UastCallFlowGenerator {
    private static final String CONTEXT_ID = "static-analysis";
    private static final int MAX_EXPANSION_DEPTH = 8;
    private static final int MAX_GENERATED_NODES = 2_000;
    private static final List<CallbackSpec> FRAMEWORK_CALLBACKS = List.of(
            new CallbackSpec(
                    "androidx.activity.compose.setContent",
                    "androidx.activity.compose.ComponentActivityKt",
                    "setContent",
                    "content"
            ),
            new CallbackSpec(
                    "androidx.compose.material3.MaterialTheme",
                    "androidx.compose.material3.MaterialThemeKt",
                    "MaterialTheme",
                    "content"
            ),
            new CallbackSpec(
                    "androidx.compose.material3.Scaffold",
                    "androidx.compose.material3.ScaffoldKt",
                    "Scaffold",
                    "content"
            )
    );

    private final PsiCallFlowLocationFactory locations;
    private final List<CallFlowNode> nodes = new ArrayList<>();
    private final List<CallFlowEdge> edges = new ArrayList<>();
    private final List<CallFlowFrame> frames = new ArrayList<>();
    private int nodeSequence;
    private int frameSequence;

    UastCallFlowGenerator(Project project) {
        locations = new PsiCallFlowLocationFactory(project);
    }

    CallFlow generate(UMethod rootMethod) {
        if (rootMethod == null || rootMethod.getUastBody() == null) {
            throw new IllegalArgumentException("Place the caret inside a function with a body");
        }
        String rootSymbol = methodSymbol(rootMethod);
        String rootLabel = methodLabel(rootMethod);
        CallFlowFrame rootFrame = newFrame(FrameKind.FUNCTION, rootLabel, rootSymbol);
        ScopeBuild root = buildFunctionScope(
                rootMethod,
                List.of(rootFrame.id()),
                Map.of(),
                true,
                0,
                new HashSet<>()
        );

        CallFlow flow = new CallFlow(
                CallFlow.CONTEXT_VERSION,
                rootLabel + " — Static Call Flow",
                nodes,
                edges,
                root.entry().id(),
                List.of(new CallFlowContext(
                        CONTEXT_ID,
                        ContextKind.TASK,
                        "Static analysis",
                        null
                )),
                frames
        );
        GeneratedCallFlowValidation.validate(flow);
        return flow;
    }

    private ScopeBuild buildFunctionScope(
            UMethod method,
            List<String> stack,
            Map<String, ULambdaExpression> lambdaBindings,
            boolean root,
            int depth,
            Set<String> activeMethods
    ) {
        ProgressManager.checkCanceled();
        checkNodeBudget();
        String key = methodKey(method);
        if (!activeMethods.add(key)) {
            throw new IllegalArgumentException("Recursive function expansion is already active: "
                    + methodLabel(method));
        }
        try {
            String symbol = methodSymbol(method);
            String label = methodLabel(method);
            UExpression body = method.getUastBody();
            CallFlowNode entry = node(
                    root ? NodeKind.ENTRY : NodeKind.DECLARATION,
                    locations.forMethod(method, symbol),
                    root
                            ? "从 " + label + " 开始静态分析。"
                            : "进入项目函数 " + label + "。",
                    stack,
                    root ? "Static entry" : "Function body",
                    label
            );

            addNode(entry);
            List<Operation> operations = collectOperations(body);
            List<OperationBuild> operationBuilds = createOperationNodes(
                    operations,
                    stack,
                    lambdaBindings,
                    depth,
                    activeMethods
            );
            CallFlowNode exit = exitNode(body, label, stack, false);

            addNode(exit);
            connectScope(entry, operationBuilds, exit);
            return new ScopeBuild(entry, exit, entry, label, false);
        } finally {
            activeMethods.remove(key);
        }
    }

    private ScopeBuild buildLambdaScope(
            ULambdaExpression lambda,
            List<String> stack,
            String label,
            Map<String, ULambdaExpression> lambdaBindings,
            int depth,
            Set<String> activeMethods
    ) {
        ProgressManager.checkCanceled();
        List<Operation> operations = collectOperations(lambda.getBody());
        if (operations.isEmpty()) {
            return null;
        }
        List<OperationBuild> operationBuilds = createOperationNodes(
                operations,
                stack,
                lambdaBindings,
                depth,
                activeMethods
        );
        CallFlowNode exit = exitNode(lambda.getBody(), label, stack, true);
        addNode(exit);
        connectScope(null, operationBuilds, exit);
        return new ScopeBuild(null, exit, operationBuilds.getFirst().entry(), label, true);
    }

    private Expansion expandCall(
            UCallExpression call,
            List<String> callerStack,
            Map<String, ULambdaExpression> callerBindings,
            int depth,
            Set<String> activeMethods
    ) {
        ProgressManager.checkCanceled();
        if (depth >= MAX_EXPANSION_DEPTH) {
            return null;
        }
        PsiMethod resolved = call.resolve();
        UMethod localTarget = localTarget(resolved);
        if (localTarget != null && localTarget.getUastBody() != null
                && !activeMethods.contains(methodKey(localTarget))) {
            String symbol = methodSymbol(localTarget);
            String label = methodLabel(localTarget);
            CallFlowFrame frame = newFrame(FrameKind.FUNCTION, label, symbol);
            List<String> childStack = append(callerStack, frame.id());
            Map<String, ULambdaExpression> targetBindings = bindLambdaArguments(
                    call,
                    localTarget,
                    callerBindings
            );
            ScopeBuild child = buildFunctionScope(
                    localTarget,
                    childStack,
                    targetBindings,
                    false,
                    depth + 1,
                    activeMethods
            );
            return new Expansion(child, TransitionKind.CALL);
        }

        CallbackSpec callbackSpec = frameworkCallback(resolved);
        if (callbackSpec == null) {
            return null;
        }
        ULambdaExpression callback = callbackArgument(
                call,
                resolved,
                callbackSpec.parameterName(),
                callerBindings
        );
        if (callback == null) {
            return null;
        }
        String callName = callName(call);
        String callbackLabel = callName + " content lambda";
        CallFlowFrame frame = newFrame(FrameKind.LAMBDA, callbackLabel, null);
        ScopeBuild child = buildLambdaScope(
                callback,
                append(callerStack, frame.id()),
                callbackLabel,
                callerBindings,
                depth + 1,
                activeMethods
        );
        if (child == null) {
            frames.remove(frame);
            return null;
        }
        return new Expansion(child, TransitionKind.CALLBACK_ENTER);
    }

    private void connectScope(
            CallFlowNode entry,
            List<OperationBuild> operations,
            CallFlowNode exit
    ) {
        CallFlowNode first = operations.isEmpty() ? exit : operations.getFirst().entry();
        if (entry != null) {
            addEdge(entry, first, EdgeKind.NEXT, "Continue in current function", TransitionKind.CONTINUE);
        }

        for (int index = 0; index < operations.size(); index++) {
            OperationBuild operation = operations.get(index);
            CallFlowNode continuation = index + 1 < operations.size()
                    ? operations.get(index + 1).entry()
                    : exit;
            connectOperation(operation, continuation);
        }
    }

    private void connectOperation(OperationBuild operation, CallFlowNode continuation) {
        if (operation.operation() instanceof BranchOperation branch) {
            connectBranchArm(
                    operation.entry(),
                    operation.trueBranch(),
                    operation.join(),
                    EdgeKind.BRANCH_TRUE,
                    "Condition matched"
            );
            connectBranchArm(
                    operation.entry(),
                    operation.falseBranch(),
                    operation.join(),
                    EdgeKind.BRANCH_FALSE,
                    "Condition did not match"
            );
            addEdge(
                    operation.join(),
                    continuation,
                    EdgeKind.NEXT,
                    "Continue after " + branch.keyword(),
                    TransitionKind.CONTINUE
            );
            return;
        }

        String name = operation.operation() instanceof CallOperation call
                ? callName(call.call())
                : operation.entry().id();
        addEdge(
                operation.entry(),
                continuation,
                EdgeKind.STEP_OVER,
                "Step over " + name,
                TransitionKind.CONTINUE
        );
        Expansion expansion = operation.expansion;
        if (expansion == null) {
            return;
        }
        ScopeBuild child = expansion.scope();
        addEdge(
                operation.entry(),
                child.first(),
                EdgeKind.STEP_INTO,
                expansion.transition() == TransitionKind.CALL
                        ? "Call " + child.label()
                        : "Enter " + child.label(),
                expansion.transition()
        );
        addEdge(
                child.exit(),
                continuation,
                EdgeKind.STEP_OUT,
                "Return from " + child.label(),
                expansion.transition() == TransitionKind.CALL
                        ? TransitionKind.RETURN
                        : TransitionKind.CALLBACK_RETURN
        );
    }

    private void connectBranchArm(
            CallFlowNode branch,
            List<OperationBuild> operations,
            CallFlowNode join,
            EdgeKind edgeKind,
            String label
    ) {
        CallFlowNode first = operations.isEmpty() ? join : operations.getFirst().entry();
        addEdge(branch, first, edgeKind, label, TransitionKind.BRANCH);
        for (int index = 0; index < operations.size(); index++) {
            CallFlowNode continuation = index + 1 < operations.size()
                    ? operations.get(index + 1).entry()
                    : join;
            connectOperation(operations.get(index), continuation);
        }
    }

    private List<OperationBuild> createOperationNodes(
            List<Operation> operations,
            List<String> stack,
            Map<String, ULambdaExpression> lambdaBindings,
            int depth,
            Set<String> activeMethods
    ) {
        List<OperationBuild> builds = new ArrayList<>();
        for (Operation operation : operations) {
            ProgressManager.checkCanceled();
            builds.add(createOperationNode(
                    operation,
                    stack,
                    lambdaBindings,
                    depth,
                    activeMethods
            ));
        }
        return List.copyOf(builds);
    }

    private OperationBuild createOperationNode(
            Operation operation,
            List<String> stack,
            Map<String, ULambdaExpression> lambdaBindings,
            int depth,
            Set<String> activeMethods
    ) {
        if (operation instanceof CallOperation callOperation) {
            UCallExpression call = callOperation.call();
            PsiMethod resolved = call.resolve();
            String name = callName(call);
            String symbol = locationSymbol(resolved);
            UIdentifier identifier = call.getMethodIdentifier();
            CallFlowLocation location = identifier == null
                    ? locations.forElement(call, symbol)
                    : locations.forElement(identifier, symbol);
            CallFlowNode node = node(
                    NodeKind.CALL,
                    location,
                    resolved == null
                            ? "调用 " + name + "；目标未解析时按外部调用处理。"
                            : "调用 " + name + "。",
                    stack,
                    "Static call",
                    name
            );
            addNode(node);
            OperationBuild build = OperationBuild.call(operation, node);
            build.expansion = expandCall(
                    call,
                    stack,
                    lambdaBindings,
                    depth,
                    activeMethods
            );
            return build;
        }

        BranchOperation branch = (BranchOperation) operation;
        CallFlowNode branchNode = node(
                NodeKind.BRANCH,
                locations.forElement(branch.anchor(), null),
                "运行时将在这里根据条件选择后续路径。",
                stack,
                "Runtime branch",
                branch.keyword()
        );
        CallFlowNode join = node(
                NodeKind.NOTE,
                locations.forScopeExit(branch.scope(), null),
                branch.keyword() + " 的分支路径在这里汇合。",
                stack,
                "Branch join",
                branch.keyword() + " join"
        );
        addNode(branchNode);
        List<OperationBuild> trueBranch = createOperationNodes(
                branch.trueOperations(),
                stack,
                lambdaBindings,
                depth,
                activeMethods
        );
        List<OperationBuild> falseBranch = createOperationNodes(
                branch.falseOperations(),
                stack,
                lambdaBindings,
                depth,
                activeMethods
        );
        addNode(join);
        return OperationBuild.branch(operation, branchNode, join, trueBranch, falseBranch);
    }

    private List<Operation> collectOperations(UExpression body) {
        if (body == null) {
            return List.of();
        }
        List<UExpression> expressions = body instanceof UBlockExpression block
                ? block.getExpressions()
                : List.of(body);
        return collectOperations(expressions);
    }

    private List<Operation> collectOperations(List<UExpression> expressions) {
        List<Operation> operations = new ArrayList<>();
        for (UExpression expression : expressions) {
            ProgressManager.checkCanceled();
            UElement control = firstControlFlow(expression);
            if (control instanceof UIfExpression ifExpression) {
                operations.add(new BranchOperation(
                        ifExpression,
                        "if",
                        ifExpression,
                        collectOperations(ifExpression.getThenExpression()),
                        collectOperations(ifExpression.getElseExpression())
                ));
                continue;
            }
            if (control instanceof USwitchExpression switchExpression) {
                operations.add(switchOperation(switchExpression));
                continue;
            }
            collectOutermostCalls(expression, operations);
        }
        return List.copyOf(operations);
    }

    private BranchOperation switchOperation(USwitchExpression expression) {
        List<USwitchClauseExpressionWithBody> clauses = expression.getBody().getExpressions().stream()
                .filter(USwitchClauseExpressionWithBody.class::isInstance)
                .map(USwitchClauseExpressionWithBody.class::cast)
                .toList();
        List<Operation> otherwise = List.of();
        for (int index = clauses.size() - 1; index >= 0; index--) {
            USwitchClauseExpressionWithBody clause = clauses.get(index);
            List<Operation> body = collectOperations(clause.getBody().getExpressions());
            if (clause.getCaseValues().isEmpty()) {
                otherwise = body;
                continue;
            }
            UElement anchor = index == 0 ? expression : clause;
            otherwise = List.of(new BranchOperation(
                    anchor,
                    "when",
                    expression,
                    body,
                    otherwise
            ));
        }
        if (otherwise.size() == 1 && otherwise.getFirst() instanceof BranchOperation branch) {
            return branch;
        }
        return new BranchOperation(
                expression,
                "when",
                expression,
                otherwise,
                List.of()
        );
    }

    private static UElement firstControlFlow(UExpression expression) {
        class Finder extends AbstractUastVisitor {
            private UElement found;

            @Override
            public boolean visitLambdaExpression(ULambdaExpression node) {
                return true;
            }

            @Override
            public boolean visitCallExpression(UCallExpression node) {
                return true;
            }

            @Override
            public boolean visitIfExpression(UIfExpression node) {
                found = node;
                return true;
            }

            @Override
            public boolean visitSwitchExpression(USwitchExpression node) {
                found = node;
                return true;
            }
        }
        Finder finder = new Finder();
        expression.accept(finder);
        return finder.found;
    }

    private static void collectOutermostCalls(
            UExpression expression,
            List<Operation> operations
    ) {
        expression.accept(new AbstractUastVisitor() {
            @Override
            public boolean visitLambdaExpression(ULambdaExpression node) {
                return true;
            }

            @Override
            public boolean visitIfExpression(UIfExpression node) {
                return true;
            }

            @Override
            public boolean visitSwitchExpression(USwitchExpression node) {
                return true;
            }

            @Override
            public boolean visitCallExpression(UCallExpression node) {
                if (node.getSourcePsi() != null && callName(node) != null) {
                    operations.add(new CallOperation(node));
                }
                return true;
            }
        });
    }

    private UMethod localTarget(PsiMethod resolved) {
        if (resolved == null) {
            return null;
        }
        PsiElement navigation = resolved.getNavigationElement();
        UMethod method = UastContextKt.toUElement(navigation, UMethod.class);
        if (method == null) {
            method = UastContextKt.toUElement(resolved, UMethod.class);
        }
        return method != null && locations.isProjectSource(method) ? method : null;
    }

    private static Map<String, ULambdaExpression> bindLambdaArguments(
            UCallExpression call,
            UMethod target,
            Map<String, ULambdaExpression> callerBindings
    ) {
        Map<String, ULambdaExpression> bindings = new LinkedHashMap<>(callerBindings);
        List<org.jetbrains.uast.UParameter> parameters = target.getUastParameters();
        for (int index = 0; index < parameters.size(); index++) {
            UExpression argument = call.getArgumentForParameter(index);
            ULambdaExpression lambda = resolveLambda(argument, callerBindings);
            if (lambda != null && parameters.get(index).getName() != null) {
                bindings.put(parameters.get(index).getName(), lambda);
            }
        }
        return Map.copyOf(bindings);
    }

    private static ULambdaExpression callbackArgument(
            UCallExpression call,
            PsiMethod resolved,
            String parameterName,
            Map<String, ULambdaExpression> bindings
    ) {
        if (resolved != null) {
            PsiParameter[] parameters = resolved.getParameterList().getParameters();
            for (int index = 0; index < parameters.length; index++) {
                if (!parameterName.equals(parameters[index].getName())) {
                    continue;
                }
                ULambdaExpression lambda = resolveLambda(call.getArgumentForParameter(index), bindings);
                if (lambda != null) {
                    return lambda;
                }
            }
            PsiElement navigation = resolved.getNavigationElement();
            if (navigation instanceof KtNamedFunction function) {
                for (int index = 0; index < function.getValueParameters().size(); index++) {
                    if (!parameterName.equals(function.getValueParameters().get(index).getName())) {
                        continue;
                    }
                    ULambdaExpression lambda = resolveLambda(
                            call.getArgumentForParameter(index),
                            bindings
                    );
                    if (lambda != null) {
                        return lambda;
                    }
                }
            }
        }
        for (UExpression argument : call.getValueArguments()) {
            if (argument instanceof UNamedExpression named
                    && parameterName.equals(named.getName())) {
                ULambdaExpression lambda = resolveLambda(named.getExpression(), bindings);
                if (lambda != null) {
                    return lambda;
                }
            }
        }
        List<UExpression> arguments = call.getValueArguments();
        for (int index = arguments.size() - 1; index >= 0; index--) {
            ULambdaExpression lambda = resolveLambda(arguments.get(index), bindings);
            if (lambda != null) {
                return lambda;
            }
        }
        return null;
    }

    private static ULambdaExpression resolveLambda(
            UExpression expression,
            Map<String, ULambdaExpression> bindings
    ) {
        UExpression current = expression;
        while (current instanceof UNamedExpression named) {
            current = named.getExpression();
        }
        while (current instanceof UParenthesizedExpression parenthesized) {
            current = parenthesized.getExpression();
        }
        if (current instanceof ULambdaExpression lambda) {
            return lambda;
        }
        if (current instanceof UReferenceExpression reference) {
            String name = reference.getResolvedName();
            if (name == null && reference.resolve() instanceof PsiNamedElement named) {
                name = named.getName();
            }
            return name == null ? null : bindings.get(name);
        }
        return null;
    }

    private CallFlowNode exitNode(
            UExpression body,
            String label,
            List<String> stack,
            boolean callback
    ) {
        return node(
                NodeKind.RETURN,
                locations.forScopeExit(body, null),
                label + " 执行完成。",
                stack,
                callback ? "Callback completion" : "Function completion",
                label + " return"
        );
    }

    private CallFlowNode node(
            NodeKind kind,
            CallFlowLocation location,
            String summary,
            List<String> stack,
            String phase,
            String idHint
    ) {
        return new CallFlowNode(
                nextNodeId(idHint),
                kind,
                location,
                summary,
                new CallFlowExecution(CONTEXT_ID, stack, phase)
        );
    }

    private CallFlowFrame newFrame(FrameKind kind, String label, String symbol) {
        CallFlowFrame frame = new CallFlowFrame(
                "frame-" + (++frameSequence) + "-" + slug(label),
                kind,
                label,
                symbol
        );
        frames.add(frame);
        return frame;
    }

    private void addNode(CallFlowNode node) {
        checkNodeBudget();
        nodes.add(node);
    }

    private void addEdge(
            CallFlowNode from,
            CallFlowNode to,
            EdgeKind edgeKind,
            String label,
            TransitionKind transitionKind
    ) {
        edges.add(new CallFlowEdge(
                from.id(),
                to.id(),
                edgeKind,
                label,
                new CallFlowTransition(transitionKind)
        ));
    }

    private void checkNodeBudget() {
        if (nodes.size() >= MAX_GENERATED_NODES) {
            throw new IllegalArgumentException(
                    "Static Call Flow exceeds " + MAX_GENERATED_NODES + " nodes"
            );
        }
    }

    private String nextNodeId(String hint) {
        return "node-" + (++nodeSequence) + "-" + slug(hint);
    }

    private static String callName(UCallExpression call) {
        String name = call == null ? null : call.getMethodName();
        if (name == null || name.isBlank() || name.startsWith("<")) {
            return null;
        }
        return name;
    }

    private static String methodLabel(UMethod method) {
        PsiElement navigation = method.getNavigationElement();
        if (navigation instanceof KtNamedFunction function) {
            String functionName = function.getName();
            KtClassOrObject owner = PsiTreeUtil.getParentOfType(
                    function,
                    KtClassOrObject.class
            );
            if (owner != null && owner.getName() != null && functionName != null) {
                return owner.getName() + "." + functionName;
            }
            if (functionName != null) {
                return functionName;
            }
        }
        PsiClass owner = method.getContainingClass();
        String ownerName = owner == null ? null : owner.getName();
        return ownerName == null || ownerName.isBlank()
                ? method.getName()
                : ownerName + "." + method.getName();
    }

    private static String methodSymbol(PsiMethod method) {
        if (method == null) {
            return null;
        }
        PsiElement navigation = method.getNavigationElement();
        if (navigation instanceof KtNamedFunction function) {
            return kotlinFunctionSymbol(function);
        }
        PsiClass owner = method.getContainingClass();
        String ownerName = owner == null ? null : owner.getQualifiedName();
        return ownerName == null || ownerName.isBlank()
                ? method.getName()
                : ownerName + "." + method.getName();
    }

    private static String locationSymbol(PsiMethod method) {
        if (method == null) {
            return null;
        }
        PsiElement navigation = method.getNavigationElement();
        if (navigation instanceof KtNamedFunction function) {
            return kotlinFunctionSymbol(function);
        }
        PsiClass owner = method.getContainingClass();
        String ownerName = owner == null ? null : owner.getQualifiedName();
        String methodName = method.getName();
        if (methodName.indexOf('-') >= 0
                || methodName.contains("$default")
                || (ownerName != null && ownerName.endsWith("Kt"))) {
            return null;
        }
        return methodSymbol(method);
    }

    private static String kotlinFunctionSymbol(KtNamedFunction function) {
        List<String> owners = new ArrayList<>();
        KtClassOrObject owner = PsiTreeUtil.getParentOfType(function, KtClassOrObject.class);
        while (owner != null) {
            if (owner.getName() != null) {
                owners.add(owner.getName());
            }
            owner = PsiTreeUtil.getParentOfType(owner, KtClassOrObject.class, true);
        }
        Collections.reverse(owners);
        String packageName = function.getContainingKtFile().getPackageFqName().asString();
        List<String> segments = new ArrayList<>();
        if (!packageName.isBlank()) {
            segments.add(packageName);
        }
        segments.addAll(owners);
        segments.add(function.getName() == null ? "anonymous" : function.getName());
        return String.join(".", segments);
    }

    private static CallbackSpec frameworkCallback(PsiMethod method) {
        if (method == null) {
            return null;
        }
        PsiElement navigation = method.getNavigationElement();
        String sourceSymbol = navigation instanceof KtNamedFunction function
                ? kotlinFunctionSymbol(function)
                : null;
        PsiClass owner = method.getContainingClass();
        String jvmOwner = owner == null ? null : owner.getQualifiedName();
        for (CallbackSpec spec : FRAMEWORK_CALLBACKS) {
            if (spec.matches(sourceSymbol, jvmOwner, method.getName())) {
                return spec;
            }
        }
        return null;
    }

    static String frameworkCallbackParameter(
            String sourceSymbol,
            String jvmOwner,
            String jvmMethodName
    ) {
        for (CallbackSpec spec : FRAMEWORK_CALLBACKS) {
            if (spec.matches(sourceSymbol, jvmOwner, jvmMethodName)) {
                return spec.parameterName();
            }
        }
        return null;
    }

    private static String sourceMethodName(String jvmMethodName) {
        if (jvmMethodName == null) {
            return null;
        }
        int defaultSuffix = jvmMethodName.indexOf("$default");
        String name = defaultSuffix < 0
                ? jvmMethodName
                : jvmMethodName.substring(0, defaultSuffix);
        int mangledSuffix = name.indexOf('-');
        return mangledSuffix < 0 ? name : name.substring(0, mangledSuffix);
    }

    private static String methodKey(UMethod method) {
        PsiElement source = method.getSourcePsi();
        if (source != null && source.getContainingFile() != null
                && source.getContainingFile().getVirtualFile() != null) {
            return source.getContainingFile().getVirtualFile().getPath()
                    + ":" + source.getTextOffset();
        }
        return methodSymbol(method);
    }

    private static List<String> append(List<String> stack, String frame) {
        List<String> result = new ArrayList<>(stack.size() + 1);
        result.addAll(stack);
        result.add(frame);
        return List.copyOf(result);
    }

    private static String slug(String value) {
        if (value == null || value.isBlank()) {
            return "node";
        }
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "node" : slug;
    }

    private sealed interface Operation permits CallOperation, BranchOperation {
    }

    private record CallOperation(UCallExpression call) implements Operation {
    }

    private record BranchOperation(
            UElement anchor,
            String keyword,
            UExpression scope,
            List<Operation> trueOperations,
            List<Operation> falseOperations
    ) implements Operation {
    }

    private static final class OperationBuild {
        private final Operation operation;
        private final CallFlowNode entry;
        private final CallFlowNode join;
        private final List<OperationBuild> trueBranch;
        private final List<OperationBuild> falseBranch;
        private Expansion expansion;

        private OperationBuild(
                Operation operation,
                CallFlowNode entry,
                CallFlowNode join,
                List<OperationBuild> trueBranch,
                List<OperationBuild> falseBranch
        ) {
            this.operation = operation;
            this.entry = entry;
            this.join = join;
            this.trueBranch = trueBranch;
            this.falseBranch = falseBranch;
        }

        private static OperationBuild call(Operation operation, CallFlowNode entry) {
            return new OperationBuild(operation, entry, null, List.of(), List.of());
        }

        private static OperationBuild branch(
                Operation operation,
                CallFlowNode entry,
                CallFlowNode join,
                List<OperationBuild> trueBranch,
                List<OperationBuild> falseBranch
        ) {
            return new OperationBuild(operation, entry, join, trueBranch, falseBranch);
        }

        private Operation operation() {
            return operation;
        }

        private CallFlowNode entry() {
            return entry;
        }

        private CallFlowNode join() {
            return join;
        }

        private List<OperationBuild> trueBranch() {
            return trueBranch;
        }

        private List<OperationBuild> falseBranch() {
            return falseBranch;
        }
    }

    private record CallbackSpec(
            String sourceSymbol,
            String jvmOwner,
            String methodName,
            String parameterName
    ) {
        private boolean matches(String source, String owner, String jvmMethodName) {
            if (sourceSymbol.equals(source)) {
                return true;
            }
            return jvmOwner.equals(owner) && methodName.equals(sourceMethodName(jvmMethodName));
        }
    }

    private record ScopeBuild(
            CallFlowNode entry,
            CallFlowNode exit,
            CallFlowNode first,
            String label,
            boolean callback
    ) {
    }

    private record Expansion(ScopeBuild scope, TransitionKind transition) {
    }
}
