package com.youngx.aicallflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/** Builds one strict JSON tree while rejecting ambiguous or excessively nested input. */
final class StrictJsonTreeParser {
    static final int MAX_NESTING_DEPTH = 128;

    private StrictJsonTreeParser() {
    }

    static JsonElement parse(String sourceJson) throws IOException {
        try {
            return parseStrict(sourceJson);
        } catch (IllegalStateException error) {
            throw new JsonSyntaxException("Malformed JSON", error);
        }
    }

    private static JsonElement parseStrict(String sourceJson) throws IOException {
        JsonReader reader = new JsonReader(new StringReader(sourceJson));
        reader.setStrictness(Strictness.STRICT);

        Deque<ContainerFrame> frames = new ArrayDeque<>();
        JsonElement root = readValue(reader, frames);
        while (!frames.isEmpty()) {
            ContainerFrame frame = frames.peek();
            if (!reader.hasNext()) {
                if (frame.object() != null) {
                    reader.endObject();
                } else {
                    reader.endArray();
                }
                frames.pop();
                continue;
            }

            if (frame.object() != null) {
                String name = reader.nextName();
                if (!frame.fieldNames().add(name)) {
                    throw new StructuralException(
                            "duplicate JSON field: " + name + " at " + reader.getPath()
                    );
                }
                frame.object().add(name, readValue(reader, frames));
            } else {
                frame.array().add(readValue(reader, frames));
            }
        }

        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new JsonSyntaxException("Trailing content is not allowed");
        }
        return root;
    }

    private static JsonElement readValue(
            JsonReader reader,
            Deque<ContainerFrame> frames
    ) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> beginObject(reader, frames);
            case BEGIN_ARRAY -> beginArray(reader, frames);
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new JsonNumberLiteral(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new JsonSyntaxException("Expected a JSON value at " + reader.getPath());
        };
    }

    private static JsonObject beginObject(
            JsonReader reader,
            Deque<ContainerFrame> frames
    ) throws IOException {
        requireAvailableDepth(frames);
        reader.beginObject();
        JsonObject object = new JsonObject();
        frames.push(ContainerFrame.forObject(object));
        return object;
    }

    private static JsonArray beginArray(
            JsonReader reader,
            Deque<ContainerFrame> frames
    ) throws IOException {
        requireAvailableDepth(frames);
        reader.beginArray();
        JsonArray array = new JsonArray();
        frames.push(ContainerFrame.forArray(array));
        return array;
    }

    private static void requireAvailableDepth(Deque<ContainerFrame> frames) {
        if (frames.size() >= MAX_NESTING_DEPTH) {
            throw new StructuralException(
                    "JSON nesting exceeds " + MAX_NESTING_DEPTH + " levels"
            );
        }
    }

    static final class StructuralException extends JsonParseException {
        private StructuralException(String message) {
            super(message);
        }
    }

    private record ContainerFrame(
            JsonObject object,
            JsonArray array,
            Set<String> fieldNames
    ) {
        private static ContainerFrame forObject(JsonObject object) {
            return new ContainerFrame(object, null, new HashSet<>());
        }

        private static ContainerFrame forArray(JsonArray array) {
            return new ContainerFrame(null, array, Set.of());
        }
    }

    /** Keeps the original JSON number literal available to strict model adapters. */
    private static final class JsonNumberLiteral extends Number {
        private final String literal;

        private JsonNumberLiteral(String literal) {
            this.literal = literal;
        }

        @Override
        public int intValue() {
            return new BigDecimal(literal).intValue();
        }

        @Override
        public long longValue() {
            return new BigDecimal(literal).longValue();
        }

        @Override
        public float floatValue() {
            return Float.parseFloat(literal);
        }

        @Override
        public double doubleValue() {
            return Double.parseDouble(literal);
        }

        @Override
        public byte byteValue() {
            return new BigDecimal(literal).byteValue();
        }

        @Override
        public short shortValue() {
            return new BigDecimal(literal).shortValue();
        }

        @Override
        public String toString() {
            return literal;
        }
    }
}
