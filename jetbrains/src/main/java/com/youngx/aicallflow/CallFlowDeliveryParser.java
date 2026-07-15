package com.youngx.aicallflow;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

final class CallFlowDeliveryParser {
    static final String DELIVERY_VERSION = "2.0";
    private static final Set<String> DELIVERY_FIELDS = Set.of(
            "version",
            "requestId",
            "createdAtEpochMs",
            "expiresAtEpochMs"
    );
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,95}");

    record Delivery(
            String version,
            String requestId,
            long createdAtEpochMs,
            long expiresAtEpochMs
    ) {
    }

    record Request(Delivery delivery, CallFlow callFlow, String sourceJson) {
    }

    private CallFlowDeliveryParser() {
    }

    static Request parse(String sourceJson) {
        if (sourceJson == null || sourceJson.isBlank()) {
            throw invalid("request JSON is required", null);
        }

        JsonObject root = parseRootObject(sourceJson);
        Delivery delivery = parseDelivery(root);
        return new Request(delivery, CallFlowParser.parse(sourceJson), sourceJson);
    }

    static Delivery parseDelivery(String sourceJson) {
        if (sourceJson == null || sourceJson.isBlank()) {
            throw invalid("request JSON is required", null);
        }
        return parseDelivery(parseRootObject(sourceJson));
    }

    private static Delivery parseDelivery(JsonObject root) {
        JsonElement deliveryElement = root.get("_delivery");
        if (deliveryElement == null || !deliveryElement.isJsonObject()) {
            throw invalid("_delivery object is required", null);
        }
        JsonObject deliveryObject = deliveryElement.getAsJsonObject();
        for (String field : deliveryObject.keySet()) {
            if (!DELIVERY_FIELDS.contains(field)) {
                throw invalid("_delivery." + field + " is not supported", null);
            }
        }
        Delivery delivery = new Delivery(
                requiredString(deliveryObject, "version", 32),
                requiredString(deliveryObject, "requestId", 96),
                requiredLong(deliveryObject, "createdAtEpochMs"),
                requiredLong(deliveryObject, "expiresAtEpochMs")
        );

        if (!DELIVERY_VERSION.equals(delivery.version())) {
            throw invalid("_delivery.version must be \"" + DELIVERY_VERSION + "\"", null);
        }
        if (!REQUEST_ID.matcher(delivery.requestId()).matches()) {
            throw invalid("_delivery.requestId has an invalid format", null);
        }
        if (delivery.createdAtEpochMs() < 0 || delivery.expiresAtEpochMs() < 0) {
            throw invalid("delivery timestamps must not be negative", null);
        }
        if (delivery.expiresAtEpochMs() <= delivery.createdAtEpochMs()) {
            throw invalid("_delivery.expiresAtEpochMs must be after createdAtEpochMs", null);
        }

        return delivery;
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

    private static JsonObject parseRootObject(String sourceJson) {
        try {
            JsonReader reader = new JsonReader(new StringReader(sourceJson));
            reader.setStrictness(Strictness.STRICT);
            JsonElement element = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw invalid("trailing content is not allowed", null);
            }
            if (!element.isJsonObject()) {
                throw invalid("root must be a JSON object", null);
            }
            return element.getAsJsonObject();
        } catch (JsonParseException | IOException error) {
            throw invalid("malformed JSON", error);
        }
    }

    private static String requiredString(JsonObject object, String name, int maxLength) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw invalid("_delivery." + name + " must be a string", null);
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isString()) {
            throw invalid("_delivery." + name + " must be a string", null);
        }
        String value = primitive.getAsString();
        if (value.isBlank()) {
            throw invalid("_delivery." + name + " is required", null);
        }
        if (value.length() > maxLength) {
            throw invalid("_delivery." + name + " is too long", null);
        }
        return value;
    }

    private static long requiredLong(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw invalid("_delivery." + name + " must be an integer", null);
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw invalid("_delivery." + name + " must be an integer", null);
        }
        try {
            return primitive.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw invalid("_delivery." + name + " must be a 64-bit integer", error);
        }
    }

    private static IllegalArgumentException invalid(String message, Throwable cause) {
        String fullMessage = "Invalid file delivery: " + message;
        return cause == null
                ? new IllegalArgumentException(fullMessage)
                : new IllegalArgumentException(fullMessage, cause);
    }
}
