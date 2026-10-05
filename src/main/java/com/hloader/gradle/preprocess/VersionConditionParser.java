package com.hloader.gradle.preprocess;

import com.hloader.gradle.VersionResolver;

/**
 * Parses the expression inside a {@code //? if <expression> {} block, e.g. {@code >=1.13},
 * {@code <1.7.10 && !1.6.4}, {@code (1.8.9 || 1.12.2)}. A version id with no comparison operator
 * means equality ({@code 1.12.2} is shorthand for {@code ==1.12.2}). Version ordering comes from
 * {@link VersionResolver#compareVersions}, so ids must be known to Mojang's manifest.
 */
public final class VersionConditionParser {

    private final String source;
    private int pos;

    private VersionConditionParser(String source) {
        this.source = source;
    }

    public static VersionCondition parse(String expression) {
        VersionConditionParser parser = new VersionConditionParser(expression);
        VersionCondition result = parser.parseOr();
        parser.skipWhitespace();
        if (parser.pos != parser.source.length()) {
            throw new IllegalArgumentException("Unexpected trailing input in condition: " + expression);
        }
        return result;
    }

    private VersionCondition parseOr() {
        VersionCondition left = parseAnd();
        while (true) {
            skipWhitespace();
            if (!consume("||")) {
                return left;
            }
            VersionCondition right = parseAnd();
            VersionCondition finalLeft = left;
            left = version -> finalLeft.test(version) || right.test(version);
        }
    }

    private VersionCondition parseAnd() {
        VersionCondition left = parseUnary();
        while (true) {
            skipWhitespace();
            if (!consume("&&")) {
                return left;
            }
            VersionCondition right = parseUnary();
            VersionCondition finalLeft = left;
            left = version -> finalLeft.test(version) && right.test(version);
        }
    }

    private VersionCondition parseUnary() {
        skipWhitespace();
        if (consume("!")) {
            VersionCondition inner = parseUnary();
            return version -> !inner.test(version);
        }
        return parsePrimary();
    }

    private VersionCondition parsePrimary() {
        skipWhitespace();
        if (consume("(")) {
            VersionCondition inner = parseOr();
            skipWhitespace();
            if (!consume(")")) {
                throw new IllegalArgumentException("Missing closing ')' in condition: " + source);
            }
            return inner;
        }
        return parseComparison();
    }

    private static final String[] OPERATORS = {">=", "<=", "==", "!=", ">", "<"};

    private VersionCondition parseComparison() {
        skipWhitespace();
        String operator = "==";
        for (String candidate : OPERATORS) {
            if (source.startsWith(candidate, pos)) {
                operator = candidate;
                pos += candidate.length();
                break;
            }
        }
        skipWhitespace();
        int start = pos;
        while (pos < source.length() && !isDelimiter(source.charAt(pos))) {
            pos++;
        }
        String versionId = source.substring(start, pos);
        if (versionId.isEmpty()) {
            throw new IllegalArgumentException("Expected a version id in condition: " + source);
        }
        String finalOperator = operator;
        return activeVersion -> {
            int comparison = VersionResolver.compareVersions(activeVersion, versionId);
            return switch (finalOperator) {
                case ">=" -> comparison >= 0;
                case "<=" -> comparison <= 0;
                case ">" -> comparison > 0;
                case "<" -> comparison < 0;
                case "!=" -> comparison != 0;
                default -> comparison == 0;
            };
        };
    }

    private boolean isDelimiter(char c) {
        return Character.isWhitespace(c) || c == '(' || c == ')' || c == '&' || c == '|';
    }

    private boolean consume(String token) {
        if (source.startsWith(token, pos)) {
            pos += token.length();
            return true;
        }
        return false;
    }

    private void skipWhitespace() {
        while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
            pos++;
        }
    }
}
