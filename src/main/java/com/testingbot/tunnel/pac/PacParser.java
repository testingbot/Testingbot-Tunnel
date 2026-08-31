package com.testingbot.tunnel.pac;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive-descent parser for the PAC subset.
 *
 * <p>Covers what proxy auto-config files actually contain: function declarations, variables,
 * {@code if}/{@code else} chains, loops, the usual operators, string and array literals, member
 * access and calls. Everything else -- objects, regular expressions, closures, {@code try},
 * {@code switch}, {@code new} -- is refused by name so the reason is obvious.
 */
final class PacParser {

    private final List<PacLexer.Token> tokens;
    private int pos;

    PacParser(String source) {
        this.tokens = new PacLexer(source).tokenize();
    }

    /** @return every function declared at the top level, by name */
    Map<String, Node.FunctionDecl> parseProgram() {
        Map<String, Node.FunctionDecl> functions = new LinkedHashMap<>();
        List<Node.Stmt> topLevel = new ArrayList<>();
        while (!at(PacLexer.Kind.EOF)) {
            Node.Stmt statement = statement();
            if (statement instanceof Node.FunctionDecl function) {
                functions.put(function.name(), function);
            } else {
                topLevel.add(statement);
            }
        }
        if (functions.isEmpty()) {
            throw new PacException("PAC file declares no functions; expected FindProxyForURL");
        }
        // Top-level statements outside a function are legal JavaScript but vanishingly rare in
        // PAC files, and supporting them would mean modelling global mutable state across
        // evaluations. Refusing is safer than half-supporting.
        if (!topLevel.isEmpty()) {
            throw new PacException(
                    "Statements outside a function are not supported; move them inside FindProxyForURL");
        }
        return functions;
    }

    /* ------------------------------------------------------------------ statements */

    private Node.Stmt statement() {
        PacLexer.Token token = peek();
        if (token.kind() == PacLexer.Kind.KEYWORD) {
            switch (token.text()) {
                case "function":
                    return functionDeclaration();
                case "var":
                case "let":
                case "const":
                    return variableDeclaration();
                case "if":
                    return ifStatement();
                case "for":
                    return forStatement();
                case "while":
                    return whileStatement();
                case "return":
                    return returnStatement();
                case "break":
                    advance();
                    consumeOptionalSemicolon();
                    return new Node.Break();
                case "continue":
                    advance();
                    consumeOptionalSemicolon();
                    return new Node.Continue();
                case "new":
                case "typeof":
                    break;   // handled as expressions, or refused there
                default:
                    throw new PacException("Unsupported statement '" + token.text() + "'", token.line());
            }
        }
        if (is(PacLexer.Kind.PUNCT, "{")) {
            return block();
        }
        if (is(PacLexer.Kind.PUNCT, ";")) {
            advance();
            return new Node.Block(List.of());
        }
        Node.Expr expression = expression();
        consumeOptionalSemicolon();
        return new Node.ExprStmt(expression);
    }

    private Node.FunctionDecl functionDeclaration() {
        int line = peek().line();
        expectKeyword("function");
        String name = expectIdentifier();
        expectPunct("(");
        List<String> parameters = new ArrayList<>();
        while (!is(PacLexer.Kind.PUNCT, ")")) {
            parameters.add(expectIdentifier());
            if (is(PacLexer.Kind.PUNCT, ",")) {
                advance();
            }
        }
        expectPunct(")");
        return new Node.FunctionDecl(name, parameters, block(), line);
    }

    private Node.Block block() {
        expectPunct("{");
        List<Node.Stmt> statements = new ArrayList<>();
        while (!is(PacLexer.Kind.PUNCT, "}")) {
            if (at(PacLexer.Kind.EOF)) {
                throw new PacException("Unexpected end of file; missing '}'", peek().line());
            }
            statements.add(statement());
        }
        expectPunct("}");
        return new Node.Block(statements);
    }

