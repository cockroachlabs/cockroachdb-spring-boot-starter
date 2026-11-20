package com.cockroachlabs.data.continuum.aop;

/**
 * Carries a checked exception out of a {@code TransactionCallback}, which can only throw
 * unchecked ones. The advice that throws it unwraps it and rethrows the original exception, so
 * the advised method's caller never sees this type.
 */
final class CheckedExceptionTunnel extends RuntimeException {

    CheckedExceptionTunnel(Throwable checked) {
        super(null, checked, false, false);
    }
}
