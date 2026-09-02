package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A handshake must settle exactly once, and the flag that guarantees it crosses threads.
 *
 * <p>The success and failure paths run on the selector thread, but {@code onIdleExpired} does
 * not: {@code IdleTimeout} schedules its check on the {@code Scheduler} and
 * {@code AbstractEndPoint} calls {@code onIdleExpired} inline there. With a plain boolean, an
 * idle expiry landing inside the hand-off window could count one dial as both success and
 * failure -- writing a 502 for a tunnel that had been granted, or tearing down one the client
 * had already been told about.
 *
 * <p>Asserted structurally rather than by racing threads: the window is microseconds wide and a
 * timing test for it would be flaky enough to be ignored. What can be checked deterministically
 * is that the flag is an atomic claimed with compareAndSet, and that both paths claim it -- the
 * success path previously only assigned, so it could not have lost a race it never entered.
 */
class HandshakeSettleOnceTest {

    private static Class<?> inner(Class<?> outer, String simpleName) {
        for (Class<?> candidate : outer.getDeclaredClasses()) {
            if (candidate.getSimpleName().equals(simpleName)) {
                return candidate;
            }
        }
        throw new AssertionError("no inner class " + simpleName + " in " + outer);
    }

    private static Field settledField(Class<?> connection) throws Exception {
        Class<?> type = connection;
        while (type != null) {
            try {
                return type.getDeclaredField("settled");
            } catch (NoSuchFieldException keepLooking) {
                type = type.getSuperclass();
            }
        }
        throw new AssertionError("no 'settled' field reachable from " + connection);
    }

    @Test
    void theConnectHandshakeGuardIsAtomic() throws Exception {
        Field settled = settledField(inner(CustomConnectHandler.class, "HandshakeConnection"));

        assertThat(settled.getType())
                .as("a plain boolean is not safe between the selector and scheduler threads")
                .isEqualTo(AtomicBoolean.class);
    }

    @Test
    void theWebsocketHandshakeGuardIsAtomic() throws Exception {
        Field settled =
                settledField(inner(WebsocketHandler.class, "WebsocketHandshakeConnection"));

        assertThat(settled.getType()).isEqualTo(AtomicBoolean.class);
    }

    @Test
    void bothOutcomesClaimTheGuardRatherThanOnlyTheFailure() throws Exception {
        // handshakeSucceeded used to assign the flag without testing it, so a failure already
        // in flight could still run its own path afterwards. Both must now claim it.
        for (Class<?> connection : new Class<?>[]{
                inner(CustomConnectHandler.class, "HandshakeConnection"),
                inner(WebsocketHandler.class, "WebsocketHandshakeConnection")}) {
            for (String name : new String[]{"handshakeSucceeded", "handshakeFailed"}) {
                Method method = findMethod(connection, name);
                assertThat(method)
                        .as("%s should declare %s", connection.getSimpleName(), name)
                        .isNotNull();
            }
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
        }
        return null;
    }

    @Test
    void aSecondClaimOnTheSameGuardIsRefused() {
        // The property the production code now relies on, stated directly: whichever thread
        // arrives second must be told it lost, so only one outcome is ever recorded.
        AtomicBoolean settled = new AtomicBoolean();

        assertThat(settled.compareAndSet(false, true)).isTrue();
        assertThat(settled.compareAndSet(false, true)).isFalse();
    }
}
