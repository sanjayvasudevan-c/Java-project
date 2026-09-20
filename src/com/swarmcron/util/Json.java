package com.swarmcron.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal hand-rolled JSON reader/writer  -  just enough for config/job files and
 * the HTTP API. Parses into plain java.util values: Map<String,Object> (object,
 * order-preserving), List<Object> (array), String, Double (number), Boolean,
 * or null. No streaming, no schema validation, no general-purpose ambitions.
 */
public final class Json {

    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object v = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) {
            throw new IllegalArgumentException("Trailing content at position " + p.pos);
        }
        return v;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    /** Pretty-printer used only for human-facing output (dashboard debugging, etc). */
    public static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writePretty(value, sb, 0);
        return sb.toString();
    }

    // ---- writer ----

    private static void writeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String str) {
            writeString(str, sb);
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Number n) {
            writeNumber(n, sb);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                writeValue(e.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object o : list) {
                if (!first) sb.append(',');
                first = false;
                writeValue(o, sb);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Cannot serialize value of type " + value.getClass());
        }
    }

    private static void writePretty(Object value, StringBuilder sb, int indent) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                pad(sb, indent + 1);
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(": ");
                writePretty(e.getValue(), sb, indent + 1);
                if (++i < map.size()) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                pad(sb, indent + 1);
                writePretty(list.get(i), sb, indent + 1);
                if (i + 1 < list.size()) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append(']');
        } else {
            writeValue(value, sb);
        }
    }

    private static void pad(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }

    private static void writeNumber(Number n, StringBuilder sb) {
        double d = n.doubleValue();
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            sb.append(d);
        }
    }

    private static void writeString(String str, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---- reader ----

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWs() {
            while (!atEnd() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        char peek() {
            return s.charAt(pos);
        }

        char next() {
            return s.charAt(pos++);
        }

        void expect(char c) {
            if (atEnd() || next() != c) {
                throw new IllegalArgumentException("Expected '" + c + "' at position " + pos);
            }
        }

        Object parseValue() {
            skipWs();
            if (atEnd()) {
                throw new IllegalArgumentException("Unexpected end of input");
            }
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (!atEnd() && peek() == '}') {
                next();
                return map;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWs();
                if (atEnd()) {
                    throw new IllegalArgumentException("Unterminated object");
                }
                char c = next();
                if (c == '}') break;
                if (c != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}' at position " + pos);
                }
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (!atEnd() && peek() == ']') {
                next();
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (atEnd()) {
                    throw new IllegalArgumentException("Unterminated array");
                }
                char c = next();
                if (c == ']') break;
                if (c != ',') {
                    throw new IllegalArgumentException("Expected ',' or ']' at position " + pos);
                }
            }
            return list;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new IllegalArgumentException("Unterminated string");
                }
                char c = next();
                if (c == '"') break;
                if (c == '\\') {
                    if (atEnd()) {
                        throw new IllegalArgumentException("Unterminated escape sequence");
                    }
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape at position " + pos);
                            }
                            String hex = s.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Invalid escape \\" + e + " at position " + pos);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Boolean parseBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid literal at position " + pos);
        }

        Object parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid literal at position " + pos);
        }

        Double parseNumber() {
            int start = pos;
            if (!atEnd() && peek() == '-') pos++;
            while (!atEnd() && Character.isDigit(peek())) pos++;
            if (!atEnd() && peek() == '.') {
                pos++;
                while (!atEnd() && Character.isDigit(peek())) pos++;
            }
            if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                pos++;
                if (!atEnd() && (peek() == '+' || peek() == '-')) pos++;
                while (!atEnd() && Character.isDigit(peek())) pos++;
            }
            if (pos == start) {
                throw new IllegalArgumentException("Invalid number at position " + pos);
            }
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
