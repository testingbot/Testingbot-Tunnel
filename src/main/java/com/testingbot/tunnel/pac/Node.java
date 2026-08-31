package com.testingbot.tunnel.pac;

import java.util.List;

/**
 * The AST for the supported PAC subset.
 *
 * <p>Sealed so the set of syntax forms stays closed and reviewable. The evaluator dispatches
 * with instanceof chains rather than a pattern switch, because pattern matching for switch is
 * still a preview feature on the Java 17 baseline; each chain ends in a throw, so a form the
 * evaluator does not know about fails loudly instead of being silently ignored.
 */
sealed interface Node {

    /* ---------------------------------------------------------------- expressions */

    sealed interface Expr extends Node {
    }

    record NumberLiteral(double value) implements Expr {
    }

    record StringLiteral(String value) implements Expr {
    }

    record BooleanLiteral(boolean value) implements Expr {
    }

    record NullLiteral() implements Expr {
    }

    record ArrayLiteral(List<Expr> elements) implements Expr {
    }

    record Identifier(String name, int line) implements Expr {
    }

    record Unary(String op, Expr operand, int line) implements Expr {
    }

    record Binary(String op, Expr left, Expr right, int line) implements Expr {
    }

    /** {@code &&} and {@code ||}, kept separate because they short-circuit. */
    record Logical(String op, Expr left, Expr right) implements Expr {
    }

    record Conditional(Expr test, Expr whenTrue, Expr whenFalse) implements Expr {
    }

    record Assign(String target, String op, Expr value, int line) implements Expr {
    }

    record Call(Expr callee, List<Expr> arguments, int line) implements Expr {
    }

    /** {@code a.b} and {@code a[b]}; {@code computed} distinguishes them. */
    record Member(Expr target, Expr property, boolean computed, int line) implements Expr {
    }

    /* ---------------------------------------------------------------- statements */

    sealed interface Stmt extends Node {
    }

    record VarDecl(String name, Expr initializer, int line) implements Stmt {
    }

    record ExprStmt(Expr expression) implements Stmt {
    }

    record Return(Expr value) implements Stmt {
    }

    record Block(List<Stmt> statements) implements Stmt {
    }

    record If(Expr test, Stmt whenTrue, Stmt whenFalse) implements Stmt {
    }

    record While(Expr test, Stmt body) implements Stmt {
    }

    record For(Stmt init, Expr test, Expr update, Stmt body) implements Stmt {
    }

    record Break() implements Stmt {
    }

    record Continue() implements Stmt {
    }

    record FunctionDecl(String name, List<String> parameters, Block body, int line) implements Stmt {
    }
}
