package com.testingbot.tunnel.pac;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates a proxy auto-config file without a JavaScript engine.
 *
 * <p>Nashorn was removed in Java 15 and the alternative is embedding GraalVM's JavaScript, which
 * is a large dependency and a large amount of new attack surface for a process that already sits
 * in the network path. PAC files use a small, well-defined slice of the language, so the slice is
 * implemented directly here and anything outside it is refused rather than approximated.
 *
 * <p>Implements the {@code FindProxyForURL} environment from the original Netscape specification:
 * {@code isPlainHostName}, {@code dnsDomainIs}, {@code localHostOrDomainIs}, {@code isResolvable},
 * {@code isInNet}, {@code dnsResolve}, {@code myIpAddress}, {@code dnsDomainLevels},
 * {@code shExpMatch}, {@code weekdayRange}, {@code dateRange} and {@code timeRange}, plus the
 * handful of String and Array members PAC files reach for.
 *
 * <p>Not thread-confined: {@link #findProxyForUrl} evaluates against fresh scopes each call, so
 * one instance can serve concurrent requests.
 */
public final class PacInterpreter {

    /** Guards against a runaway loop in a hostile or buggy PAC file wedging a request thread. */
    private static final int MAX_STEPS = 200_000;

    private final Map<String, Node.FunctionDecl> functions;
    private final PacEnvironment environment;

    public PacInterpreter(String source) {
        this(source, PacEnvironment.system());
    }

    public PacInterpreter(String source, PacEnvironment environment) {
        this.functions = new PacParser(source).parseProgram();
        this.environment = environment;
        if (!functions.containsKey("FindProxyForURL")) {
            throw new PacException("PAC file does not define FindProxyForURL");
        }
    }

    /**
     * @return the raw directive string, e.g. {@code "PROXY p:8080; DIRECT"}
     * @throws PacException if the file uses an unsupported construct or fails at runtime
     */
    public String findProxyForUrl(String url, String host) {
        Object result = call(functions.get("FindProxyForURL"), List.of(url, host), new int[]{0});
        if (result == null) {
            throw new PacException("FindProxyForURL returned no value");
        }
        return stringOf(result);
    }

    /* ------------------------------------------------------------------ evaluation */

    /** Unwinds a {@code return}; cheaper and clearer than threading a completion flag. */
    private static final class ReturnSignal extends RuntimeException {
        private final Object value;

        ReturnSignal(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    private static final class BreakSignal extends RuntimeException {
        BreakSignal() {
            super(null, null, false, false);
        }
    }

    private static final class ContinueSignal extends RuntimeException {
        ContinueSignal() {
            super(null, null, false, false);
        }
    }

    /** Lexical scope; PAC has no closures, so a parent chain is enough. */
    private static final class Scope {
        private final Scope parent;
        private final Map<String, Object> values = new HashMap<>();

        Scope(Scope parent) {
            this.parent = parent;
        }

        boolean has(String name) {
            return values.containsKey(name) || (parent != null && parent.has(name));
        }

        Object get(String name) {
            if (values.containsKey(name)) {
                return values.get(name);
            }
            if (parent != null) {
                return parent.get(name);
            }
            return null;
        }

        void declare(String name, Object value) {
            values.put(name, value);
        }

        void assign(String name, Object value) {
            if (values.containsKey(name) || parent == null) {
                values.put(name, value);
            } else {
                parent.assign(name, value);
            }
        }
    }

    private Object call(Node.FunctionDecl function, List<Object> arguments, int[] steps) {
        Scope scope = new Scope(null);
        for (int i = 0; i < function.parameters().size(); i++) {
            scope.declare(function.parameters().get(i),
                    i < arguments.size() ? arguments.get(i) : null);
        }
        try {
            execute(function.body(), scope, steps);
        } catch (ReturnSignal signal) {
            return signal.value;
        }
        return null;
    }

    private void execute(Node.Stmt statement, Scope scope, int[] steps) {
        if (++steps[0] > MAX_STEPS) {
            throw new PacException("PAC evaluation exceeded " + MAX_STEPS
                    + " steps; the file probably contains an unbounded loop");
        }
        // instanceof chains rather than a pattern switch: pattern matching for switch is a
        // preview feature on the Java 17 baseline this project compiles against.
        if (statement instanceof Node.Block block) {
            Scope inner = new Scope(scope);
            for (Node.Stmt child : block.statements()) {
                execute(child, inner, steps);
            }
        } else if (statement instanceof Node.VarDecl decl) {
            scope.declare(decl.name(),
                    decl.initializer() == null ? null : evaluate(decl.initializer(), scope, steps));
        } else if (statement instanceof Node.ExprStmt expr) {
            evaluate(expr.expression(), scope, steps);
        } else if (statement instanceof Node.Return ret) {
            throw new ReturnSignal(ret.value() == null ? null : evaluate(ret.value(), scope, steps));
        } else if (statement instanceof Node.If branch) {
            if (truthy(evaluate(branch.test(), scope, steps))) {
                execute(branch.whenTrue(), scope, steps);
            } else if (branch.whenFalse() != null) {
                execute(branch.whenFalse(), scope, steps);
            }
        } else if (statement instanceof Node.While loop) {
            while (truthy(evaluate(loop.test(), scope, steps))) {
                if (++steps[0] > MAX_STEPS) {
                    throw new PacException("PAC evaluation exceeded " + MAX_STEPS + " steps");
                }
                try {
                    execute(loop.body(), scope, steps);
                } catch (BreakSignal stop) {
                    break;
                } catch (ContinueSignal next) {
                    // next iteration
                }
            }
        } else if (statement instanceof Node.For loop) {
            Scope inner = new Scope(scope);
            if (loop.init() != null) {
                execute(loop.init(), inner, steps);
            }
            while (loop.test() == null || truthy(evaluate(loop.test(), inner, steps))) {
                if (++steps[0] > MAX_STEPS) {
                    throw new PacException("PAC evaluation exceeded " + MAX_STEPS + " steps");
                }
                try {
                    execute(loop.body(), inner, steps);
                } catch (BreakSignal stop) {
                    break;
                } catch (ContinueSignal next) {
                    // fall through to the update
                }
                if (loop.update() != null) {
                    evaluate(loop.update(), inner, steps);
                }
            }
        } else if (statement instanceof Node.Break) {
            throw new BreakSignal();
        } else if (statement instanceof Node.Continue) {
            throw new ContinueSignal();
        } else if (!(statement instanceof Node.FunctionDecl)) {
            // FunctionDecl is hoisted at parse time; anything else is a gap in this chain.
            throw new PacException("Internal error: unhandled statement "
                    + statement.getClass().getSimpleName());
        }
    }

    private Object evaluate(Node.Expr expression, Scope scope, int[] steps) {
        if (++steps[0] > MAX_STEPS) {
            throw new PacException("PAC evaluation exceeded " + MAX_STEPS + " steps");
        }
        if (expression instanceof Node.NumberLiteral n) {
            return n.value();
        }
        if (expression instanceof Node.StringLiteral s) {
            return s.value();
        }
        if (expression instanceof Node.BooleanLiteral b) {
            return b.value();
        }
        if (expression instanceof Node.NullLiteral) {
            return null;
        }
        if (expression instanceof Node.ArrayLiteral array) {
            List<Object> values = new ArrayList<>(array.elements().size());
            for (Node.Expr element : array.elements()) {
                values.add(evaluate(element, scope, steps));
            }
            return values;
        }
        if (expression instanceof Node.Identifier id) {
            if (!scope.has(id.name())) {
                throw new PacException("Unknown variable '" + id.name() + "'", id.line());
            }
            return scope.get(id.name());
        }
        if (expression instanceof Node.Unary unary) {
            Object value = evaluate(unary.operand(), scope, steps);
            switch (unary.op()) {
                case "!":
                    return !truthy(value);
                case "-":
                    return -numberOf(value);
                case "+":
                    return numberOf(value);
                case "typeof":
                    return typeOf(value);
                default:
                    throw new PacException("Unsupported operator " + unary.op(), unary.line());
            }
        }
        if (expression instanceof Node.Logical logical) {
            Object left = evaluate(logical.left(), scope, steps);
            if (logical.op().equals("&&")) {
                return truthy(left) ? evaluate(logical.right(), scope, steps) : left;
            }
            return truthy(left) ? left : evaluate(logical.right(), scope, steps);
        }
        if (expression instanceof Node.Binary binaryExpr) {
            return binary(binaryExpr,
                    evaluate(binaryExpr.left(), scope, steps),
                    evaluate(binaryExpr.right(), scope, steps));
        }
        if (expression instanceof Node.Conditional conditional) {
            return truthy(evaluate(conditional.test(), scope, steps))
                    ? evaluate(conditional.whenTrue(), scope, steps)
                    : evaluate(conditional.whenFalse(), scope, steps);
        }
        if (expression instanceof Node.Assign assign) {
            Object value = evaluate(assign.value(), scope, steps);
            if (!assign.op().equals("=")) {
                if (!scope.has(assign.target())) {
                    throw new PacException("Unknown variable '" + assign.target() + "'",
                            assign.line());
                }
                Object current = scope.get(assign.target());
                String op = assign.op().substring(0, 1);
                value = binary(new Node.Binary(op, null, null, assign.line()), current, value);
            }
            scope.assign(assign.target(), value);
            return value;
        }
        if (expression instanceof Node.Member memberExpr) {
            return member(memberExpr, scope, steps);
        }
        if (expression instanceof Node.Call call) {
            return callExpression(call, scope, steps);
        }
        throw new PacException("Internal error: unhandled expression "
                + expression.getClass().getSimpleName());
    }

    private Object binary(Node.Binary binary, Object left, Object right) {
        return switch (binary.op()) {
            case "+" -> (left instanceof String || right instanceof String)
                    ? stringOf(left) + stringOf(right)
                    : numberOf(left) + numberOf(right);
            case "-" -> numberOf(left) - numberOf(right);
            case "*" -> numberOf(left) * numberOf(right);
            case "/" -> numberOf(left) / numberOf(right);
            case "%" -> numberOf(left) % numberOf(right);
            // NaN compares false against everything in JavaScript. Double.compare orders NaN
            // above every other value, so relying on it made "notANumber > 20" quietly true.
            case "<" -> comparable(left, right) && compare(left, right) < 0;
            case ">" -> comparable(left, right) && compare(left, right) > 0;
            case "<=" -> comparable(left, right) && compare(left, right) <= 0;
            case ">=" -> comparable(left, right) && compare(left, right) >= 0;
            // PAC files predate === and use == for value comparison; both compare by value here,
            // which matches how the files are written and read.
            case "==", "===" -> looseEquals(left, right);
            case "!=", "!==" -> !looseEquals(left, right);
            default -> throw new PacException("Unsupported operator " + binary.op(), binary.line());
        };
    }

    private Object member(Node.Member member, Scope scope, int[] steps) {
        Object target = evaluate(member.target(), scope, steps);
        Object property = member.computed()
                ? evaluate(member.property(), scope, steps)
                : ((Node.StringLiteral) member.property()).value();

        if (target instanceof List<?> list) {
            if (property instanceof String name) {
                if (name.equals("length")) {
                    return (double) list.size();
                }
                throw new PacException("Arrays support only .length, not ." + name, member.line());
            }
            int index = (int) numberOf(property);
            return index >= 0 && index < list.size() ? list.get(index) : null;
        }
        if (target instanceof String text) {
            if (property instanceof String name) {
                // A property, not a method: "host.length > 10" is one of the most common things
                // a PAC file does, and returning the method handle here made it compare as NaN.
                if (name.equals("length")) {
                    return (double) text.length();
                }
                return new BoundMethod(text, name, member.line());
            }
            int index = (int) numberOf(property);
            return index >= 0 && index < text.length() ? String.valueOf(text.charAt(index)) : null;
        }
        throw new PacException("Cannot read a property of " + typeOf(target), member.line());
    }

    /** A String method paired with its receiver, resolved when it is called. */
    private record BoundMethod(String receiver, String name, int line) {
    }

    private Object callExpression(Node.Call call, Scope scope, int[] steps) {
        List<Object> arguments = new ArrayList<>(call.arguments().size());
        for (Node.Expr argument : call.arguments()) {
            arguments.add(evaluate(argument, scope, steps));
        }

        if (call.callee() instanceof Node.Identifier id) {
            Node.FunctionDecl declared = functions.get(id.name());
            if (declared != null) {
                return call(declared, arguments, steps);
            }
            return builtin(id.name(), arguments, call.line());
        }
        Object callee = evaluate(call.callee(), scope, steps);
        if (callee instanceof BoundMethod method) {
            return stringMethod(method, arguments);
        }
        throw new PacException("Cannot call " + typeOf(callee), call.line());
    }

    private Object stringMethod(BoundMethod method, List<Object> args) {
        String s = method.receiver();
        return switch (method.name()) {
            case "length" -> (double) s.length();
            case "toLowerCase" -> s.toLowerCase(Locale.ROOT);
            case "toUpperCase" -> s.toUpperCase(Locale.ROOT);
            case "indexOf" -> (double) s.indexOf(stringOf(arg(args, 0)));
            case "lastIndexOf" -> (double) s.lastIndexOf(stringOf(arg(args, 0)));
            case "charAt" -> {
                int i = (int) numberOf(arg(args, 0));
                yield i >= 0 && i < s.length() ? String.valueOf(s.charAt(i)) : "";
            }
            case "substring" -> {
                int from = Math.max(0, Math.min(s.length(), (int) numberOf(arg(args, 0))));
                int to = args.size() > 1
                        ? Math.max(0, Math.min(s.length(), (int) numberOf(arg(args, 1))))
                        : s.length();
                yield from <= to ? s.substring(from, to) : s.substring(to, from);
            }
            case "substr" -> {
                int from = Math.max(0, Math.min(s.length(), (int) numberOf(arg(args, 0))));
                int len = args.size() > 1 ? (int) numberOf(arg(args, 1)) : s.length() - from;
                yield s.substring(from, Math.min(s.length(), from + Math.max(0, len)));
            }
            case "split" -> {
                List<Object> parts = new ArrayList<>();
                for (String part : s.split(java.util.regex.Pattern.quote(stringOf(arg(args, 0))), -1)) {
                    parts.add(part);
                }
                yield parts;
            }
            case "replace" -> {
                // JavaScript's replace(string, string) substitutes only the first match;
                // Java's String.replace substitutes every one.
                String search = stringOf(arg(args, 0));
                int at = s.indexOf(search);
                yield at < 0 || search.isEmpty()
                        ? s
                        : s.substring(0, at) + stringOf(arg(args, 1))
                          + s.substring(at + search.length());
            }
            case "trim" -> s.trim();
            case "startsWith" -> s.startsWith(stringOf(arg(args, 0)));
            case "endsWith" -> s.endsWith(stringOf(arg(args, 0)));
            case "toString" -> s;
            default -> throw new PacException(
                    "Unsupported String method '" + method.name() + "'", method.line());
        };
    }

    private Object builtin(String name, List<Object> args, int line) {
        return switch (name) {
            case "isPlainHostName" -> !stringOf(arg(args, 0)).contains(".");
            case "dnsDomainIs" -> {
                String host = stringOf(arg(args, 0));
                String domain = stringOf(arg(args, 1));
                yield host.equalsIgnoreCase(domain)
                        || host.toLowerCase(Locale.ROOT).endsWith(domain.toLowerCase(Locale.ROOT));
            }
            case "localHostOrDomainIs" -> {
                String host = stringOf(arg(args, 0));
                String fqdn = stringOf(arg(args, 1));
                yield host.equalsIgnoreCase(fqdn)
                        || (!host.contains(".")
                            && fqdn.toLowerCase(Locale.ROOT)
                                   .startsWith(host.toLowerCase(Locale.ROOT) + "."));
            }
            case "isResolvable" -> environment.resolve(stringOf(arg(args, 0))) != null;
            case "dnsResolve" -> environment.resolve(stringOf(arg(args, 0)));
            case "myIpAddress" -> environment.myIpAddress();
            case "dnsDomainLevels" -> {
                String host = stringOf(arg(args, 0));
                yield (double) host.chars().filter(c -> c == '.').count();
            }
            case "isInNet" -> isInNet(stringOf(arg(args, 0)), stringOf(arg(args, 1)),
                    stringOf(arg(args, 2)));
            case "shExpMatch" -> shExpMatch(stringOf(arg(args, 0)), stringOf(arg(args, 1)));
            case "weekdayRange" -> environment.weekdayRange(stringList(args));
            case "dateRange" -> environment.dateRange(stringList(args));
            case "timeRange" -> environment.timeRange(stringList(args));
            case "alert" -> null;   // browsers log it; nothing useful to do here
            default -> throw new PacException("Unknown function '" + name + "'", line);
        };
    }

    /* ------------------------------------------------------------------- built-ins */

    /**
     * Netscape shell-glob matching: {@code *} for any run of characters, {@code ?} for one.
     *
     * <p>Translated to a regex with everything else quoted, so a host containing {@code .} or
     * {@code +} cannot smuggle regex syntax into the pattern.
     */
    static boolean shExpMatch(String value, String pattern) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    regex.append(java.util.regex.Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(java.util.regex.Pattern.quote(literal.toString()));
        }
        return value.matches(regex.toString());
    }

    /** True when {@code host} falls inside {@code pattern}/{@code mask}, both dotted-quad IPv4. */
    static boolean isInNet(String host, String pattern, String mask) {
        long address = ipv4ToLong(host);
        long network = ipv4ToLong(pattern);
        long netmask = ipv4ToLong(mask);
        if (address < 0 || network < 0 || netmask < 0) {
            return false;
        }
        return (address & netmask) == (network & netmask);
    }

    /** @return the address as an unsigned 32-bit value, or -1 when it is not dotted-quad IPv4 */
    static long ipv4ToLong(String address) {
        if (address == null) {
            return -1;
        }
        String[] parts = address.trim().split("\\.");
        if (parts.length != 4) {
            return -1;
        }
        long value = 0;
        for (String part : parts) {
            try {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) {
                    return -1;
                }
                value = (value << 8) | octet;
            } catch (NumberFormatException notANumber) {
                return -1;
            }
        }
        return value;
    }

    /* --------------------------------------------------------------------- helpers */

    private static Object arg(List<Object> args, int index) {
        return index < args.size() ? args.get(index) : null;
    }

    private static List<String> stringList(List<Object> args) {
        List<String> out = new ArrayList<>(args.size());
        for (Object arg : args) {
            out.add(stringOf(arg));
        }
        return out;
    }

    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Double d) {
            return d != 0 && !d.isNaN();
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        return true;
    }

    static double numberOf(Object value) {
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value == null) {
            return Double.NaN;
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException notANumber) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    static String stringOf(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Double d) {
            // JavaScript prints integral doubles without a decimal point, and PAC files build
            // host names by concatenation, so "8080.0" would be a real bug.
            if (d == Math.floor(d) && !d.isInfinite()) {
                return String.valueOf((long) (double) d);
            }
            return String.valueOf((double) d);
        }
        if (value instanceof List<?> list) {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    joined.append(',');
                }
                joined.append(stringOf(list.get(i)));
            }
            return joined.toString();
        }
        return String.valueOf(value);
    }

    private static String typeOf(Object value) {
        if (value == null) {
            return "undefined";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Double) {
            return "number";
        }
        if (value instanceof String) {
            return "string";
        }
        return "object";
    }

    private static boolean looseEquals(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof String && right instanceof String) {
            return left.equals(right);
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            return truthy(left) == truthy(right);
        }
        if (left instanceof String || right instanceof String) {
            if (left instanceof Double || right instanceof Double) {
                return numberOf(left) == numberOf(right);
            }
            return stringOf(left).equals(stringOf(right));
        }
        return numberOf(left) == numberOf(right);
    }

    /** False when either side coerces to NaN, which makes every relational operator false. */
    private static boolean comparable(Object left, Object right) {
        if (left instanceof String && right instanceof String) {
            return true;
        }
        return !Double.isNaN(numberOf(left)) && !Double.isNaN(numberOf(right));
    }

    private static int compare(Object left, Object right) {
        if (left instanceof String a && right instanceof String b) {
            return a.compareTo(b);
        }
        return Double.compare(numberOf(left), numberOf(right));
    }

    /** Everything the interpreter needs from outside itself, so tests can supply a fixed world. */
    public interface PacEnvironment {

        /** @return the first IPv4 address for {@code host}, or null when it does not resolve */
        String resolve(String host);

        String myIpAddress();

        boolean weekdayRange(List<String> args);

        boolean dateRange(List<String> args);

        boolean timeRange(List<String> args);

        static PacEnvironment system() {
            return new SystemPacEnvironment();
        }
    }

    /** Real DNS and the real clock. */
    static final class SystemPacEnvironment implements PacEnvironment {

        @Override
        public String resolve(String host) {
            try {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (address instanceof java.net.Inet4Address) {
                        return address.getHostAddress();
                    }
                }
                return null;
            } catch (UnknownHostException unresolvable) {
                return null;
            }
        }

        @Override
        public String myIpAddress() {
            try {
                // The address a caller would reach us on, not the loopback that
                // InetAddress.getLocalHost() often reports on a misconfigured host.
                try (java.net.DatagramSocket probe = new java.net.DatagramSocket()) {
                    probe.connect(InetAddress.getByName("8.8.8.8"), 53);
                    String address = probe.getLocalAddress().getHostAddress();
                    if (!address.equals("0.0.0.0")) {
                        return address;
                    }
                }
                return InetAddress.getLocalHost().getHostAddress();
            } catch (Exception offline) {
                return "127.0.0.1";
            }
        }

        @Override
        public boolean weekdayRange(List<String> args) {
            return PacDateTime.weekdayRange(args, java.time.ZonedDateTime.now());
        }

        @Override
        public boolean dateRange(List<String> args) {
            return PacDateTime.dateRange(args, java.time.ZonedDateTime.now());
        }

        @Override
        public boolean timeRange(List<String> args) {
            return PacDateTime.timeRange(args, java.time.ZonedDateTime.now());
        }
    }
}
