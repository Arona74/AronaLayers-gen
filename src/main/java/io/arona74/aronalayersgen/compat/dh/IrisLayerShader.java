/*
 * Adapted from Geostep (verdantlayers) 1.3, MIT License, Copyright (c) 2026 Verdant Layers contributors.
 * The full license text is in compat/dh/DhLayerMesh.java and THIRD_PARTY_NOTICES.md.
 */
package io.arona74.aronalayersgen.compat.dh;

import io.arona74.aronalayersgen.AronaLayersGen;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iris's Distant Horizons terrain vertex shader, with the packed Y of exact layer heights decoded
 * (see DhLayerMesh.packVertexY).
 *
 * <p>Shader packs often snap positions to the block grid ({@code floor(position)} and the like) for
 * their effects; applied to a layer cap that would undo its fractional height. Such rounding of the
 * position is rewritten to round the whole-block part only and add the eighths back.
 */
public final class IrisLayerShader {
    private IrisLayerShader() {}

    private static final String MARKER = "/* aronalayersgen fractional DH input */";
    private static final Pattern POSITION = Pattern.compile("(_vert_position\\s*=\\s*)([^;]+)(;)");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
    private static final Pattern ASSIGNMENT = Pattern.compile("(?<![\\w.])(?:(vec[34])\\s+)?([A-Za-z_]\\w*)\\s*=(?!=)\\s*([^;]+);");
    private static final Pattern GRID = Pattern.compile("\\b(floor|ceil|round|roundEven|trunc)\\s*\\(");
    private static final Pattern MATRIX = Pattern.compile("\\b\\w*(?:[Mm]atrix|[Pp]rojection|[Mm]odel[Vv]iew)\\w*\\b|\\btransform\\s*\\(");
    private static final Pattern VERTEX_POSITION_CALL = Pattern.compile("\\bgetVertexPosition\\s*\\(");
    private static final String DECODED =
        "vec3(float(vPosition.x), (float(vPosition.y & 4095u) + float((vPosition.y >> 13u) & 7u) * 0.125), float(vPosition.z))";

    public static String patch(String source) {
        if (source.contains(MARKER)) return source;

        Matcher matcher = POSITION.matcher(source);
        StringBuilder result = new StringBuilder();
        boolean patched = false;
        while (matcher.find()) {
            String expression = matcher.group(2);
            if (expression.contains("vPosition.xyz")) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(
                    matcher.group(1) + expression.replace("vPosition.xyz", DECODED) + matcher.group(3)));
                patched = true;
            }
        }
        if (!patched) {
            throw new IllegalStateException("Arona Layers Generator cannot read this Iris DH vertex format. "
                + "Set dh_exact_layers to false in config/aronalayersgen/layer_config.json for this Iris version.");
        }
        matcher.appendTail(result);
        return MARKER + "\n" + preservePositionRounding(result.toString());
    }

    private static String preservePositionRounding(String source) {
        Matcher main = MAIN.matcher(source);
        if (!main.find()) return source;
        int begin = main.end();
        int end = closing(source, begin - 1, '{', '}');
        if (end < 0) throw new IllegalStateException("Incomplete Iris DH vertex main function");

        String body = source.substring(begin, end);
        Set<String> positions = new HashSet<>();
        positions.add("_vert_position");
        Map<String, String> types = new HashMap<>();
        types.put("_vert_position", "vec3");
        TreeSet<String> helpers = new TreeSet<>();
        Matcher statements = ASSIGNMENT.matcher(body);
        StringBuilder output = new StringBuilder();
        int fixes = 0;

        while (statements.find()) {
            String type = statements.group(1);
            String variable = statements.group(2);
            String expression = statements.group(3);
            if (type != null) types.put(variable, type);

            String vector = types.get(variable);
            if (positions.contains(variable) && vector != null) {
                Matcher calls = GRID.matcher(expression);
                StringBuilder rewritten = new StringBuilder();
                int cursor = 0;
                while (calls.find(cursor)) {
                    int close = closing(expression, calls.end() - 1, '(', ')');
                    if (close < 0) break;
                    String argument = expression.substring(calls.end(), close);
                    if (referencesPosition(argument, positions) && !MATRIX.matcher(argument).find()
                            && !argument.contains("*") && !argument.contains("/") && !GRID.matcher(argument).find()) {
                        helpers.add(calls.group(1) + ":" + vector);
                        fixes++;
                        rewritten.append(expression, cursor, calls.start())
                            .append("aronalayersgen_").append(calls.group(1)).append(vector.substring(3))
                            .append('(').append(argument).append(')');
                    } else {
                        rewritten.append(expression, cursor, close + 1);
                    }
                    cursor = close + 1;
                }
                if (cursor > 0) expression = rewritten.append(expression.substring(cursor)).toString();
            }

            if (referencesPosition(expression, positions) && !MATRIX.matcher(expression).find()) {
                positions.add(variable);
            } else {
                positions.remove(variable);
            }
            String replacement = statements.group().substring(0, statements.start(3) - statements.start()) + expression + ";";
            statements.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        statements.appendTail(output);
        if (fixes == 0) return source;

        StringBuilder functions = new StringBuilder("\n// Keep fractional DH terrain heights through block-grid snapping.\n");
        for (String helper : helpers) {
            String[] parts = helper.split(":");
            String operation = parts[0];
            String vector = parts[1];
            String offset = vector.equals("vec3") ? "vec3(0.0,fraction,0.0)" : "vec4(0.0,fraction,0.0,0.0)";
            functions.append(vector).append(" aronalayersgen_").append(operation).append(vector.substring(3))
                .append('(').append(vector).append(" value) {\n")
                .append(" float fraction=float((vPosition.y >> 13u) & 7u) * 0.125;\n ")
                .append(vector).append(" offset=").append(offset).append(";\n")
                .append(" return ").append(operation).append("(value-offset)+offset;\n}\n");
        }
        AronaLayersGen.LOGGER.info("[DistantHorizons] kept fractional layer heights through {} shader position rounding(s)", fixes);
        return source.substring(0, main.start()) + functions + source.substring(main.start(), begin) + output + source.substring(end);
    }

    private static boolean referencesPosition(String expression, Set<String> positions) {
        if (VERTEX_POSITION_CALL.matcher(expression).find()) return true;
        for (String position : positions) {
            if (Pattern.compile("\\b" + Pattern.quote(position) + "\\b").matcher(expression).find()) return true;
        }
        return false;
    }

    private static int closing(String text, int open, char left, char right) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == left) depth++;
            else if (c == right && --depth == 0) return i;
        }
        return -1;
    }
}