    private Node.Stmt variableDeclaration() {
        int line = peek().line();
        advance();   // var / let / const
        List<Node.Stmt> declarations = new ArrayList<>();
        while (true) {
            String name = expectIdentifier();
            Node.Expr initializer = null;
            if (is(PacLexer.Kind.PUNCT, "=")) {
                advance();
                initializer = assignment();
            }
            declarations.add(new Node.VarDecl(name, initializer, line));
            if (is(PacLexer.Kind.PUNCT, ",")) {
                advance();
                continue;
            }
            break;
        }
        consumeOptionalSemicolon();
        return declarations.size() == 1 ? declarations.get(0) : new Node.Block(declarations);
    }

    private Node.Stmt ifStatement() {
        expectKeyword("if");
        expectPunct("(");
        Node.Expr test = expression();
        expectPunct(")");
        Node.Stmt whenTrue = statement();
        Node.Stmt whenFalse = null;
        if (is(PacLexer.Kind.KEYWORD, "else")) {
            advance();
            whenFalse = statement();
        }
        return new Node.If(test, whenTrue, whenFalse);
    }

    private Node.Stmt whileStatement() {
        expectKeyword("while");
        expectPunct("(");
        Node.Expr test = expression();
        expectPunct(")");
        return new Node.While(test, statement());
    }

    private Node.Stmt forStatement() {
        int line = peek().line();
        expectKeyword("for");
        expectPunct("(");
        // for..in over an object has no meaning in the subset (no objects), so refuse it early
        // rather than mis-parsing it as a C-style header.
        Node.Stmt init = null;
        if (!is(PacLexer.Kind.PUNCT, ";")) {
            init = is(PacLexer.Kind.KEYWORD, "var") || is(PacLexer.Kind.KEYWORD, "let")
                    || is(PacLexer.Kind.KEYWORD, "const")
                    ? variableDeclarationNoSemicolon()
                    : new Node.ExprStmt(expression());
        }
        if (is(PacLexer.Kind.KEYWORD, "in")) {
            throw new PacException("for..in is not supported", line);
        }
        expectPunct(";");
        Node.Expr test = is(PacLexer.Kind.PUNCT, ";") ? null : expression();
        expectPunct(";");
        Node.Expr update = is(PacLexer.Kind.PUNCT, ")") ? null : expression();
        expectPunct(")");
        return new Node.For(init, test, update, statement());
    }

    /** The {@code var i = 0} inside a {@code for} header, which must not eat the {@code ;}. */
    private Node.Stmt variableDeclarationNoSemicolon() {
        int line = peek().line();
        advance();
        String name = expectIdentifier();
        Node.Expr initializer = null;
        if (is(PacLexer.Kind.PUNCT, "=")) {
            advance();
            initializer = assignment();
        }
        return new Node.VarDecl(name, initializer, line);
    }

    private Node.Stmt returnStatement() {
        expectKeyword("return");
        Node.Expr value = null;
        if (!is(PacLexer.Kind.PUNCT, ";") && !is(PacLexer.Kind.PUNCT, "}")) {
            value = expression();
        }
        consumeOptionalSemicolon();
        return new Node.Return(value);
    }

    /* ----------------------------------------------------------------- expressions */

    private Node.Expr expression() {
        return assignment();
    }

    private Node.Expr assignment() {
        Node.Expr left = conditional();
        if (at(PacLexer.Kind.PUNCT)) {
            String op = peek().text();
            if (op.equals("=") || op.equals("+=") || op.equals("-=")
                    || op.equals("*=") || op.equals("/=") || op.equals("%=")) {
                int line = peek().line();
                if (!(left instanceof Node.Identifier target)) {
                    throw new PacException("Can only assign to a variable", line);
                }
                advance();
                return new Node.Assign(target.name(), op, assignment(), line);
            }
        }
        return left;
    }

