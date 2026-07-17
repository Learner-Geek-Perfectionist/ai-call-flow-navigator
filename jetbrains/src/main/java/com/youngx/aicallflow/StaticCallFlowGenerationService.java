package com.youngx.aicallflow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.CancellablePromise;
import org.jetbrains.uast.UMethod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Coordinates smart-mode static analysis and loads its immutable result into playback. */
public final class StaticCallFlowGenerationService implements Disposable {
    private final Project project;
    private final AtomicLong requestSequence = new AtomicLong();
    private final AtomicReference<CompletableFuture<CallFlow>> latestRequest =
            new AtomicReference<>();
    private final AtomicReference<CancellablePromise<CallFlow>> activeAnalysis =
            new AtomicReference<>();
    private volatile boolean disposed;

    public StaticCallFlowGenerationService(@NotNull Project project) {
        this.project = project;
    }

    public static StaticCallFlowGenerationService getInstance(@NotNull Project project) {
        return project.getService(StaticCallFlowGenerationService.class);
    }

    /** Generates from the project-relative source entry supplied by the explicit AI Skill. */
    CompletionStage<CallFlow> generateAndLoad(@NotNull AnalysisRequest analysisRequest) {
        return generateAndLoad(analysisRequest, () -> true);
    }

    CompletionStage<CallFlow> generateAndLoad(
            @NotNull AnalysisRequest analysisRequest,
            @NotNull BooleanSupplier requestIsCurrent
    ) {
        Objects.requireNonNull(analysisRequest, "analysisRequest");
        Objects.requireNonNull(requestIsCurrent, "requestIsCurrent");
        Request request = beginRequest(requestIsCurrent);
        if (disposed || project.isDisposed()) {
            cancel(request, "Static Call Flow analysis was canceled");
            return request.completion();
        }

        try {
            VirtualFile file = resolveProjectFile(analysisRequest.entry());
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document == null) {
                throw new IllegalArgumentException("Analysis entry source cannot be opened as text");
            }
            int offset = entryOffset(document, analysisRequest.entry());
            return analyze(
                    request,
                    document,
                    document.getModificationStamp(),
                    file,
                    offset,
                    analysisRequest.entry().symbol(),
                    analysisRequest.topic()
            );
        } catch (RuntimeException error) {
            request.completion().completeExceptionally(error);
            return request.completion();
        }
    }

    private CompletionStage<CallFlow> analyze(
            Request request,
            Document document,
            long sourceStamp,
            VirtualFile file,
            int sourceOffset,
            String expectedSymbol,
            String topic
    ) {

        CancellablePromise<CallFlow> analysis = ReadAction
                .nonBlocking(() -> {
                    if (!isSourceCurrent(request, document, sourceStamp, file)) {
                        throw new ProcessCanceledException();
                    }
                    return generate(file, sourceOffset, expectedSymbol, topic);
                })
                .inSmartMode(project)
                .withDocumentsCommitted(project)
                .expireWith(project)
                .expireWhen(() -> !isSourceCurrent(request, document, sourceStamp, file))
                .coalesceBy(this)
                .submit(AppExecutorUtil.getAppExecutorService());
        activeAnalysis.set(analysis);
        request.completion().whenComplete((flow, error) -> {
            if (request.completion().isCancelled()
                    && activeAnalysis.compareAndSet(analysis, null)) {
                analysis.cancel();
            }
        });
        analysis.onSuccess(flow -> loadGenerated(request, analysis, document, sourceStamp, file, flow));
        analysis.onError(error -> analysisFailed(request, analysis, document, sourceStamp, file, error));
        return request.completion();
    }

    @Override
    public void dispose() {
        disposed = true;
        requestSequence.incrementAndGet();
        CancellablePromise<CallFlow> analysis = activeAnalysis.getAndSet(null);
        if (analysis != null) {
            analysis.cancel();
        }
        CompletableFuture<CallFlow> request = latestRequest.getAndSet(null);
        if (request != null) {
            request.completeExceptionally(new CancellationException(
                    "Static Call Flow analysis was canceled"
            ));
        }
    }

    private Request beginRequest() {
        return beginRequest(() -> true);
    }

    private Request beginRequest(BooleanSupplier validity) {
        long id = requestSequence.incrementAndGet();
        CompletableFuture<CallFlow> completion = new CompletableFuture<>();
        CompletableFuture<CallFlow> previousRequest = latestRequest.getAndSet(completion);
        CancellablePromise<CallFlow> previousAnalysis = activeAnalysis.getAndSet(null);
        if (previousAnalysis != null) {
            previousAnalysis.cancel();
        }
        if (previousRequest != null) {
            previousRequest.completeExceptionally(new CancellationException(
                    "Static Call Flow analysis was superseded by a newer request"
            ));
        }
        return new Request(id, completion, validity);
    }

    private void loadGenerated(
            Request request,
            CancellablePromise<CallFlow> analysis,
            Document document,
            long sourceStamp,
            VirtualFile file,
            CallFlow flow
    ) {
        if (!isSourceCurrent(request, document, sourceStamp, file)) {
            finishCanceled(request, analysis);
            return;
        }

        CompletionStage<Void> loading;
        try {
            loading = CallFlowSessionService.getInstance(project).loadAsync(
                    flow,
                    () -> isSourceCurrent(request, document, sourceStamp, file)
            );
        } catch (RuntimeException error) {
            finishFailed(request, analysis, error);
            return;
        }
        loading.whenComplete((ignored, error) -> {
            activeAnalysis.compareAndSet(analysis, null);
            if (!isSourceCurrent(request, document, sourceStamp, file)) {
                cancel(request, "Static Call Flow analysis was canceled");
            } else if (error == null) {
                request.completion().complete(flow);
            } else {
                request.completion().completeExceptionally(error);
            }
        });
    }

    private void analysisFailed(
            Request request,
            CancellablePromise<CallFlow> analysis,
            Document document,
            long sourceStamp,
            VirtualFile file,
            Throwable error
    ) {
        activeAnalysis.compareAndSet(analysis, null);
        if (!isSourceCurrent(request, document, sourceStamp, file)
                || error instanceof CancellationException) {
            cancel(request, "Static Call Flow analysis was canceled");
        } else {
            request.completion().completeExceptionally(error);
        }
    }

    private void finishCanceled(Request request, CancellablePromise<CallFlow> analysis) {
        activeAnalysis.compareAndSet(analysis, null);
        cancel(request, "Static Call Flow analysis was canceled");
    }

    private void finishFailed(
            Request request,
            CancellablePromise<CallFlow> analysis,
            Throwable error
    ) {
        activeAnalysis.compareAndSet(analysis, null);
        if (isCurrent(request)) {
            request.completion().completeExceptionally(error);
        } else {
            cancel(request, "Static Call Flow analysis was canceled");
        }
    }

    private boolean isSourceCurrent(
            Request request,
            Document document,
            long sourceStamp,
            VirtualFile file
    ) {
        return isCurrent(request)
                && file.isValid()
                && document.getModificationStamp() == sourceStamp;
    }

    private boolean isCurrent(Request request) {
        return !disposed
                && !project.isDisposed()
                && requestSequence.get() == request.id()
                && latestRequest.get() == request.completion()
                && request.validity().getAsBoolean()
                && !request.completion().isDone();
    }

    private static void cancel(Request request, String message) {
        request.completion().completeExceptionally(new CancellationException(message));
    }

    CallFlow generateForRequest(@NotNull AnalysisRequest request) {
        VirtualFile file = resolveProjectFile(request.entry());
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            throw new IllegalArgumentException("Analysis entry source cannot be opened as text");
        }
        int offset = entryOffset(document, request.entry());
        return ReadAction.computeBlocking(() -> generate(
                file,
                offset,
                request.entry().symbol(),
                request.topic()
        ));
    }

    private CallFlow generate(
            VirtualFile file,
            int caretOffset,
            String expectedSymbol,
            String topic
    ) {
        if (project.isDisposed()) {
            throw new IllegalStateException("Android Studio project is disposed");
        }
        if (!ProjectFileIndex.getInstance(project).isInContent(file)) {
            throw new IllegalArgumentException(
                    "Analysis entry source is not part of the current project content"
            );
        }
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null) {
            throw new IllegalArgumentException("The current source file has no PSI representation");
        }
        UMethod method = CurrentCallableResolver.resolve(psiFile, caretOffset);
        if (method == null || method.getUastBody() == null) {
            throw new IllegalArgumentException(
                    "Analysis entry does not point inside a Java or Kotlin function body"
            );
        }
        CallFlow generated = new UastCallFlowGenerator(project).generate(method);
        CallFlowNode entryNode = generated.nodes().stream()
                .filter(node -> generated.entry().equals(node.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Generated Call Flow has no entry node"));
        String actualSymbol = entryNode.location() == null ? null : entryNode.location().symbol();
        if (expectedSymbol != null && !expectedSymbol.equals(actualSymbol)) {
            throw new IllegalArgumentException(
                    "Analysis entry symbol does not match the function at the requested location"
                            + " (expected " + expectedSymbol + ", found " + actualSymbol + ")"
            );
        }
        if (topic == null || topic.equals(generated.title())) {
            return generated;
        }
        return new CallFlow(
                generated.version(),
                topic,
                generated.nodes(),
                generated.edges(),
                generated.entry(),
                generated.contexts(),
                generated.frames()
        );
    }

    private VirtualFile resolveProjectFile(AnalysisRequest.Entry entry) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            throw new IllegalArgumentException("Current Android Studio project has no local root");
        }
        try {
            Path root = Path.of(basePath).toRealPath();
            Path source = root.resolve(entry.path()).normalize();
            if (!source.startsWith(root)) {
                throw new IllegalArgumentException("Analysis entry path is outside the current project");
            }
            source = source.toRealPath();
            if (!source.startsWith(root) || !Files.isRegularFile(source)) {
                throw new IllegalArgumentException("Analysis entry path is outside the current project");
            }
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source);
            if (file == null || !file.isValid()) {
                throw new IllegalArgumentException("Analysis entry source file was not found");
            }
            return file;
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "Cannot resolve analysis entry in the current Android Studio project",
                    error
            );
        }
    }

    private static int entryOffset(Document document, AnalysisRequest.Entry entry) {
        int lineIndex = entry.line() - 1;
        if (lineIndex < 0 || lineIndex >= document.getLineCount()) {
            throw new IllegalArgumentException("Analysis entry line is outside the source file");
        }
        int lineStart = document.getLineStartOffset(lineIndex);
        int lineEnd = document.getLineEndOffset(lineIndex);
        long offset = (long) lineStart + entry.column() - 1L;
        if (offset < lineStart || offset > lineEnd) {
            throw new IllegalArgumentException("Analysis entry column is outside the source line");
        }
        return (int) offset;
    }

    private record Request(
            long id,
            CompletableFuture<CallFlow> completion,
            BooleanSupplier validity
    ) {
    }
}
