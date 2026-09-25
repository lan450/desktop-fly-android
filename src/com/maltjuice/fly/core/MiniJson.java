package com.maltjuice.fly.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent JSON parser (objects, arrays, strings, numbers,
 * booleans, null). No dependencies, so the exact same loader runs on Android
 * and in desktop JVM self-tests.
 */
public final class MiniJson {
    public static final Object[] EMPTY_ARRAY = new Object[0];

    private final String s;
    private int i;

    private MiniJson(String s) { this.s = s; }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) throw new IllegalArgumentException("trailing JSON content at " + p.i);
        return v;
    }

    // ---- typed accessors ----
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        if (v instanceof Map) return (Map<String, Object>) v;
        throw new IllegalArgumentException("expected object, got " + (v == null ? "null" : v.getClass()));
    }
    public static Object[] arr(Object v) {
        if (v == null) return EMPTY_ARRAY;
        if (v instanceof List) return ((List<?>) v).toArray();
        if (v instanceof Object[]) return (Object[]) v;
        throw new IllegalArgumentException("expected array, got " + v.getClass());
    }
    public static String str(Object v) { return v == null ? null : v.toString(); }
    public static double num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) return Double.parseDouble((String) v);
        throw new IllegalArgumentException("expected number, got " + v);
    }
    public static Integer intOrNull(Object v) { return v == null ? null : (int) Math.rint(num(v)); }
    public static String strOrNull(Object v) { return v == null ? null : v.toString(); }

    // ---- parser ----
    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private Object value() {
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String key = string();
            ws();
            if (s.charAt(i) != ':') throw new IllegalArgumentException("expected ':' at " + i);
            i++; ws();
            m.put(key, value());
            ws();
            char c = s.charAt(i);
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; return m; }
            throw new IllegalArgumentException("expected ',' or '}' at " + i);
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i);
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; return l; }
            throw new IllegalArgumentException("expected ',' or ']' at " + i);
        }
    }

    private String string() {
        if (s.charAt(i) != '"') throw new IllegalArgumentException("expected string at " + i);
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new IllegalArgumentException("bad escape \\" + e);
                }
            } else {
                b.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                    || c == '-' || c == '+') { i++; continue; }
            break;
        }
        String t = s.substring(start, i);
        if (t.isEmpty()) throw new IllegalArgumentException("bad number at " + start);
        if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) {
            return Double.parseDouble(t);
        }
        try { return Long.parseLong(t); } catch (NumberFormatException e) { return Double.parseDouble(t); }
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) throw new IllegalArgumentException("expected " + word + " at " + i);
        i += word.length();
    }
}