    private Node.Expr conditional() {
        Node.Expr test = logicalOr();
        if (is(PacLexer.Kind.PUNCT, "?")) {
            advance();
            Node.Expr whenTrue = assignment();
            expectPunct(":");
            return new Node.Conditional(test, whenTrue, assignment());
        }
        return test;
    }

    private Node.Expr logicalOr() {
        Node.Expr left = logicalAnd();
        while (is(PacLexer.Kind.PUNCT, "||")) {
            advance();
            left = new Node.Logical("||", left, logicalAnd());
        }
        return left;
    }

    private Node.Expr logicalAnd() {
        Node.Expr left = equality();
        while (is(PacLexer.Kind.PUNCT, "&&")) {
            advance();
            left = new Node.Logical("&&", left, equality());
        }
        return left;
    }

    private Node.Expr equality() {
        Node.Expr left = relational();
        while (isAnyPunct("==", "!=", "===", "!==")) {
            PacLexer.Token op = advance();
            left = new Node.Binary(op.text(), left, relational(), op.line());
        }
        return left;
    }

    private Node.Expr relational() {
        Node.Expr left = additive();
        while (isAnyPunct("<", ">", "<=", ">=")) {
            PacLexer.Token op = advance();
            left = new Node.Binary(op.text(), left, additive(), op.line());
        }
        return left;
    }

    private Node.Expr additive() {
        Node.Expr left = multiplicative();
        while (isAnyPunct("+", "-")) {
            PacLexer.Token op = advance();
            left = new Node.Binary(op.text(), left, multiplicative(), op.line());
        }
        return left;
    }

    private Node.Expr multiplicative() {
        Node.Expr left = unary();
        while (isAnyPunct("*", "/", "%")) {
            PacLexer.Token op = advance();
            left = new Node.Binary(op.text(), left, unary(), op.line());
        }
        return left;
    }

    private Node.Expr unary() {
        if (isAnyPunct("!", "-", "+")) {
            PacLexer.Token op = advance();
            return new Node.Unary(op.text(), unary(), op.line());
        }
        if (is(PacLexer.Kind.KEYWORD, "typeof")) {
            PacLexer.Token op = advance();
            return new Node.Unary("typeof", unary(), op.line());
        }
        if (isAnyPunct("++", "--")) {
            // Only the postfix form appears in PAC loops, and supporting one spelling of an
            // operator but not the other would be worse than refusing both prefixes.
            throw new PacException("Prefix ++/-- is not supported; use i += 1", peek().line());
        }
        return postfix();
    }

    private Node.Expr postfix() {
        Node.Expr expr = primary();
        while (true) {
            if (is(PacLexer.Kind.PUNCT, ".")) {
                int line = advance().line();
                String name = expectIdentifierOrKeyword();
                expr = new Node.Member(expr, new Node.StringLiteral(name), false, line);
            } else if (is(PacLexer.Kind.PUNCT, "[")) {
                int line = advance().line();
                Node.Expr property = expression();
                expectPunct("]");
                expr = new Node.Member(expr, property, true, line);
            } else if (is(PacLexer.Kind.PUNCT, "(")) {
                int line = advance().line();
                List<Node.Expr> arguments = new ArrayList<>();
                while (!is(PacLexer.Kind.PUNCT, ")")) {
                    arguments.add(assignment());
                    if (is(PacLexer.Kind.PUNCT, ",")) {
                        advance();
                    }
                }
                expectPunct(")");
                expr = new Node.Call(expr, arguments, line);
            } else if (isAnyPunct("++", "--")) {
                // i++ as a statement or loop update: desugar to i = i + 1.
                PacLexer.Token op = advance();
                if (!(expr instanceof Node.Identifier target)) {
                    throw new PacException("Can only increment a variable", op.line());
                }
                expr = new Node.Assign(target.name(), op.text().equals("++") ? "+=" : "-=",
                        new Node.NumberLiteral(1), op.line());
            } else {
                return expr;
            }
        }
    }

