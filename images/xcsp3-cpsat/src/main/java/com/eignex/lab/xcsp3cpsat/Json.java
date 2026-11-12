package com.eignex.lab.xcsp3cpsat;

import java.math.BigDecimal;
import java.util.Map;

/** The verdict's JSON in Python's `json.dumps` form (`", "` and `": "` separators, ASCII-only strings). */
final class Json {
    private Json() {
    }

    static String write(Object value) {
        StringBuilder s = new StringBuilder();
        write(s, value);
        return s.toString();
    }

    private static void write(StringBuilder s, Object value) {
        if (value == null) s.append("null");
        else if (value instanceof Boolean b) s.append(b ? "true" : "false");
        else if (value instanceof Double d) s.append(pyFloat(d));
        else if (value instanceof Number n) s.append(n.longValue());
        else if (value instanceof String str) string(s, str);
        else if (value instanceof Map<?, ?> m) {
            s.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) s.append(", ");
                first = false;
                string(s, String.valueOf(e.getKey()));
                s.append(": ");
                write(s, e.getValue());
            }
            s.append('}');
        } else throw new IllegalArgumentException("not JSON: " + value);
    }

    private static void string(StringBuilder s, String str) {
        s.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"' -> s.append("\\\"");
                case '\\' -> s.append("\\\\");
                case '\n' -> s.append("\\n");
                case '\r' -> s.append("\\r");
                case '\t' -> s.append("\\t");
                case '\b' -> s.append("\\b");
                case '\f' -> s.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7f) s.append(String.format("\\u%04x", (int) c));
                    else s.append(c);
                }
            }
        }
        s.append('"');
    }

    /** Python's float repr: the shortest round-trip digits, positional for exponents -4..15, else `1e-05` form. */
    static String pyFloat(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        if (d == 0) return (1 / d < 0) ? "-0.0" : "0.0";
        BigDecimal b = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = b.unscaledValue().toString();
        int exponent = digits.length() - 1 - b.scale();
        String sign = d < 0 ? "-" : "";
        if (exponent >= -4 && exponent < 16) {
            String plain = b.toPlainString();
            return sign + (plain.contains(".") ? plain : plain + ".0");
        }
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + String.format("%02d", Math.abs(exponent));
    }

    /** Python's repr of a string. */
    static String pyRepr(String s) {
        char quote = s.contains("'") && !s.contains("\"") ? '"' : '\'';
        StringBuilder out = new StringBuilder().append(quote);
        for (char c : s.toCharArray()) {
            if (c == quote || c == '\\') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < 0x20 || c == 0x7f) out.append(String.format("\\x%02x", (int) c));
            else out.append(c);
        }
        return out.append(quote).toString();
    }

    /** `xml.sax.saxutils.escape`. */
    static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace(">", "&gt;").replace("<", "&lt;");
    }
}
