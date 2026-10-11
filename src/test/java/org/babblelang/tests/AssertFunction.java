package org.babblelang.tests;

import org.opentest4j.AssertionFailedError;

public class AssertFunction extends org.babblelang.engine.impl.natives.AssertFunction {
    @Override
    protected void fail(String message) {
        // Thrown directly rather than via Assertions.assertTrue, which would append
        // its own "==> expected: <true> but was: <false>" to the reported message.
        throw new AssertionFailedError(message);
    }
}