    private Node.Expr primary() {
        PacLexer.Token token = peek();
        switch (token.kind()) {
            case NUMBER:
                advance();
                try {
                    return new Node.NumberLiteral(Double.parseDouble(token.text()));
                } catch (NumberFormatException bad) {
                    throw new PacException("Invalid number '" + token.text() + "'", token.line());
                }
            case STRING:
                advance();
                return new Node.StringLiteral(token.text());
            case IDENT:
                advance();
                return new Node.Identifier(token.text(), token.line());
            case KEYWORD:
                switch (token.text()) {
                    case "true":
                        advance();
                        return new Node.BooleanLiteral(true);
                    case "false":
                        advance();
                        return new Node.BooleanLiteral(false);
                    case "null":
                    case "undefined":
                        advance();
                        return new Node.NullLiteral();
                    case "function":
                        throw new PacException("Nested/anonymous functions are not supported",
                                token.line());
                    case "new":
                        throw new PacException("'new' is not supported", token.line());
                    default:
                        throw new PacException("Unexpected keyword '" + token.text() + "'",
                                token.line());
                }
            case PUNCT:
                if (token.text().equals("(")) {
                    advance();
                    Node.Expr inner = expression();
                    expectPunct(")");
                    return inner;
                }
                if (token.text().equals("[")) {
                    advance();
                    List<Node.Expr> elements = new ArrayList<>();
                    while (!is(PacLexer.Kind.PUNCT, "]")) {
                        elements.add(assignment());
                        if (is(PacLexer.Kind.PUNCT, ",")) {
                            advance();
                        }
                    }
                    expectPunct("]");
                    return new Node.ArrayLiteral(elements);
                }
                if (token.text().equals("{")) {
                    throw new PacException("Object literals are not supported", token.line());
                }
                if (token.text().equals("/")) {
                    throw new PacException("Regular expressions are not supported; "
                            + "use shExpMatch()", token.line());
                }
                throw new PacException("Unexpected '" + token.text() + "'", token.line());
            default:
                throw new PacException("Unexpected end of file", token.line());
        }
    }

    /* --------------------------------------------------------------------- helpers */

    private PacLexer.Token peek() {
        return tokens.get(pos);
    }

    private PacLexer.Token advance() {
        return tokens.get(pos++);
    }

    private boolean at(PacLexer.Kind kind) {
        return peek().kind() == kind;
    }

    private boolean is(PacLexer.Kind kind, String text) {
        return peek().kind() == kind && peek().text().equals(text);
    }

    private boolean isAnyPunct(String... options) {
        if (!at(PacLexer.Kind.PUNCT)) {
            return false;
        }
        for (String option : options) {
            if (peek().text().equals(option)) {
                return true;
            }
        }
        return false;
    }

    private void expectPunct(String text) {
        if (!is(PacLexer.Kind.PUNCT, text)) {
            throw new PacException("Expected '" + text + "' but found '" + peek().text() + "'",
                    peek().line());
        }
        advance();
    }

    private void expectKeyword(String text) {
        if (!is(PacLexer.Kind.KEYWORD, text)) {
            throw new PacException("Expected '" + text + "'", peek().line());
        }
        advance();
    }

    private String expectIdentifier() {
        if (!at(PacLexer.Kind.IDENT)) {
            throw new PacException("Expected a name but found '" + peek().text() + "'",
                    peek().line());
        }
        return advance().text();
    }

    /** Property names may collide with keywords, e.g. {@code x.in}. */
    private String expectIdentifierOrKeyword() {
        if (!at(PacLexer.Kind.IDENT) && !at(PacLexer.Kind.KEYWORD)) {
            throw new PacException("Expected a property name after '.'", peek().line());
        }
        return advance().text();
    }

    private void consumeOptionalSemicolon() {
        if (is(PacLexer.Kind.PUNCT, ";")) {
            advance();
        }
    }
}
