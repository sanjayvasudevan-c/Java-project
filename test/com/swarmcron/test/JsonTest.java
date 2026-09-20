package com.swarmcron.test;

import com.swarmcron.util.Assert;
import com.swarmcron.util.Json;
import com.swarmcron.util.TestSuite;

import java.util.List;
import java.util.Map;

final class JsonTest {

    static boolean run() {
        return new TestSuite("JsonTest")
                .test("round-trips a flat object", JsonTest::roundTripsFlatObject)
                .test("parses nested arrays and objects", JsonTest::parsesNested)
                .test("parses escapes and unicode", JsonTest::parsesEscapes)
                .test("parses numbers including exponents", JsonTest::parsesNumbers)
                .test("writes integral doubles without a decimal point", JsonTest::writesIntegralNumbers)
                .test("rejects malformed input", JsonTest::rejectsMalformed)
                .run();
    }

    private static void roundTripsFlatObject() {
        String text = "{\"id\":\"nightly-backup\",\"enabled\":true,\"timeoutSeconds\":3600}";
        Object parsed = Json.parse(text);
        Assert.that(parsed instanceof Map, "expected a Map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        Assert.equals("nightly-backup", map.get("id"), "id field");
        Assert.equals(Boolean.TRUE, map.get("enabled"), "enabled field");
        Assert.equals(3600.0, map.get("timeoutSeconds"), "timeoutSeconds field");

        String written = Json.write(map);
        Object reparsed = Json.parse(written);
        Assert.equals(map, reparsed, "round trip should be stable");
    }

    @SuppressWarnings("unchecked")
    private static void parsesNested() {
        String text = "{\"command\":[\"/usr/bin/rsync\",\"-a\",\"/srv\"],\"nested\":{\"a\":[1,2,3]}}";
        Map<String, Object> map = (Map<String, Object>) Json.parse(text);
        List<Object> command = (List<Object>) map.get("command");
        Assert.equals(3, command.size(), "command array length");
        Assert.equals("/usr/bin/rsync", command.get(0), "first command arg");
        Map<String, Object> nested = (Map<String, Object>) map.get("nested");
        List<Object> a = (List<Object>) nested.get("a");
        Assert.equals(3, a.size(), "nested array length");
        Assert.equals(2.0, a.get(1), "nested array element");
    }

    private static void parsesEscapes() {
        String text = "\"line1\\nline2\\t\\u0041\"";
        Object parsed = Json.parse(text);
        Assert.equals("line1\nline2\tA", parsed, "escape decoding");
    }

    private static void parsesNumbers() {
        Assert.equals(-42.0, Json.parse("-42"), "negative integer");
        Assert.equals(3.14, Json.parse("3.14"), "decimal");
        Assert.equals(1.5e3, Json.parse("1.5e3"), "exponent");
    }

    private static void writesIntegralNumbers() {
        Assert.equals("3600", Json.write(3600.0), "integral double should print without decimal");
        Assert.equals("3.5", Json.write(3.5), "fractional double should keep decimal");
    }

    private static void rejectsMalformed() {
        boolean threw = false;
        try {
            Json.parse("{\"a\": }");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        Assert.that(threw, "malformed object should raise IllegalArgumentException");
    }
}
