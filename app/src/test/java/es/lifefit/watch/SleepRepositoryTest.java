package es.lifefit.watch;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.TimeZone;
import org.junit.Test;

public final class SleepRepositoryTest {
    private static final String DATE = "2026-10-29";
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static WatchSleepHistory.Sample sample(String date, long start, long end, int state) {
        return new WatchSleepHistory.Sample(date, start, end, state);
    }

    private static SleepMetrics metrics(String date, WatchSleepHistory.Sample... samples) {
        return SleepMetrics.from(date, Arrays.asList(samples));
    }

    @Test public void partialNewestDoesNotReplaceCompleteNight() {
        SleepMetrics partial = metrics(DATE, sample(DATE, 10, 20, 1));
        SleepMetrics complete = metrics(DATE, sample(DATE, 0, 10, 1), sample(DATE, 10, 20, 2));
        SleepRepository.Selector selector = new SleepRepository.Selector();
        selector.add(partial, UTC);
        selector.add(complete, UTC);
        SleepRepository.Result result = selector.result(false);
        assertSame(complete, result.nights.get(0).metrics);
        assertFalse(result.ambiguous);
    }

    @Test public void equalCoverageUsesLatestPhaseCorrectionAndIgnoresDroppedOverlap() {
        SleepMetrics corrected = metrics(DATE, sample(DATE, 0, 10, 2), sample(DATE, 5, 50, 1),
                sample(DATE, 10, 20, 1));
        SleepMetrics original = metrics(DATE, sample(DATE, 0, 20, 1));
        SleepRepository.Selector selector = new SleepRepository.Selector();
        selector.add(corrected, UTC);
        selector.add(original, UTC);
        SleepRepository.Result result = selector.result(false);
        assertSame(corrected, result.nights.get(0).metrics);
        assertFalse(result.ambiguous);
        assertTrue(corrected.overlapping);
    }

    @Test public void incomparableGapsBlockWholeExport() throws Exception {
        SleepMetrics newest = metrics(DATE, sample(DATE, 0, 10, 1), sample(DATE, 20, 40, 2));
        SleepMetrics older = metrics(DATE, sample(DATE, 0, 20, 1), sample(DATE, 30, 40, 2));
        SleepRepository.Selector selector = new SleepRepository.Selector();
        selector.add(newest, UTC);
        selector.add(older, UTC);
        SleepRepository.Result result = selector.result(false);
        assertSame(newest, result.nights.get(0).metrics);
        assertTrue(result.ambiguous);
        assertTrue(result.nights.get(0).ambiguous);
        try {
            SleepExport.document(null, "synthetic", result, "unused");
            fail("Ambiguous export must be rejected before opening the database");
        } catch (IllegalStateException exception) {
            assertTrue(exception.getMessage().contains(DATE));
        }
    }

    @Test public void oldSupersetResolvesIncomparableCandidatesAcrossMultipleDates() {
        String otherDate = "2026-10-28";
        SleepMetrics newest = metrics(DATE, sample(DATE, 0, 10, 1));
        SleepMetrics middle = metrics(DATE, sample(DATE, 20, 30, 1));
        SleepMetrics complete = metrics(DATE, sample(DATE, 0, 30, 1));
        SleepMetrics other = metrics(otherDate, sample(otherDate, 5, 15, 2));
        SleepRepository.Selector selector = new SleepRepository.Selector();
        selector.add(newest, UTC);
        selector.add(other, UTC);
        selector.add(middle, UTC);
        selector.add(complete, UTC);
        SleepRepository.Result result = selector.result(true);
        assertEquals(2, result.nights.size());
        assertSame(complete, result.nights.get(0).metrics);
        assertSame(other, result.nights.get(1).metrics);
        assertTrue(result.unreadable);
        assertFalse(result.ambiguous);
    }

    @Test public void clippingAndInternalGapsMatter() {
        SleepMetrics latest = metrics(DATE, sample(DATE, 0, 10, 3), sample(DATE, 10, 20, 1),
                sample(DATE, 30, 40, 2), sample(DATE, 40, 50, 3));
        SleepMetrics old = metrics(DATE, sample(DATE, 10, 40, 1));
        SleepRepository.Selector selector = new SleepRepository.Selector();
        selector.add(latest, UTC);
        selector.add(old, UTC);
        SleepRepository.Result result = selector.result(false);
        assertSame(old, result.nights.get(0).metrics);
        assertFalse(result.ambiguous);
    }

    @Test public void changedEndpointsAndGapCannotBeComparedByDurationOrBoundsAlone() {
        SleepMetrics newest = metrics(DATE, sample(DATE, 0, 10, 1), sample(DATE, 20, 30, 2));
        SleepMetrics older = metrics(DATE, sample(DATE, 5, 25, 1));
        SleepRepository.Selector selector = new SleepRepository.Selector();
        selector.add(newest, UTC);
        selector.add(older, UTC);
        SleepRepository.Result result = selector.result(false);
        assertSame(newest, result.nights.get(0).metrics);
        assertTrue(result.ambiguous);
    }

    @Test public void conflictInOneOfSeveralNightsBlocksAll() throws Exception {
        String otherDate = "2026-10-28";
        SleepRepository.Selector selector = new SleepRepository.Selector();
        selector.add(metrics(DATE, sample(DATE, 0, 20, 1)), UTC);
        selector.add(metrics(otherDate, sample(otherDate, 0, 10, 1)), UTC);
        selector.add(metrics(otherDate, sample(otherDate, 5, 15, 2)), UTC);
        SleepRepository.Result result = selector.result(false);
        assertFalse(result.nights.get(0).ambiguous);
        assertTrue(result.nights.get(1).ambiguous);
        try {
            SleepExport.document(null, "synthetic", result, "unused");
            fail("A conflict in any night must block the document");
        } catch (IllegalStateException exception) {
            assertTrue(exception.getMessage().contains(otherDate));
        }
    }
}