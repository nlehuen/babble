package org.babblelang.crib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framed NDJSON protocol records and zero-dependency JSON codec shared by {@link CribServer},
 * {@link CribWorkerPool}, and {@link CribWorkerMain} ({@code BIP041-D3}, {@code BIP041-D7}).
 */
public final class CribProtocol {

    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_FAIL = "FAIL";
    public static final String STATUS_INTERNAL_CRASH = "INTERNAL_CRASH";
    public static final String STATUS_TIMEOUT = "TIMEOUT";
    public static final String STATUS_WORKER_CRASHED = "WORKER_CRASHED";
    public static final String STATUS_SKIPPED_NON_PORTABLE = "SKIPPED_NON_PORTABLE";

    public record TrialRequest(
            String batestContent,
            String sourceName,
            List<CribMode> modes,
            boolean verify,
            boolean parity,
            boolean strict,
            boolean checkIr,
            long timeoutMs,
            String filter,
            boolean fresh) {
        public TrialRequest {
            batestContent = batestContent != null ? batestContent : "";
            sourceName =
                    sourceName != null && !sourceName.isBlank() ? sourceName : "crib.batest.md";
            modes = modes != null ? List.copyOf(modes) : CribMode.DEFAULT_JVM_MODES;
            filter = filter != null ? filter : "";
        }
    }

    public record ModeVerdict(
            String status,
            String kind,
            String stdout,
            String stderr,
            String error,
            String behavioralError,
            List<String> irErrors,
            String crash,
            long elapsedMs) {
        public ModeVerdict {
            stdout = stdout != null ? stdout : "";
            stderr = stderr != null ? stderr : "";
            irErrors = irErrors != null ? List.copyOf(irErrors) : List.of();
        }
    }

    public record CaseVerdict(
            String name,
            int index,
            int line,
            boolean passed,
            boolean parityOk,
            String parityDiff,
            Map<String, ModeVerdict> modes) {
        public CaseVerdict {
            name = name != null ? name : "";
            modes = modes != null ? new LinkedHashMap<>(modes) : new LinkedHashMap<>();
        }
    }

    public record TrialSummary(
            int cases,
            int passed,
            int failed,
            int parityDivergences,
            int internalCrashes,
            int timeouts) {}

    public record TrialResponse(
            boolean ok,
            long elapsedMs,
            List<String> requestedModes,
            boolean verify,
            boolean parity,
            boolean strict,
            boolean workerRecycled,
            String poisonReason,
            String parseError,
            TrialSummary summary,
            List<CaseVerdict> cases) {
        public TrialResponse {
            requestedModes = requestedModes != null ? List.copyOf(requestedModes) : List.of();
            cases = cases != null ? List.copyOf(cases) : List.of();
        }

        public TrialResponse withWorkerStatus(
                boolean recycled, String reason, long totalElapsedMs) {
            return new TrialResponse(
                    ok,
                    totalElapsedMs,
                    requestedModes,
                    verify,
                    parity,
                    strict,
                    recycled,
                    reason,
                    parseError,
                    summary,
                    cases);
        }
    }

    private CribProtocol() {}

    // ------------------------------------------------------------------ Request JSON

