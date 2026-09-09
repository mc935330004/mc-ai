package org.example.ai.agent.business.person;

import org.example.ai.agent.business.person.BoundedPersonFanOutService.PersonRequest;
import org.example.ai.agent.business.person.PersonBusinessQueryService.Command;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetPlan;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetType;
import org.example.ai.agent.business.person.PersonBusinessQueryService.Metric;
import org.example.ai.agent.business.person.PersonBusinessQueryService.ModuleResult;
import org.example.ai.agent.business.person.PersonBusinessQueryService.ModuleStatus;
import org.example.ai.agent.business.person.PersonBusinessQueryService.ReimbursementSummary;
import org.example.ai.agent.business.person.PersonBusinessQueryService.Result;
import org.example.ai.agent.business.person.PersonBusinessQueryService.TravelSummary;
import org.example.ai.agent.business.person.model.AttendanceDayResult;
import org.example.ai.agent.business.person.model.MultiPersonSummary;
import org.example.ai.agent.config.BusinessAssistantProperties;
import org.example.ai.agent.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BoundedPersonFanOutServiceTest {

    private BoundedPersonFanOutService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void overLimitReturnsNarrowingInstructionWithoutStartingDetailCalls() {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        service = new BoundedPersonFanOutService(singlePersonService, properties(2, 1, 100, 1_000, 0));

        MultiPersonSummary summary = service.query(
                List.of(request("张*", false), request("李*", false), request("王*", false)),
                () -> false
        );

        assertThat(summary.status()).isEqualTo(MultiPersonSummary.ExecutionStatus.LIMIT_EXCEEDED);
        assertThat(summary.narrowingInstruction()).contains("缩小人员范围");
        assertThat(summary.people()).isEmpty();
        verify(singlePersonService, never()).query(any());
    }

    @Test
    void boundsConcurrencyPreservesOrderAndReturnsAggregateWithAllowedAnomalies() throws Exception {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch twoStarted = new CountDownLatch(2);
        when(singlePersonService.query(any())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            twoStarted.countDown();
            twoStarted.await(1, TimeUnit.SECONDS);
            Thread.sleep(30);
            active.decrementAndGet();
            return completeResult(true);
        });
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 2, 100, 1_000, 0));

        MultiPersonSummary summary = service.query(
                List.of(request("张*", true), request("李*", false), request("王*", true)),
                () -> false
        );

        assertThat(maximum.get()).isEqualTo(2);
        assertThat(summary.status()).isEqualTo(MultiPersonSummary.ExecutionStatus.COMPLETED);
        assertThat(summary.people()).extracting(MultiPersonSummary.PersonStatus::displayLabel)
                .containsExactly("张*", "李*", "王*");
        assertThat(summary.aggregate().tripCount()).isEqualTo(3);
        assertThat(summary.aggregate().travelAmount()).isEqualByComparingTo("30");
        assertThat(summary.aggregate().reimbursementAmount()).isEqualByComparingTo("60");
        assertThat(summary.anomalyPeople()).extracting(MultiPersonSummary.AnomalyPerson::displayLabel)
                .containsExactly("张*", "王*");
    }

    @Test
    void reimbursementOnlyReturnsCompleteTotalWithTravelFieldsAbsent() {
        List<DatasetPlan> plans = plans(DatasetType.REIMBURSEMENT);
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        when(singlePersonService.query(any())).thenReturn(completeResult(plans, false));
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 1, 100, 1_000, 0));

        MultiPersonSummary summary = service.query(
                List.of(request("张*", false, plans), request("李*", false, plans)),
                () -> false
        );

        assertThat(summary.status()).isEqualTo(MultiPersonSummary.ExecutionStatus.COMPLETED);
        assertThat(summary.aggregate().complete()).isTrue();
        assertThat(summary.aggregate().tripCount()).isNull();
        assertThat(summary.aggregate().travelAmount()).isNull();
        assertThat(summary.aggregate().reimbursementAmount()).isEqualByComparingTo("40");
    }

    @Test
    void travelOnlyReturnsCompleteTotalsWithReimbursementAbsent() {
        List<DatasetPlan> plans = plans(DatasetType.TRAVEL);
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        when(singlePersonService.query(any())).thenReturn(completeResult(plans, false));
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 1, 100, 1_000, 0));

        MultiPersonSummary summary = service.query(List.of(request("张*", false, plans)), () -> false);

        assertThat(summary.status()).isEqualTo(MultiPersonSummary.ExecutionStatus.COMPLETED);
        assertThat(summary.aggregate().tripCount()).isOne();
        assertThat(summary.aggregate().travelAmount()).isEqualByComparingTo("10");
        assertThat(summary.aggregate().reimbursementAmount()).isNull();
    }

    @Test
    void attendanceOnlyUsesDependenciesWithoutPublishingTravelTotals() {
        List<DatasetPlan> plans = plans(DatasetType.PUNCH);
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        when(singlePersonService.query(any())).thenReturn(completeResult(plans, true));
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 1, 100, 1_000, 0));

        MultiPersonSummary summary = service.query(List.of(request("张*", true, plans)), () -> false);

        assertThat(summary.status()).isEqualTo(MultiPersonSummary.ExecutionStatus.COMPLETED);
        assertThat(summary.aggregate().tripCount()).isNull();
        assertThat(summary.aggregate().travelAmount()).isNull();
        assertThat(summary.aggregate().reimbursementAmount()).isNull();
        assertThat(summary.anomalyPeople()).extracting(MultiPersonSummary.AnomalyPerson::displayLabel)
                .containsExactly("张*");
    }

    @Test
    void failedAttendanceDependencyMakesPersonAndAggregatePartial() {
        List<DatasetPlan> plans = plans(DatasetType.PUNCH);
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        when(singlePersonService.query(any())).thenReturn(result(plans, false, DatasetType.LEAVE));
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 1, 100, 1_000, 0));

        MultiPersonSummary summary = service.query(List.of(request("张*", true, plans)), () -> false);

        assertThat(summary.status()).isEqualTo(MultiPersonSummary.ExecutionStatus.PARTIAL);
        assertThat(summary.people()).extracting(MultiPersonSummary.PersonStatus::status)
                .containsExactly(MultiPersonSummary.PersonQueryStatus.PARTIAL);
        assertThat(summary.aggregate().complete()).isFalse();
        assertThat(summary.anomalyPeople()).isEmpty();
    }

    @Test
    void anomalyIsNotCollectedWhenPunchWasNotRequested() {
        List<DatasetPlan> plans = plans(DatasetType.TRAVEL);
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        when(singlePersonService.query(any())).thenReturn(completeResult(plans, true));
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 1, 100, 1_000, 0));

        MultiPersonSummary summary = service.query(List.of(request("张*", true, plans)), () -> false);

        assertThat(summary.status()).isEqualTo(MultiPersonSummary.ExecutionStatus.COMPLETED);
        assertThat(summary.anomalyPeople()).isEmpty();
    }

    @Test
    void rejectsDifferentPersonPlanContractsBeforeStartingCalls() {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        List<DatasetPlan> plans = plans(DatasetType.TRAVEL);
        DatasetPlan original = plans.get(0);
        List<DatasetPlan> changed = List.of(new DatasetPlan(
                original.type(), original.datasetCode(),
                Map.of("startDate", "2026-09-02", "endDate", "2026-09-10"),
                original.requestedGrain(), original.requiredFactCodes(), original.userRequested()
        ));
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 1, 100, 1_000, 0));

        assertThatThrownBy(() -> service.query(
                List.of(request("张*", false, plans), request("李*", false, changed)),
                () -> false
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("计划契约");
        verify(singlePersonService, never()).query(any());
    }

    @Test
    void appliesRequestRateLimitToEachSinglePersonCall() {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        List<Long> starts = new ArrayList<>();
        when(singlePersonService.query(any())).thenAnswer(invocation -> {
            synchronized (starts) {
                starts.add(System.nanoTime());
            }
            return completeResult(false);
        });
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 2, 5, 1_000, 0));

        service.query(List.of(request("张*", false), request("李*", false)), () -> false);

        assertThat(starts).hasSize(2);
        starts.sort(Long::compareTo);
        assertThat(Duration.ofNanos(starts.get(1) - starts.get(0)))
                .isGreaterThanOrEqualTo(Duration.ofMillis(150));
    }

    @Test
    void retriesOnceAndKeepsPartialCompletionAfterFailureAndTimeout() {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        AtomicInteger calls = new AtomicInteger();
        when(singlePersonService.query(any())).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                throw new IllegalStateException("temporary");
            }
            if (call == 3) {
                Thread.sleep(500);
            }
            return completeResult(false);
        });
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 1, 100, 80, 1));

        MultiPersonSummary summary = service.query(
                List.of(request("重试人员", false), request("超时人员", false)),
                () -> false
        );

        assertThat(calls.get()).isEqualTo(3);
        assertThat(summary.status()).isEqualTo(MultiPersonSummary.ExecutionStatus.PARTIAL);
        assertThat(summary.people()).extracting(MultiPersonSummary.PersonStatus::status)
                .containsExactly(
                        MultiPersonSummary.PersonQueryStatus.SUCCESS,
                        MultiPersonSummary.PersonQueryStatus.TIMEOUT
                );
        assertThat(summary.aggregate().complete()).isFalse();
    }

    @Test
    void cancellationStopsStartingRemainingPeople() {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(singlePersonService.query(any())).thenAnswer(invocation -> {
            cancelled.set(true);
            return completeResult(false);
        });
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 1, 100, 1_000, 0));

        MultiPersonSummary summary = service.query(
                List.of(request("张*", false), request("李*", false), request("王*", false)),
                cancelled::get
        );

        assertThat(summary.status()).isEqualTo(MultiPersonSummary.ExecutionStatus.CANCELLED);
        assertThat(summary.people()).extracting(MultiPersonSummary.PersonStatus::status)
                .containsExactly(
                        MultiPersonSummary.PersonQueryStatus.SUCCESS,
                        MultiPersonSummary.PersonQueryStatus.CANCELLED,
                        MultiPersonSummary.PersonQueryStatus.CANCELLED
                );
        verify(singlePersonService).query(any());
    }

    @Test
    void downstreamIgnoringInterruptDoesNotBreakPartialCompletion() {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        when(singlePersonService.query(any())).thenAnswer(invocation -> {
            long stopAt = System.nanoTime() + Duration.ofMillis(200).toNanos();
            while (System.nanoTime() < stopAt) {
                LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
            }
            return completeResult(false);
        });
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 1, 100, 30, 0));

        MultiPersonSummary summary = service.query(
                List.of(request("张*", false), request("李*", false), request("王*", false)),
                () -> false
        );

        assertThat(summary.status()).isEqualTo(MultiPersonSummary.ExecutionStatus.PARTIAL);
        assertThat(summary.people()).hasSize(3);
        assertThat(summary.people()).allMatch(
                person -> person.status() != MultiPersonSummary.PersonQueryStatus.SUCCESS
        );
    }

    @Test
    void rejectsInvalidBoundedConfiguration() {
        BusinessAssistantProperties properties = properties(2, 3, 0, 0, -1);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("多人查询");
    }

    @Test
    void overallTimeoutStopsLaterBatches() {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        AtomicInteger calls = new AtomicInteger();
        when(singlePersonService.query(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            Thread.sleep(70);
            return completeResult(false);
        });
        BusinessAssistantProperties properties = properties(10, 1, 100, 1_000, 0);
        properties.setOverallTimeout(Duration.ofMillis(100));
        service = new BoundedPersonFanOutService(singlePersonService, properties);

        MultiPersonSummary summary = service.query(
                List.of(request("一", false), request("二", false), request("三", false)),
                () -> false
        );

        assertThat(summary.status()).isEqualTo(MultiPersonSummary.ExecutionStatus.PARTIAL);
        assertThat(calls.get()).isLessThan(3);
        assertThat(summary.people()).hasSize(3);
    }

    @Test
    void runningCancellationIsObservedWithoutWaitingForPersonTimeout() throws Exception {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(singlePersonService.query(any())).thenAnswer(invocation -> {
            started.countDown();
            Thread.sleep(2_000);
            return completeResult(false);
        });
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 1, 100, 2_000, 0));
        Thread trigger = new Thread(() -> {
            try {
                started.await(1, TimeUnit.SECONDS);
                Thread.sleep(30);
                cancelled.set(true);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        trigger.setDaemon(true);
        trigger.start();

        long startedAt = System.nanoTime();
        MultiPersonSummary summary = service.query(
                List.of(request("一", false), request("二", false)),
                cancelled::get
        );

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofMillis(400));
        assertThat(summary.status()).isEqualTo(MultiPersonSummary.ExecutionStatus.CANCELLED);
        verify(singlePersonService).query(any());
    }

    @Test
    void incompleteModuleMakesOverallAggregateIncomplete() {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        Result result = completeResult(true);
        when(singlePersonService.query(any())).thenReturn(new Result(
                result.travelSummary(),
                result.reimbursementSummary(),
                result.attendance(),
                List.of(new ModuleResult(
                        PersonBusinessQueryService.DatasetType.TRAVEL,
                        "PERSON_TRAVEL",
                        ModuleStatus.SUCCESS,
                        false,
                        "snapshot-travel",
                        "a".repeat(64)
                ))
        ));
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 1, 100, 1_000, 0));

        MultiPersonSummary summary = service.query(List.of(request("一", true)), () -> false);

        assertThat(summary.status()).isEqualTo(MultiPersonSummary.ExecutionStatus.PARTIAL);
        assertThat(summary.aggregate().complete()).isFalse();
        assertThat(summary.aggregate().successfulPeople()).isZero();
        assertThat(summary.people()).extracting(MultiPersonSummary.PersonStatus::status)
                .containsExactly(MultiPersonSummary.PersonQueryStatus.PARTIAL);
        assertThat(summary.anomalyPeople()).isEmpty();
    }

    @Test
    void incompleteFormalMetricMakesPersonPartialAndExcludesFactsAndAnomalies() {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        Result complete = completeResult(true);
        when(singlePersonService.query(any())).thenReturn(new Result(
                new TravelSummary(Metric.incomplete(), complete.travelSummary().totalAmount()),
                complete.reimbursementSummary(),
                complete.attendance(),
                List.of(new ModuleResult(
                        PersonBusinessQueryService.DatasetType.TRAVEL,
                        "PERSON_TRAVEL",
                        ModuleStatus.SUCCESS,
                        true,
                        "snapshot-travel",
                        "a".repeat(64)
                ))
        ));
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 1, 100, 1_000, 0));

        MultiPersonSummary summary = service.query(List.of(request("一", true)), () -> false);

        assertThat(summary.people()).extracting(MultiPersonSummary.PersonStatus::status)
                .containsExactly(MultiPersonSummary.PersonQueryStatus.PARTIAL);
        assertThat(summary.aggregate().complete()).isFalse();
        assertThat(summary.aggregate().successfulPeople()).isZero();
        assertThat(summary.anomalyPeople()).isEmpty();
    }

    @Test
    void businessDenialIsNotRetried() {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        AtomicInteger calls = new AtomicInteger();
        when(singlePersonService.query(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            throw new BusinessException(403, "禁止访问");
        });
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 1, 100, 1_000, 1));

        MultiPersonSummary summary = service.query(List.of(request("一", false)), () -> false);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(summary.people()).extracting(MultiPersonSummary.PersonStatus::status)
                .containsExactly(MultiPersonSummary.PersonQueryStatus.FAILED);
    }

    @Test
    void transientBusiness503IsRetriedOnce() {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        AtomicInteger calls = new AtomicInteger();
        when(singlePersonService.query(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new BusinessException(503, "来源服务暂不可用");
            }
            return completeResult(false);
        });
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 1, 100, 1_000, 1));

        MultiPersonSummary summary = service.query(List.of(request("一", false)), () -> false);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(summary.people()).extracting(MultiPersonSummary.PersonStatus::status)
                .containsExactly(MultiPersonSummary.PersonQueryStatus.SUCCESS);
    }

    @Test
    void cancellationExceptionKeepsCancelledStatus() {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        when(singlePersonService.query(any())).thenThrow(new java.util.concurrent.CancellationException());
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 1, 100, 1_000, 1));

        MultiPersonSummary summary = service.query(List.of(request("一", false)), () -> false);

        assertThat(summary.people()).extracting(MultiPersonSummary.PersonStatus::status)
                .containsExactly(MultiPersonSummary.PersonQueryStatus.CANCELLED);
        verify(singlePersonService).query(any());
    }

    @Test
    void cancelledLowRateBatchDoesNotReservePermitForNextBatch() throws Exception {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        CountDownLatch firstCall = new CountDownLatch(1);
        when(singlePersonService.query(any())).thenAnswer(invocation -> {
            firstCall.countDown();
            return completeResult(false);
        });
        service = new BoundedPersonFanOutService(singlePersonService, properties(10, 2, 2, 2_000, 0));
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread trigger = new Thread(() -> {
            try {
                firstCall.await(1, TimeUnit.SECONDS);
                Thread.sleep(50);
                cancelled.set(true);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        trigger.setDaemon(true);
        trigger.start();
        service.query(List.of(request("一", false), request("二", false)), cancelled::get);

        long startedAt = System.nanoTime();
        MultiPersonSummary next = service.query(List.of(request("三", false)), () -> false);

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofMillis(750));
        assertThat(next.status()).isEqualTo(MultiPersonSummary.ExecutionStatus.COMPLETED);
    }

    @Test
    void sharedExecutorStartsPersonTimeoutOnlyWhenTaskActuallyRuns() throws Exception {
        PersonBusinessQueryService singlePersonService = mock(PersonBusinessQueryService.class);
        CountDownLatch firstStarted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(singlePersonService.query(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
                long stopAt = System.nanoTime() + Duration.ofMillis(180).toNanos();
                while (System.nanoTime() < stopAt) {
                    LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
                }
            }
            return completeResult(false);
        });
        BusinessAssistantProperties properties = properties(10, 1, 100, 100, 0);
        properties.setOverallTimeout(Duration.ofSeconds(2));
        service = new BoundedPersonFanOutService(singlePersonService, properties);

        CompletableFuture<MultiPersonSummary> first = CompletableFuture.supplyAsync(
                () -> service.query(List.of(request("占用线程", false)), () -> false)
        );
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MultiPersonSummary> queued = CompletableFuture.supplyAsync(
                () -> service.query(List.of(request("排队人员", false)), () -> false)
        );
        Thread.sleep(30);
        MultiPersonSummary rejectedThenRetried = service.query(
                List.of(request("等待重提人员", false)),
                () -> false
        );

        assertThat(queued.get(2, TimeUnit.SECONDS).status())
                .isEqualTo(MultiPersonSummary.ExecutionStatus.COMPLETED);
        assertThat(rejectedThenRetried.status())
                .isEqualTo(MultiPersonSummary.ExecutionStatus.COMPLETED);
        first.get(2, TimeUnit.SECONDS);
    }

    private BusinessAssistantProperties properties(
            int maxPeople,
            int concurrency,
            int requestsPerSecond,
            long timeoutMillis,
            int retries) {
        BusinessAssistantProperties properties = new BusinessAssistantProperties();
        properties.setMaxPeople(maxPeople);
        properties.setConcurrency(concurrency);
        properties.setRequestsPerSecond(requestsPerSecond);
        properties.setPersonTimeout(Duration.ofMillis(timeoutMillis));
        properties.setOverallTimeout(Duration.ofSeconds(5));
        properties.setMaxRetries(retries);
        properties.setSnapshotMaxTtl(Duration.ofHours(24));
        return properties;
    }

    private PersonRequest request(String label, boolean allowAnomalyDisclosure) {
        return request(label, allowAnomalyDisclosure,
                plans(DatasetType.TRAVEL, DatasetType.PUNCH, DatasetType.REIMBURSEMENT));
    }

    private PersonRequest request(
            String label,
            boolean allowAnomalyDisclosure,
            List<DatasetPlan> plans) {
        return new PersonRequest(
                label,
                allowAnomalyDisclosure,
                new Command("run", "user", "session", "Bearer token", Map.of(), label, false, plans)
        );
    }

    private Result completeResult(boolean missingPunch) {
        return completeResult(
                plans(DatasetType.TRAVEL, DatasetType.PUNCH, DatasetType.REIMBURSEMENT),
                missingPunch
        );
    }

    private Result completeResult(List<DatasetPlan> plans, boolean missingPunch) {
        return result(plans, missingPunch, null);
    }

    private Result result(
            List<DatasetPlan> plans,
            boolean missingPunch,
            DatasetType incompleteType) {
        boolean travelExecuted = plans.stream().anyMatch(plan -> plan.type() == DatasetType.TRAVEL);
        boolean reimbursementExecuted = plans.stream()
                .anyMatch(plan -> plan.type() == DatasetType.REIMBURSEMENT);
        List<AttendanceDayResult> attendance = missingPunch
                ? List.of(new AttendanceDayResult(
                        LocalDate.of(2026, 9, 1), "MISSING", "NONE", "MISSING_PUNCH", List.of("punch")
                ))
                : List.of();
        return new Result(
                travelExecuted
                        ? new TravelSummary(Metric.complete(1), Metric.complete(new BigDecimal("10")))
                        : new TravelSummary(Metric.incomplete(), Metric.incomplete()),
                reimbursementExecuted
                        ? new ReimbursementSummary(
                        Metric.complete(new BigDecimal("20")),
                        Metric.complete(new BigDecimal("20")),
                        Metric.complete(new BigDecimal("20")))
                        : new ReimbursementSummary(
                        Metric.incomplete(), Metric.incomplete(), Metric.incomplete()),
                attendance,
                plans.stream().map(plan -> new ModuleResult(
                        plan.type(), plan.datasetCode(), ModuleStatus.SUCCESS,
                        plan.type() != incompleteType,
                        "snapshot-" + plan.type().name().toLowerCase(java.util.Locale.ROOT),
                        "a".repeat(64)
                )).toList()
        );
    }

    private List<DatasetPlan> plans(DatasetType... requestedTypes) {
        Set<DatasetType> requested = Set.of(requestedTypes);
        Set<DatasetType> attendance = Set.of(
                DatasetType.TRAVEL, DatasetType.PUNCH, DatasetType.LEAVE,
                DatasetType.SCHEDULE, DatasetType.CALENDAR
        );
        return java.util.Arrays.stream(DatasetType.values())
                .filter(type -> requested.contains(type)
                        || requested.contains(DatasetType.PUNCH) && attendance.contains(type))
                .map(type -> new DatasetPlan(
                        type,
                        "PERSON_" + type.name(),
                        Map.of("startDate", "2026-09-01", "endDate", "2026-09-10"),
                        "DAY",
                        Set.of(factCode(type)),
                        requested.contains(type)
                ))
                .toList();
    }

    private String factCode(DatasetType type) {
        return switch (type) {
            case TRAVEL -> PersonBusinessQueryService.TRAVEL_RECORDS;
            case PUNCH -> PersonBusinessQueryService.PUNCH_RECORDS;
            case LEAVE -> PersonBusinessQueryService.LEAVE_RECORDS;
            case SCHEDULE -> PersonBusinessQueryService.SCHEDULE_RECORDS;
            case CALENDAR -> PersonBusinessQueryService.CALENDAR_RECORDS;
            case REIMBURSEMENT -> PersonBusinessQueryService.REIMBURSEMENT_RECORDS;
        };
    }
}
