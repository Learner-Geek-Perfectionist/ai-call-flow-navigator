package com.youngx.aicallflow;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict parser for the file-ipc-v3 analysis request. */
final class AnalysisRequestParser {
    static final String DELIVERY_VERSION = "3.0";

    private static final Set<String> ROOT_FIELDS = Set.of(
            "_delivery", "version", "type", "topic", "entry", "strategy"
    );
    private static final Set<String> DELIVERY_FIELDS = Set.of(
            "version", "requestId", "createdAtEpochMs", "expiresAtEpochMs"
    );
    private static final Set<String> ENTRY_FIELDS = Set.of(
            "path", "line", "column", "symbol"
    );
    private static final Set<String> STRATEGY_FIELDS = Set.of("mode", "scope");
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,95}");

    private AnalysisRequestParser() {
    }

    record Envelope(
            AnalysisRequest.Delivery delivery,
            JsonObject root
    ) {
    }

    static Envelope parseEnvelope(String sourceJson) {
        JsonObject root = parseRootObject(sourceJson);
        return new Envelope(parseDelivery(root), root);
    }

    static AnalysisRequest parseRequest(Envelope envelope) {
        JsonObject root = envelope.root();
        rejectUnknownFields(root, ROOT_FIELDS, "root");

        String version = requiredString(root, "version", 32, "version");
        if (!AnalysisRequest.REQUEST_VERSION.equals(version)) {
            throw invalid("version must be \"" + AnalysisRequest.REQUEST_VERSION + "\"");
        }
        String type = requiredString(root, "type", 64, "type");
        if (!AnalysisRequest.REQUEST_TYPE.equals(type)) {
            throw invalid("type must be \"" + AnalysisRequest.REQUEST_TYPE + "\"");
        }
        String topic = requiredString(root, "topic", 16_384, "topic");

        JsonObject entryObject = requiredObject(root, "entry", "entry");
        rejectUnknownFields(entryObject, ENTRY_FIELDS, "entry");
        String path = requiredIdentifierString(
                entryObject,
                "path",
                4_096,
                "entry.path"
        );
        validateProjectRelativePath(path);
        int line = requiredPositiveInt(entryObject, "line", "entry.line");
        int column = requiredPositiveInt(entryObject, "column", "entry.column");
        String symbol = requiredIdentifierString(
                entryObject,
                "symbol",
                512,
                "entry.symbol"
        );

        JsonObject strategyObject = requiredObject(root, "strategy", "strategy");
        rejectUnknownFields(strategyObject, STRATEGY_FIELDS, "strategy");
        String mode = requiredString(strategyObject, "mode", 64, "strategy.mode");
        if (!AnalysisRequest.Strategy.MODE.equals(mode)) {
            throw invalid("strategy.mode must be \"" + AnalysisRequest.Strategy.MODE + "\"");
        }
        String scope = requiredString(strategyObject, "scope", 64, "strategy.scope");
        if (!AnalysisRequest.Strategy.SCOPE.equals(scope)) {
            throw invalid("strategy.scope must be \"" + AnalysisRequest.Strategy.SCOPE + "\"");
        }

        return new AnalysisRequest(
                envelope.delivery(),
                version,
                type,
                topic,
                new AnalysisRequest.Entry(path, line, column, symbol),
                new AnalysisRequest.Strategy(mode, scope)
        );
    }

    static String requestIdFromFileName(Path path) {
        String name = path.getFileName().toString();
        if (!name.startsWith("request-") || !name.endsWith(".json")) {
            return null;
        }
        String requestId = name.substring("request-".length(), name.length() - ".json".length());
        return REQUEST_ID.matcher(requestId).matches() ? requestId : null;
    }

    static String requestIdFromClaimFileName(Path path) {
        String name = path.getFileName().toString();
        String marker = ".json.claim-";
        if (!name.startsWith("request-") || !name.contains(marker)) {
            return null;
        }
        int markerIndex = name.lastIndexOf(marker);
        String requestId = name.substring("request-".length(), markerIndex);
        String claimId = name.substring(markerIndex + marker.length());
        return REQUEST_ID.matcher(requestId).matches()
                && REQUEST_ID.matcher(claimId).matches()
                ? requestId
                : null;
    }

    private static AnalysisRequest.Delivery parseDelivery(JsonObject root) {
        JsonObject delivery = requiredObject(root, "_delivery", "_delivery");
        rejectUnknownFields(delivery, DELIVERY_FIELDS, "_delivery");
        AnalysisRequest.Delivery result = new AnalysisRequest.Delivery(
                requiredString(delivery, "version", 32, "_delivery.version"),
                requiredString(delivery, "requestId", 96, "_delivery.requestId"),
                requiredLong(delivery, "createdAtEpochMs", "_delivery.createdAtEpochMs"),
                requiredLong(delivery, "expiresAtEpochMs", "_delivery.expiresAtEpochMs")
        );
        if (!DELIVERY_VERSION.equals(result.version())) {
            throw invalid("_delivery.version must be \"" + DELIVERY_VERSION + "\"");
        }
        if (!REQUEST_ID.matcher(result.requestId()).matches()) {
            throw invalid("_delivery.requestId has an invalid format");
        }
        if (result.createdAtEpochMs() < 0 || result.expiresAtEpochMs() < 0) {
            throw invalid("delivery timestamps must not be negative");
        }
        if (result.expiresAtEpochMs() <= result.createdAtEpochMs()) {
            throw invalid("_delivery.expiresAtEpochMs must be after createdAtEpochMs");
        }
        return result;
    }

    private static JsonObject parseRootObject(String sourceJson) {
        if (sourceJson == null || sourceJson.isBlank()) {
            throw invalid("request JSON is required");
        }
        try {
            JsonElement element = StrictJsonTreeParser.parse(sourceJson);
            if (!element.isJsonObject()) {
                throw invalid("root must be a JSON object");
            }
            return element.getAsJsonObject();
        } catch (StrictJsonTreeParser.StructuralException error) {
            throw invalid(error.getMessage(), error);
        } catch (JsonParseException | IOException error) {
            throw invalid("malformed JSON", error);
        }
    }

    private static void validateProjectRelativePath(String value) {
        if (value.indexOf('\\') >= 0) {
            throw invalid("entry.path must use '/' separators");
        }
        try {
            Path path = Path.of(value);
            if (path.isAbsolute()
                    || value.startsWith("/")
                    || value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
                throw invalid("entry.path must be project-relative");
            }
            Path normalized = path.normalize();
            if (normalized.getNameCount() == 0
                    || normalized.startsWith("..")
                    || !normalized.toString().replace('\\', '/').equals(value)) {
                throw invalid("entry.path must be a normalized project-relative path");
            }
        } catch (InvalidPathException error) {
            throw invalid("entry.path is invalid", error);
        }
    }

    private static JsonObject requiredObject(JsonObject object, String name, String label) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonObject()) {
            throw invalid(label + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static String requiredString(
            JsonObject object,
            String name,
            int maxLength,
            String label
    ) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw invalid(label + " must be a string");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isString()) {
            throw invalid(label + " must be a string");
        }
        String value = primitive.getAsString();
        if (value.isBlank()) {
            throw invalid(label + " is required");
        }
        if (value.length() > maxLength) {
            throw invalid(label + " is too long");
        }
        return value;
    }

    private static String requiredIdentifierString(
            JsonObject object,
            String name,
            int maxLength,
            String label
    ) {
        String value = requiredString(object, name, maxLength, label);
        if (!value.equals(value.strip())) {
            throw invalid(label + " must not have leading or trailing whitespace");
        }
        if (value.codePoints().anyMatch(AnalysisRequestParser::isIsoControl)) {
            throw invalid(label + " must not contain control characters");
        }
        return value;
    }

    private static boolean isIsoControl(int codePoint) {
        return codePoint <= 0x1F || codePoint >= 0x7F && codePoint <= 0x9F;
    }

    private static int requiredPositiveInt(JsonObject object, String name, String label) {
        long value = requiredLong(object, name, label);
        if (value < 1 || value > Integer.MAX_VALUE) {
            throw invalid(label + " must be a positive 32-bit integer");
        }
        return (int) value;
    }

    private static long requiredLong(JsonObject object, String name, String label) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw invalid(label + " must be an integer");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw invalid(label + " must be an integer");
        }
        try {
            return StrictJsonNumbers.parseLong(primitive.getAsString());
        } catch (NumberFormatException error) {
            throw invalid(label + " must be a 64-bit integer", error);
        }
    }

    private static void rejectUnknownFields(JsonObject object, Set<String> allowed, String label) {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw invalid(label + "." + field + " is not supported");
            }
        }
    }

    private static InvalidAnalysisRequestException invalid(String message) {
        return new InvalidAnalysisRequestException("Invalid analysis request: " + message);
    }

    private static InvalidAnalysisRequestException invalid(String message, Throwable cause) {
        return new InvalidAnalysisRequestException("Invalid analysis request: " + message, cause);
    }

    static final class InvalidAnalysisRequestException extends IllegalArgumentException {
        private InvalidAnalysisRequestException(String message) {
            super(message);
        }

        private InvalidAnalysisRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
