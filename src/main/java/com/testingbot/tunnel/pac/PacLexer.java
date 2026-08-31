package com.testingbot.tunnel.pac;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tokenises the JavaScript subset that PAC files are written in.
 *
 * <p>Deliberately not a general JavaScript lexer. A proxy auto-config file decides where a
 * customer's traffic goes, so the failure that matters is silently misreading a construct and
 * routing to the wrong place. Anything outside the supported subset is rejected with the line
 * it appeared on rather than guessed at.
 */
final class PacLexer {

    enum Kind {
        NUMBER, STRING, IDENT, KEYWORD, PUNCT, EOF
    }

    record Token(Kind kind, String text, int line) {
        @Override
        public String toString() {
            return kind + "(" + text + ")";
        }
    }

    private static final Set<String> KEYWORDS = Set.of(
            "function", "var", "let", "const", "if", "else", "return",
            "for", "while", "break", "continue", "true", "false", "null", "undefined",
            "new", "typeof", "in");

    /** Longest first, so "===" is not read as "==" followed by "=". */
    private static final String[] PUNCTUATORS = {
        "===", "!==", "==", "!=", "<=", ">=", "&&", "||", "++", "--",
        "+=", "-=", "*=", "/=", "%=",
        "{", "}", "(", ")", "[", "]", ";", ",", "<", ">", "+", "-", "*", "/", "%",
        "!", "?", ":", ".", "="
    };

    private final String source;
    private int pos;
    private int line = 1;

    PacLexer(String source) {
        this.source = source;
    }

    List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= source.length()) {
                tokens.add(new Token(Kind.EOF, "", line));
                return tokens;
            }
            char c = source.charAt(pos);
            if (c == '"' || c == '\'') {
                tokens.add(readString(c));
            } else if (Character.isDigit(c) || (c == '.' && pos + 1 < source.length()
                    && Character.isDigit(source.charAt(pos + 1)))) {
                tokens.add(readNumber());
            } else if (Character.isJavaIdentifierStart(c) || c == '$') {
                tokens.add(readIdentifier());
            } else {
                tokens.add(readPunctuator());
            }
        }
    }

    private void skipWhitespaceAndComments() {
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '\n') {
                line++;
                pos++;
            } else if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '/' && pos + 1 < source.length() && source.charAt(pos + 1) == '/') {
                while (pos < source.length() && source.charAt(pos) != '\n') {
                    pos++;
                }
            } else if (c == '/' && pos + 1 < source.length() && source.charAt(pos + 1) == '*') {
                int end = source.indexOf("*/", pos + 2);
                if (end < 0) {
                    throw new PacException("Unterminated /* comment", line);
                }
                for (int i = pos; i < end; i++) {
                    if (source.charAt(i) == '\n') {
                        line++;
                    }
                }
                pos = end + 2;
            } else {
                return;
            }
        }
    }

    private Token readString(char quote) {
        int startLine = line;
        StringBuilder value = new StringBuilder();
        pos++;   // opening quote
        while (true) {
            if (pos >= source.length()) {
                throw new PacException("Unterminated string literal", startLine);
            }
            char c = source.charAt(pos++);
            if (c == quote) {
                return new Token(Kind.STRING, value.toString(), startLine);
            }
            if (c == '\n') {
                throw new PacException("Unterminated string literal", startLine);
            }
            if (c == '\\') {
                if (pos >= source.length()) {
                    throw new PacException("Unterminated escape sequence", startLine);
                }
                char escaped = source.charAt(pos++);
                switch (escaped) {
                    case 'n' -> value.append('\n');
                    case 't' -> value.append('\t');
                    case 'r' -> value.append('\r');
                    case '\\' -> value.append('\\');
                    case '\'' -> value.append('\'');
                    case '"' -> value.append('"');
                    default -> value.append(escaped);
                }
            } else {
                value.append(c);
            }
        }
    }

    private Token readNumber() {
        int start = pos;
        while (pos < source.length()
                && (Character.isDigit(source.charAt(pos)) || source.charAt(pos) == '.')) {
            pos++;
        }
        return new Token(Kind.NUMBER, source.substring(start, pos), line);
    }

    private Token readIdentifier() {
        int start = pos;
        while (pos < source.length()
                && (Character.isJavaIdentifierPart(source.charAt(pos)) || source.charAt(pos) == '$')) {
            pos++;
        }
        String text = source.substring(start, pos);
        return new Token(KEYWORDS.contains(text) ? Kind.KEYWORD : Kind.IDENT, text, line);
    }

    private Token readPunctuator() {
        for (String p : PUNCTUATORS) {
            if (source.startsWith(p, pos)) {
                pos += p.length();
                return new Token(Kind.PUNCT, p, line);
            }
        }
        throw new PacException("Unexpected character '" + source.charAt(pos) + "'", line);
    }
}
