package com.hloader.gradle.preprocess;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stonecutter-compatible source preprocessor. Toggles regions of a Java source file on or off for
 * the active Minecraft version using comment directives that stay valid Java either way:
 *
 * <pre>{@code
 * //? if >=1.13 {
 * code for 1.13+
 * //? } else {
 * //$$ code for pre-1.13, written commented-out
 * //? }
 * }</pre>
 *
 * <p>Lines inside an {@code if}/{@code else} region are rewritten in place: a line whose region is
 * active loses any leading {@code //$$ } marker, a line whose region is inactive gains one. A bare
 * {@code //? if <condition>} with no trailing {@code {} applies to only the single line that
 * follows it. Directive lines themselves ({@code //? if}, {@code //? } else {}, {@code //? }})
 * always pass through unchanged. See {@link VersionConditionParser} for the condition grammar.
 */
public final class CodePreprocessor {

    private static final Pattern IF_BLOCK = Pattern.compile("^\\s*//\\?\\s*if\\s+(.+?)\\s*\\{\\s*$");
    private static final Pattern IF_LINE = Pattern.compile("^\\s*//\\?\\s*if\\s+(.+?)\\s*$");
    private static final Pattern ELSE_BLOCK = Pattern.compile("^\\s*//\\?\\s*}\\s*else\\s*\\{\\s*$");
    private static final Pattern END_BLOCK = Pattern.compile("^\\s*//\\?\\s*}\\s*$");
    // DOTALL so (.*) also swallows a trailing '\r' left over from CRLF sources split on '\n'.
    private static final Pattern INACTIVE_PREFIX = Pattern.compile("^(\\s*)//\\$\\$ ?(.*)$", Pattern.DOTALL);
    private static final Pattern LEADING_WHITESPACE = Pattern.compile("^(\\s*)(.*)$", Pattern.DOTALL);

    private CodePreprocessor() {
    }

    public static String process(String source, String activeVersion) {
        String[] lines = source.split("\n", -1);
        List<String> output = new ArrayList<>(lines.length);
        Deque<Frame> stack = new ArrayDeque<>();

        for (int i = 0; i < lines.length; i++) {
            int lineNumber = i + 1;
            String line = lines[i];

            Matcher blockMatcher = IF_BLOCK.matcher(line);
            if (blockMatcher.matches()) {
                boolean parentActive = stack.isEmpty() || stack.peek().active;
                boolean ownCondition = evaluate(blockMatcher.group(1), activeVersion, lineNumber);
                stack.push(new Frame(parentActive && ownCondition, ownCondition, parentActive));
                output.add(line);
                continue;
            }

            Matcher elseMatcher = ELSE_BLOCK.matcher(line);
            if (elseMatcher.matches()) {
                if (stack.isEmpty()) {
                    throw new IllegalStateException("'//? } else {' with no matching '//? if' at line " + lineNumber);
                }
                Frame frame = stack.peek();
                frame.active = frame.parentActive && !frame.ownCondition;
                output.add(line);
                continue;
            }

            Matcher endMatcher = END_BLOCK.matcher(line);
            if (endMatcher.matches()) {
                if (stack.isEmpty()) {
                    throw new IllegalStateException("'//? }' with no matching '//? if' at line " + lineNumber);
                }
                stack.pop();
                output.add(line);
                continue;
            }

            Matcher lineMatcher = IF_LINE.matcher(line);
            if (lineMatcher.matches()) {
                boolean parentActive = stack.isEmpty() || stack.peek().active;
                boolean active = parentActive && evaluate(lineMatcher.group(1), activeVersion, lineNumber);
                output.add(line);
                if (i + 1 < lines.length) {
                    i++;
                    output.add(applyActive(lines[i], active));
                }
                continue;
            }

            boolean active = stack.isEmpty() || stack.peek().active;
            output.add(applyActive(line, active));
        }

        if (!stack.isEmpty()) {
            throw new IllegalStateException("Unclosed '//? if' block(s) - missing a matching '//? }'");
        }
        return String.join("\n", output);
    }

    private static boolean evaluate(String conditionExpression, String activeVersion, int lineNumber) {
        try {
            return VersionConditionParser.parse(conditionExpression).test(activeVersion);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid version condition on line " + lineNumber + " (\"" + conditionExpression + "\"): " + e.getMessage(), e);
        }
    }

    private static String applyActive(String line, boolean active) {
        if (line.isBlank()) {
            return line;
        }
        Matcher inactive = INACTIVE_PREFIX.matcher(line);
        if (active) {
            return inactive.matches() ? inactive.group(1) + inactive.group(2) : line;
        }
        if (inactive.matches()) {
            return line;
        }
        Matcher indent = LEADING_WHITESPACE.matcher(line);
        indent.matches();
        return indent.group(1) + "//$$ " + indent.group(2);
    }

    private static final class Frame {
        boolean active;
        final boolean ownCondition;
        final boolean parentActive;

        Frame(boolean active, boolean ownCondition, boolean parentActive) {
            this.active = active;
            this.ownCondition = ownCondition;
            this.parentActive = parentActive;
        }
    }
}
