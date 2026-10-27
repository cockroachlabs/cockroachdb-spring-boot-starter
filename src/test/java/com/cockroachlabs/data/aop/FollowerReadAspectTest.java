package com.cockroachlabs.data.aop;

import com.cockroachlabs.data.annotation.FollowerRead;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FollowerReadAspectTest {

    @FollowerRead
    void byDefault() {
    }

    @FollowerRead(minutesAgo = 5)
    void fiveMinutesAgo() {
    }

    @FollowerRead(hoursAgo = 1, minutesAgo = 30)
    void ninetyMinutesAgo() {
    }

    @FollowerRead(minutesAgo = -5)
    void negative() {
    }

    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    private final FollowerReadAspect aspect = new FollowerReadAspect(
            txManager, mock(JdbcTemplate.class), new CockroachDBMetrics.InMemory());

    private final ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

    @BeforeEach
    void signature() {
        Signature sig = mock(Signature.class);
        when(sig.toShortString()).thenReturn("Catalog.count(..)");
        when(pjp.getSignature()).thenReturn(sig);
    }

    private FollowerRead annotationOf(String method) throws NoSuchMethodException {
        return getClass().getDeclaredMethod(method).getAnnotation(FollowerRead.class);
    }

    private static FollowerRead followerRead(long hours, long minutes, long seconds) {
        FollowerRead annotation = mock(FollowerRead.class);
        when(annotation.hoursAgo()).thenReturn(hours);
        when(annotation.minutesAgo()).thenReturn(minutes);
        when(annotation.secondsAgo()).thenReturn(seconds);
        return annotation;
    }

    @Test
    void defaultsToFollowerReadTimestamp() throws NoSuchMethodException {
        assertThat(FollowerReadAspect.asOfExpression(pjp, annotationOf("byDefault")))
                .isEqualTo("follower_read_timestamp()");
    }

    @Test
    void singleUnit() throws NoSuchMethodException {
        assertThat(FollowerReadAspect.asOfExpression(pjp, annotationOf("fiveMinutesAgo")))
                .isEqualTo("'-300s'");
    }

    @Test
    void unitsAddUp() throws NoSuchMethodException {
        assertThat(FollowerReadAspect.asOfExpression(pjp, annotationOf("ninetyMinutesAgo")))
                .isEqualTo("'-5400s'");
    }

    @ParameterizedTest
    @CsvSource({"0,0,20,-20s", "1,0,0,-3600s", "0,2,0,-120s", "2,15,30,-8130s"})
    void convertsToSeconds(long hours, long minutes, long seconds, String interval) {
        assertThat(FollowerReadAspect.asOfExpression(pjp, followerRead(hours, minutes, seconds)))
                .isEqualTo("'" + interval + "'");
    }

    @Test
    void negativePartFailsBeforeAnythingRuns() throws Throwable {
        assertThatIllegalStateException()
                .isThrownBy(() -> aspect.around(pjp, annotationOf("negative")))
                .withMessageContaining("minutesAgo = -5")
                .withMessageContaining("must not be negative")
                .withMessageContaining("Catalog.count(..)");
        verify(pjp, never()).proceed();
        verifyNoInteractions(txManager);
    }
}
