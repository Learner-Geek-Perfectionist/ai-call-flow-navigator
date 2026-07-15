package com.youngx.aicallflow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

final class CallFlowParser {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "_delivery", "version", "title", "nodes", "edges", "entry"
    );
    private static final Set<String> NODE_FIELDS = Set.of(
            "id", "kind", "location", "summary"
    );
    private static final Set<String> LOCATION_FIELDS = Set.of(
            "path", "line", "column", "endLine", "endColumn", "symbol", "anchorText"
    );
    private static final Set<String> EDGE_FIELDS = Set.of(
            "from", "to", "kind", "label"
    );
    private static final TypeAdapter<String> STRICT_STRING = new TypeAdapter<String>() {
        @Override
        public void write(JsonWriter output, String value) throws IOException {
            output.value(value);
        }

        @Override
        public String read(JsonReader input) throws IOException {
            if (input.peek() != JsonToken.STRING) {
                throw new JsonSyntaxException("Expected a JSON string");
            }
            return input.nextString();
        }
    }.nullSafe();

    private static final TypeAdapter<Integer> STRICT_INTEGER = new TypeAdapter<Integer>() {
        @Override
        public void write(JsonWriter output, Integer value) throws IOException {
            output.value(value);
        }

        @Override
        public Integer read(JsonReader input) throws IOException {
            if (input.peek() != JsonToken.NUMBER) {
                throw new JsonSyntaxException("Expected a JSON integer");
            }
            try {
                return StrictJsonNumbers.parseInt(input.nextString());
            } catch (NumberFormatException error) {
                throw new JsonSyntaxException("Expected a 32-bit JSON integer", error);
            }
        }
    }.nullSafe();

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(String.class, STRICT_STRING)
            .registerTypeAdapter(int.class, STRICT_INTEGER)
            .registerTypeAdapter(Integer.class, STRICT_INTEGER)
            .registerTypeAdapter(NodeKind.class, strictEnum(Map.of(
                    "entry", NodeKind.ENTRY,
                    "declaration", NodeKind.DECLARATION,
                    "call", NodeKind.CALL,
                    "branch", NodeKind.BRANCH,
                    "return", NodeKind.RETURN,
                    "async", NodeKind.ASYNC,
                    "callback", NodeKind.CALLBACK,
                    "note", NodeKind.NOTE
            )))
            .registerTypeAdapter(EdgeKind.class, strictEnum(Map.of(
                    "next", EdgeKind.NEXT,
                    "step_into", EdgeKind.STEP_INTO,
                    "step_over", EdgeKind.STEP_OVER,
                    "step_out", EdgeKind.STEP_OUT,
                    "branch_true", EdgeKind.BRANCH_TRUE,
                    "branch_false", EdgeKind.BRANCH_FALSE,
                    "return", EdgeKind.RETURN,
                    "async", EdgeKind.ASYNC,
                    "callback", EdgeKind.CALLBACK
            )))
            .create();

    private CallFlowParser() {
    }

    static CallFlow parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Invalid call flow JSON: request body is required");
        }

        try {
            JsonElement element = StrictJsonTreeParser.parse(json);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Invalid call flow JSON: root must be a JSON object");
            }
            return parse(element.getAsJsonObject());
        } catch (StrictJsonTreeParser.StructuralException error) {
            throw new IllegalArgumentException(
                    "Invalid call flow JSON: " + error.getMessage(),
                    error
            );
        } catch (JsonParseException | IOException error) {
            throw new IllegalArgumentException("Invalid call flow JSON: malformed JSON", error);
        }
    }

    static CallFlow parse(JsonObject root) {
        validateFields(root);
        CallFlow flow;
        try {
            flow = GSON.fromJson(root, CallFlow.class);
        } catch (JsonParseException error) {
            throw new IllegalArgumentException("Invalid call flow JSON: malformed JSON", error);
        }
        CallFlowValidation.validate(flow);
        return flow;
    }

    private static void validateFields(JsonObject root) {
        rejectUnknownFields(root, ROOT_FIELDS, "Call Flow");
        validateObjectArray(root.get("nodes"), NODE_FIELDS, "nodes", true);
        validateObjectArray(root.get("edges"), EDGE_FIELDS, "edges", false);
    }

    private static void validateObjectArray(
            JsonElement element,
            Set<String> fields,
            String name,
            boolean containsLocation
    ) {
        if (element == null || !element.isJsonArray()) {
            return;
        }
        JsonArray values = element.getAsJsonArray();
        for (int index = 0; index < values.size(); index++) {
            JsonElement value = values.get(index);
            if (!value.isJsonObject()) {
                continue;
            }
            JsonObject object = value.getAsJsonObject();
            String objectName = name + "[" + index + "]";
            rejectUnknownFields(object, fields, objectName);
            if (containsLocation) {
                JsonElement location = object.get("location");
                if (location != null && location.isJsonObject()) {
                    rejectUnknownFields(
                            location.getAsJsonObject(),
                            LOCATION_FIELDS,
                            objectName + ".location"
                    );
                }
            }
        }
    }

    private static void rejectUnknownFields(
            JsonObject object,
            Set<String> supportedFields,
            String name
    ) {
        for (String field : object.keySet()) {
            if (!supportedFields.contains(field)) {
                throw new IllegalArgumentException(
                        "Invalid call flow: " + name + "." + field + " is not supported"
                );
            }
        }
    }

    private static <E extends Enum<E>> TypeAdapter<E> strictEnum(Map<String, E> protocolValues) {
        return new TypeAdapter<E>() {
            @Override
            public void write(JsonWriter output, E value) throws IOException {
                for (Map.Entry<String, E> entry : protocolValues.entrySet()) {
                    if (entry.getValue() == value) {
                        output.value(entry.getKey());
                        return;
                    }
                }
                throw new JsonSyntaxException("Unsupported enum value: " + value);
            }

            @Override
            public E read(JsonReader input) throws IOException {
                if (input.peek() != JsonToken.STRING) {
                    throw new JsonSyntaxException("Expected a JSON enum string");
                }
                return protocolValues.get(input.nextString());
            }
        }.nullSafe();
    }
}