    public static String encodeRequest(TrialRequest req) {
        StringBuilder sb = new StringBuilder(req.batestContent().length() + 256);
        sb.append("{\"batestContent\":");
        appendJsonString(sb, req.batestContent());
        sb.append(",\"sourceName\":");
        appendJsonString(sb, req.sourceName());
        sb.append(",\"modes\":[");
        for (int i = 0; i < req.modes().size(); i++) {
            if (i > 0) sb.append(',');
            appendJsonString(sb, req.modes().get(i).label());
        }
        sb.append("],\"verify\":").append(req.verify());
        sb.append(",\"parity\":").append(req.parity());
        sb.append(",\"strict\":").append(req.strict());
        sb.append(",\"checkIr\":").append(req.checkIr());
        sb.append(",\"timeoutMs\":").append(req.timeoutMs());
        sb.append(",\"filter\":");
        appendJsonString(sb, req.filter());
        sb.append(",\"fresh\":").append(req.fresh());
        sb.append('}');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static TrialRequest decodeRequest(String json) {
        Map<String, Object> map = parseObject(json);
        String content = asString(map.get("batestContent"), "");
        String sourceName = asString(map.get("sourceName"), "crib.batest.md");
        List<Object> rawModes = (List<Object>) map.get("modes");
        List<CribMode> modes = new ArrayList<>();
        if (rawModes != null) {
            for (Object item : rawModes) {
                modes.add(CribMode.parseOne(String.valueOf(item)));
            }
        }
        boolean verify = asBool(map.get("verify"), true);
        boolean parity = asBool(map.get("parity"), true);
        boolean strict = asBool(map.get("strict"), false);
        boolean checkIr = asBool(map.get("checkIr"), true);
        long timeoutMs = asLong(map.get("timeoutMs"), CribConfig.DEFAULT_TIMEOUT_MS);
        String filter = asString(map.get("filter"), "");
        boolean fresh = asBool(map.get("fresh"), false);
        return new TrialRequest(
                content,
                sourceName,
                modes,
                verify,
                parity,
                strict,
                checkIr,
                timeoutMs,
                filter,
                fresh);
    }

    // ------------------------------------------------------------------ Response JSON

    public static String encodeResponse(TrialResponse resp) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"ok\":").append(resp.ok());
        sb.append(",\"elapsedMs\":").append(resp.elapsedMs());
        sb.append(",\"requestedModes\":[");
        for (int i = 0; i < resp.requestedModes().size(); i++) {
            if (i > 0) sb.append(',');
            appendJsonString(sb, resp.requestedModes().get(i));
        }
        sb.append("],\"verify\":").append(resp.verify());
        sb.append(",\"parity\":").append(resp.parity());
        sb.append(",\"strict\":").append(resp.strict());
        sb.append(",\"workerRecycled\":").append(resp.workerRecycled());
        sb.append(",\"poisonReason\":");
        appendNullableString(sb, resp.poisonReason());
        if (resp.parseError() != null) {
            sb.append(",\"parseError\":");
            appendJsonString(sb, resp.parseError());
        }
        if (resp.summary() != null) {
            TrialSummary s = resp.summary();
            sb.append(",\"summary\":{\"cases\":").append(s.cases());
            sb.append(",\"passed\":").append(s.passed());
            sb.append(",\"failed\":").append(s.failed());
            sb.append(",\"parityDivergences\":").append(s.parityDivergences());
            sb.append(",\"internalCrashes\":").append(s.internalCrashes());
            sb.append(",\"timeouts\":").append(s.timeouts());
            sb.append('}');
        }
        sb.append(",\"cases\":[");
        for (int i = 0; i < resp.cases().size(); i++) {
            if (i > 0) sb.append(',');
            appendCaseVerdict(sb, resp.cases().get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendCaseVerdict(StringBuilder sb, CaseVerdict c) {
        sb.append("{\"name\":");
        appendJsonString(sb, c.name());
        sb.append(",\"index\":").append(c.index());
        sb.append(",\"line\":").append(c.line());
        sb.append(",\"passed\":").append(c.passed());
        sb.append(",\"parityOk\":").append(c.parityOk());
        sb.append(",\"parityDiff\":");
        appendNullableString(sb, c.parityDiff());
        sb.append(",\"modes\":{");
        int idx = 0;
        for (Map.Entry<String, ModeVerdict> e : c.modes().entrySet()) {
            if (idx++ > 0) sb.append(',');
            appendJsonString(sb, e.getKey());
            sb.append(':');
            appendModeVerdict(sb, e.getValue());
        }
        sb.append("}}");
    }

    private static void appendModeVerdict(StringBuilder sb, ModeVerdict m) {
        sb.append("{\"status\":");
        appendJsonString(sb, m.status());
        sb.append(",\"kind\":");
        appendNullableString(sb, m.kind());
        sb.append(",\"stdout\":");
        appendJsonString(sb, m.stdout());
        sb.append(",\"stderr\":");
        appendJsonString(sb, m.stderr());
        sb.append(",\"error\":");
        appendNullableString(sb, m.error());
        sb.append(",\"behavioralError\":");
        appendNullableString(sb, m.behavioralError());
        sb.append(",\"irErrors\":[");
        for (int i = 0; i < m.irErrors().size(); i++) {
            if (i > 0) sb.append(',');
            appendJsonString(sb, m.irErrors().get(i));
        }
        sb.append("],\"crash\":");
        appendNullableString(sb, m.crash());
        sb.append(",\"elapsedMs\":").append(m.elapsedMs());
        sb.append('}');
    }

    @SuppressWarnings("unchecked")
    public static TrialResponse decodeResponse(String json) {
        Map<String, Object> map = parseObject(json);
        boolean ok = asBool(map.get("ok"), false);
        long elapsedMs = asLong(map.get("elapsedMs"), 0L);
        List<String> requestedModes = asStringList((List<Object>) map.get("requestedModes"));
        boolean verify = asBool(map.get("verify"), true);
        boolean parity = asBool(map.get("parity"), true);
        boolean strict = asBool(map.get("strict"), false);
        boolean workerRecycled = asBool(map.get("workerRecycled"), false);
        String poisonReason = asNullableString(map.get("poisonReason"));
        String parseError = asNullableString(map.get("parseError"));
        TrialSummary summary = null;
        if (map.get("summary") instanceof Map<?, ?> sm) {
            Map<String, Object> s = (Map<String, Object>) sm;
            summary =
                    new TrialSummary(
                            (int) asLong(s.get("cases"), 0),
                            (int) asLong(s.get("passed"), 0),
                            (int) asLong(s.get("failed"), 0),
                            (int) asLong(s.get("parityDivergences"), 0),
                            (int) asLong(s.get("internalCrashes"), 0),
                            (int) asLong(s.get("timeouts"), 0));
        }
        List<CaseVerdict> cases = new ArrayList<>();
        if (map.get("cases") instanceof List<?> rawCases) {
            for (Object item : rawCases) {
                Map<String, Object> cm = (Map<String, Object>) item;
                Map<String, ModeVerdict> modeMap = new LinkedHashMap<>();
                if (cm.get("modes") instanceof Map<?, ?> rawModes) {
                    for (Map.Entry<?, ?> entry : rawModes.entrySet()) {
                        Map<String, Object> mm = (Map<String, Object>) entry.getValue();
                        modeMap.put(
                                String.valueOf(entry.getKey()),
                                new ModeVerdict(
                                        asString(mm.get("status"), STATUS_FAIL),
                                        asNullableString(mm.get("kind")),
                                        asString(mm.get("stdout"), ""),
                                        asString(mm.get("stderr"), ""),
                                        asNullableString(mm.get("error")),
                                        asNullableString(mm.get("behavioralError")),
                                        asStringList((List<Object>) mm.get("irErrors")),
                                        asNullableString(mm.get("crash")),
                                        asLong(mm.get("elapsedMs"), 0L)));
                    }
                }
                cases.add(
                        new CaseVerdict(
                                asString(cm.get("name"), ""),
                                (int) asLong(cm.get("index"), 0),
                                (int) asLong(cm.get("line"), 0),
                                asBool(cm.get("passed"), false),
                                asBool(cm.get("parityOk"), true),
                                asNullableString(cm.get("parityDiff")),
                                modeMap));
            }
        }
        return new TrialResponse(
                ok,
                elapsedMs,
                requestedModes,
                verify,
                parity,
                strict,
                workerRecycled,
                poisonReason,
                parseError,
                summary,
                cases);
    }

    /** Formats a {@link TrialResponse} into a compact human-readable plain-text report. */
    public static String formatText(TrialResponse resp) {
        StringBuilder sb = new StringBuilder(256);
        if (resp.parseError() != null) {
            sb.append("CRIB ERROR : ").append(resp.parseError()).append('\n');
            return sb.toString();
        }
        TrialSummary s = resp.summary();
        sb.append(resp.ok() ? "CRIB PASS : " : "CRIB FAIL : ");
        if (s != null) {
            sb.append(s.cases()).append(s.cases() == 1 ? " case (" : " cases (");
            sb.append(s.passed()).append(" passed, ").append(s.failed()).append(" failed");
            if (s.parityDivergences() > 0) {
                sb.append(", ")
                        .append(s.parityDivergences())
                        .append(
                                s.parityDivergences() == 1
                                        ? " parity divergence"
                                        : " parity divergences");
            }
            if (s.internalCrashes() > 0) {
                sb.append(", ").append(s.internalCrashes()).append(" internal crash(es)");
            }
            if (s.timeouts() > 0) {
                sb.append(", ").append(s.timeouts()).append(" timeout(s)");
            }
            sb.append(", ").append(resp.elapsedMs()).append("ms)");
        } else {
            sb.append(resp.elapsedMs()).append("ms");
        }
        if (resp.workerRecycled()) {
            sb.append(" [worker recycled");
            if (resp.poisonReason() != null) {
                sb.append(" : ").append(resp.poisonReason());
            }
            sb.append(']');
        }
        sb.append('\n');
        for (CaseVerdict c : resp.cases()) {
            sb.append(c.passed() ? "[PASS] " : "[FAIL] ")
                    .append(c.name())
                    .append(" (line ")
                    .append(c.line())
                    .append(")\n");
            if (!c.parityOk() && c.parityDiff() != null) {
                sb.append("  PARITY DIVERGENCE : ").append(c.parityDiff()).append('\n');
            }
            for (Map.Entry<String, ModeVerdict> e : c.modes().entrySet()) {
                ModeVerdict m = e.getValue();
                sb.append(
                        String.format("  %-9s : %s (%dms)", e.getKey(), m.status(), m.elapsedMs()));
                if (m.behavioralError() != null && !m.behavioralError().isEmpty()) {
                    sb.append(" — ").append(m.behavioralError().replace('\n', ' '));
                } else if (!m.irErrors().isEmpty()) {
                    sb.append(" — ").append(m.irErrors().get(0).replace('\n', ' '));
                } else if (m.crash() != null && !m.crash().isEmpty()) {
                    String firstLine = m.crash().split("\n", 2)[0];
                    sb.append(" — ").append(firstLine);
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ JSON helpers

    public static void appendNullableString(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
        } else {
            appendJsonString(sb, s);
        }
    }

    public static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
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
        }
        sb.append('"');
    }

    public static Map<String, Object> parseObject(String json) {
        return new JsonParser(json).parseRootObject();
    }

    private static String asString(Object o, String fallback) {
        return o instanceof String s ? s : fallback;
    }

    private static String asNullableString(Object o) {
        return o instanceof String s ? s : null;
    }

    private static boolean asBool(Object o, boolean fallback) {
        return o instanceof Boolean b ? b : fallback;
    }

    private static long asLong(Object o, long fallback) {
        return o instanceof Number n ? n.longValue() : fallback;
    }

    private static List<String> asStringList(List<Object> list) {
        if (list == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            out.add(item == null ? "" : String.valueOf(item));
        }
        return out;
    }

    /** Zero-dependency recursive-descent JSON parser. */
    private static final class JsonParser {
        private final String s;
        private int pos;

        JsonParser(String s) {
            this.s = s != null ? s : "";
        }

        Map<String, Object> parseRootObject() {
            skipWs();
            Map<String, Object> obj = readObject();
            skipWs();
            if (pos != s.length()) {
                throw new IllegalArgumentException("Trailing characters in JSON at pos " + pos);
            }
            return obj;
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                skipWs();
                map.put(key, readValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw new IllegalArgumentException(
                            "Expected ',' or '}' in JSON at pos " + pos + ", got '" + c + "'");
                }
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(readValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException(
                            "Expected ',' or ']' in JSON at pos " + pos + ", got '" + c + "'");
                }
            }
        }

        private Object readValue() {
            char c = peek();
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> {
                    expectLiteral("true");
                    yield Boolean.TRUE;
                }
                case 'f' -> {
                    expectLiteral("false");
                    yield Boolean.FALSE;
                }
                case 'n' -> {
                    expectLiteral("null");
                    yield null;
                }
                default -> readNumber();
            };
        }

        private Number readNumber() {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
            }
            boolean isFloat = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isFloat = true;
                    pos++;
                } else {
                    break;
                }
            }
            String token = s.substring(start, pos);
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Expected JSON value at pos " + start);
            }
            return isFloat ? Double.parseDouble(token) : Long.parseLong(token);
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("Unterminated escape in JSON string");
                    }
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            int cp = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            pos += 4;
                            sb.append((char) cp);
                        }
                        default ->
                                throw new IllegalArgumentException(
                                        "Invalid JSON escape '\\" + esc + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private void expectLiteral(String lit) {
            if (!s.startsWith(lit, pos)) {
                throw new IllegalArgumentException("Expected '" + lit + "' in JSON at pos " + pos);
            }
            pos += lit.length();
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            if (pos >= s.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            return s.charAt(pos);
        }

        private void expect(char expected) {
            char actual = peek();
            if (actual != expected) {
                throw new IllegalArgumentException(
                        "Expected '"
                                + expected
                                + "' in JSON at pos "
                                + pos
                                + ", got '"
                                + actual
                                + "'");
            }
            pos++;
        }
    }
}
