package com.cockroachlabs.data.ai.springai;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.Filter.ExpressionType;
import org.springframework.ai.vectorstore.filter.Filter.Group;
import org.springframework.ai.vectorstore.filter.Filter.Key;
import org.springframework.ai.vectorstore.filter.Filter.Operand;
import org.springframework.ai.vectorstore.filter.Filter.Value;

public class CockroachDBFilterExpressionConverter {

    private static final String METADATA_COLUMN = "metadata";

    public String convertExpression(Expression expression) {
        if (expression == null) {
            return "";
        }
        return convertOperand(expression);
    }

    private String convertOperand(Operand operand) {
        if (operand instanceof Group group) {
            return "(" + convertOperand(group.content()) + ")";
        }
        if (operand instanceof Expression expression) {
            return convertExpr(expression);
        }
        throw new UnsupportedOperationException(
                "Unsupported operand type: " + operand.getClass().getSimpleName());
    }

    private String convertExpr(Expression expression) {
        ExpressionType type = expression.type();

        if (type == ExpressionType.AND) {
            return "(" + convertOperand(expression.left()) + " AND "
                    + convertOperand(expression.right()) + ")";
        }
        if (type == ExpressionType.OR) {
            return "(" + convertOperand(expression.left()) + " OR "
                    + convertOperand(expression.right()) + ")";
        }
        if (type == ExpressionType.NOT) {
            return "NOT (" + convertOperand(expression.left()) + ")";
        }

        if (type == ExpressionType.IN) {
            return convertIn(expression, false);
        }
        if (type == ExpressionType.NIN) {
            return convertIn(expression, true);
        }

        return convertComparison(expression);
    }

    private String convertComparison(Expression expression) {
        String key = ((Key) expression.left()).key();
        Object value = ((Value) expression.right()).value();
        String operator = switch (expression.type()) {
            case EQ -> "=";
            case NE -> "!=";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            default -> throw new IllegalStateException("Unexpected type: " + expression.type());
        };

        if (value instanceof Number) {
            return "(" + jsonExtract(key) + ")::numeric " + operator + " " + value;
        }
        return jsonExtract(key) + " " + operator + " " + quote(value.toString());
    }

    private String convertIn(Expression expression, boolean negate) {
        String key = ((Key) expression.left()).key();
        @SuppressWarnings("unchecked")
        List<Object> values = ((Value) expression.right()).value() instanceof List<?> list
                ? (List<Object>) list
                : List.of(((Value) expression.right()).value());

        String inClause = values.stream()
                .map(v -> v instanceof Number ? v.toString() : quote(v.toString()))
                .collect(Collectors.joining(", "));

        String prefix = negate ? " NOT IN (" : " IN (";
        return jsonExtract(key) + prefix + inClause + ")";
    }

    private String jsonExtract(String key) {
        return METADATA_COLUMN + "->>'" + key + "'";
    }

    private String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
