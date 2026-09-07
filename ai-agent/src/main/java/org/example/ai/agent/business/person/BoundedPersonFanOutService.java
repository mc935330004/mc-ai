package org.example.ai.agent.business.person;

import jakarta.annotation.PreDestroy;
import org.example.ai.agent.business.person.PersonBusinessQueryService.Command;
import org.example.ai.agent.business.person.PersonBusinessQueryService.Result;
import org.example.ai.agent.business.person.model.AttendanceDayResult;
import org.example.ai.agent.business.person.model.MultiPersonSummary;
import org.example.ai.agent.business.person.model.MultiPersonSummary.Aggregate;
import org.example.ai.agent.business.person.model.MultiPersonSummary.AnomalyPerson;
import org.example.ai.agent.business.person.model.MultiPersonSummary.PersonQueryStatus;
import org.example.ai.agent.business.person.model.MultiPersonSummary.PersonStatus;
import org.example.ai.agent.config.BusinessAssistantProperties;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

/**
 * 对不支持批量查询的人员接口执行有界逐人查询。
 *
 * 线程池、速率、超时和人数上限均使用业务助手独立配置，不占用 SSE 执行器。
 */
@Service
public class BoundedPersonFanOutService {

    private static final String NARROWING_INSTRUCTION = "人员数量超过单次上限，请缩小人员范围或拆分查询。";

    private final PersonBusinessQueryService singlePersonService;
    private final BusinessAssistantProperties properties;
    private final ExecutorService executor;
    private final RateGate rateGate;

    public BoundedPersonFanOutService(
            PersonBusinessQueryService singlePersonService,
            BusinessAssistantProperties properties) {
        this.singlePersonService = Objects.requireNonNull(
                singlePersonService, "singlePersonService不能为空"
        );
        this.properties = Objects.requireNonNull(properties, "properties不能为空");
        properties.validate();
        this.executor = executor(properties.getConcurrency());
        this.rateGate = new RateGate(properties.getRequestsPerSecond());
    }

    public MultiPersonSummary query(
            List<PersonRequest> requests,
            BooleanSupplier cancellationRequested) {
        validate(requests, cancellationRequested);
        if (requests.size() > properties.getMaxPeople()) {
            return new MultiPersonSummary(
                    MultiPersonSummary.ExecutionStatus.LIMIT_EXCEEDED,
                    incompleteAggregate(0),
                    List.of(),
                    List.of(),
                    NARROWING_INSTRUCTION
            );
        }

        long overallDeadline = System.nanoTime() + properties.getOverallTimeout().toNanos();
        Outcome[] outcomes = executeBounded(requests, cancellationRequested, overallDeadline);
        return summarize(requests, outcomes, cancellationRequested.getAsBoolean());
    }

    private Outcome[] executeBounded(
            List<PersonRequest> requests,
            BooleanSupplier cancellationRequested,
            long overallDeadline) {
        Outcome[] outcomes = new Outcome[requests.size()];
        ExecutorCompletionService<IndexedOutcome> completion =
                new ExecutorCompletionService<>(executor);
        Map<Future<IndexedOutcome>, InFlight> running = new LinkedHashMap<>();
        int nextIndex = 0;

        while (hasPending(outcomes)) {
            if (System.nanoTime() >= overallDeadline) {
                timeoutAll(requests.size(), nextIndex, running, outcomes);
                break;
            }
            if (cancellationRequested.getAsBoolean()) {
                cancelAll(requests.size(), nextIndex, running, outcomes);
                break;
            }
            while (nextIndex < requests.size()
                    && running.size() < properties.getConcurrency()) {
                int index = nextIndex;
                InFlight item = new InFlight(index, new AtomicLong());
                try {
                    Future<IndexedOutcome> future = completion.submit(
                            () -> {
                                item.markStarted();
                                return new IndexedOutcome(
                                        index,
                                        executeOne(requests.get(index), cancellationRequested)
                                );
                            }
                    );
                    running.put(future, item);
                    nextIndex++;
                } catch (RejectedExecutionException exception) {
                    // 共享执行器暂满时短等重提，直到整体超时或调用方取消。
                    break;
                }
            }
            if (running.isEmpty()) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                continue;
            }
            if (!collectNext(completion, running, outcomes, cancellationRequested, overallDeadline)) {
                break;
            }
        }
        return outcomes;
    }

    private boolean collectNext(
            ExecutorCompletionService<IndexedOutcome> completion,
            Map<Future<IndexedOutcome>, InFlight> running,
            Outcome[] outcomes,
            BooleanSupplier cancellationRequested,
            long overallDeadline) {
        long now = System.nanoTime();
        long waitNanos = running.values().stream()
                .mapToLong(item -> item.deadlineNanos(
                        overallDeadline,
                        properties.getPersonTimeout().toNanos()
                ) - now)
                .min()
                .orElse(1L);
        waitNanos = Math.min(waitNanos, overallDeadline - now);
        // 短轮询让运行中的取消信号无需等待完整的单人超时。
        waitNanos = Math.min(waitNanos, TimeUnit.MILLISECONDS.toNanos(10));
        Future<IndexedOutcome> completed;
        try {
            completed = completion.poll(
                    Math.max(1L, waitNanos), TimeUnit.NANOSECONDS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            running.forEach((future, item) -> {
                future.cancel(true);
                outcomes[item.index()] = Outcome.cancelled();
            });
            running.clear();
            return false;
        }
        if (completed == null && cancellationRequested.getAsBoolean()) {
            return true;
        }
        if (completed != null) {
            InFlight inFlight = running.remove(completed);
            if (inFlight != null) {
                try {
                    outcomes[inFlight.index()] = completed.get().outcome();
                } catch (ExecutionException | CancellationException exception) {
                    // 具体异常不进入人员状态，避免泄露下游响应或人员标识。
                    outcomes[inFlight.index()] = Outcome.failed();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    outcomes[inFlight.index()] = Outcome.cancelled();
                    return false;
                }
            }
        }
        cancelExpired(running, outcomes);
        return true;
    }

    private void cancelExpired(
            Map<Future<IndexedOutcome>, InFlight> running,
            Outcome[] outcomes) {
        long now = System.nanoTime();
        List<Future<IndexedOutcome>> expired = running.entrySet().stream()
                .filter(entry -> entry.getValue().personDeadlineNanos(
                        properties.getPersonTimeout().toNanos()
                ) <= now)
                .map(Map.Entry::getKey)
                .toList();
        for (Future<IndexedOutcome> future : expired) {
            InFlight item = running.remove(future);
            future.cancel(true);
            outcomes[item.index()] = Outcome.timeout();
        }
    }

    private Outcome executeOne(
            PersonRequest request,
            BooleanSupplier cancellationRequested) {
        for (int attempt = 0; attempt <= properties.getMaxRetries(); attempt++) {
            if (!rateGate.acquire(cancellationRequested)) {
                return Outcome.cancelled();
            }
            try {
                Result result = singlePersonService.query(request.command());
                if (result == null) {
                    return Outcome.failed();
                }
                return completePersonResult(result)
                        ? Outcome.success(result)
                        : Outcome.partial(result);
            } catch (CancellationException exception) {
                return Outcome.cancelled();
            } catch (BusinessException exception) {
                if (!serverError(exception) || attempt == properties.getMaxRetries()) {
                    return Outcome.failed();
                }
            } catch (SecurityException exception) {
                // 权限或业务拒绝不能靠重试绕过。
                return Outcome.failed();
            } catch (IllegalArgumentException exception) {
                return Outcome.failed();
            } catch (RuntimeException exception) {
                if (attempt == properties.getMaxRetries()) {
                    return Outcome.failed();
                }
            }
        }
        return Outcome.failed();
    }

    private boolean serverError(BusinessException exception) {
        Integer code = exception.getCode();
        return code != null && code >= 500 && code < 600;
    }

    private boolean completePersonResult(Result result) {
        return result.modules().stream().allMatch(
                PersonBusinessQueryService.ModuleResult::complete
        )
                && result.travelSummary().tripCount().complete()
                && result.travelSummary().totalAmount().complete()
                && result.reimbursementSummary().paidAmount().complete();
    }

    private MultiPersonSummary summarize(
            List<PersonRequest> requests,
            Outcome[] outcomes,
            boolean cancellationRequested) {
        List<PersonStatus> statuses = new ArrayList<>(requests.size());
        List<AnomalyPerson> anomalies = new ArrayList<>();
        int successfulPeople = 0;
        int tripCount = 0;
        BigDecimal travelAmount = BigDecimal.ZERO;
        BigDecimal reimbursementAmount = BigDecimal.ZERO;
        boolean complete = true;

        for (int index = 0; index < requests.size(); index++) {
            PersonRequest request = requests.get(index);
            Outcome outcome = outcomes[index] == null ? Outcome.cancelled() : outcomes[index];
            statuses.add(new PersonStatus(request.displayLabel(), outcome.status()));
            if (outcome.status() != PersonQueryStatus.SUCCESS) {
                complete = false;
                continue;
            }
            successfulPeople++;
            Result result = outcome.result();
            tripCount += result.travelSummary().tripCount().value();
            travelAmount = travelAmount.add(result.travelSummary().totalAmount().value());
            reimbursementAmount = reimbursementAmount.add(
                    result.reimbursementSummary().paidAmount().value()
            );
            addAllowedAnomaly(request, result, anomalies);
        }

        Aggregate aggregate = complete
                ? new Aggregate(true, successfulPeople, tripCount, travelAmount, reimbursementAmount)
                : incompleteAggregate(successfulPeople);
        MultiPersonSummary.ExecutionStatus status = cancellationRequested
                ? MultiPersonSummary.ExecutionStatus.CANCELLED
                : complete
                ? MultiPersonSummary.ExecutionStatus.COMPLETED
                : MultiPersonSummary.ExecutionStatus.PARTIAL;
        return new MultiPersonSummary(status, aggregate, statuses, anomalies, null);
    }

    private void addAllowedAnomaly(
            PersonRequest request,
            Result result,
            List<AnomalyPerson> anomalies) {
        if (!request.allowAnomalyDisclosure()) {
            return;
        }
        List<String> anomalyTypes = result.attendance().stream()
                .map(AttendanceDayResult::determination)
                .filter(value -> "MISSING_PUNCH".equals(value) || "UNKNOWN".equals(value))
                .distinct()
                .toList();
        if (!anomalyTypes.isEmpty()) {
            anomalies.add(new AnomalyPerson(request.displayLabel(), anomalyTypes));
        }
    }

    private Aggregate incompleteAggregate(int successfulPeople) {
        return new Aggregate(false, successfulPeople, null, null, null);
    }

    private void validate(
            List<PersonRequest> requests,
            BooleanSupplier cancellationRequested) {
        if (requests == null || requests.isEmpty() || cancellationRequested == null) {
            throw new IllegalArgumentException("多人查询请求不完整");
        }
        for (PersonRequest request : requests) {
            if (request == null
                    || !StringUtils.hasText(request.displayLabel())
                    || request.command() == null) {
                throw new IllegalArgumentException("多人查询人员项不完整");
            }
        }
    }

    private boolean hasPending(Outcome[] outcomes) {
        for (Outcome outcome : outcomes) {
            if (outcome == null) {
                return true;
            }
        }
        return false;
    }

    private void cancelAll(
            int size,
            int nextIndex,
            Map<Future<IndexedOutcome>, InFlight> running,
            Outcome[] outcomes) {
        running.forEach((future, item) -> {
            future.cancel(true);
            outcomes[item.index()] = Outcome.cancelled();
        });
        running.clear();
        for (int index = nextIndex; index < size; index++) {
            outcomes[index] = Outcome.cancelled();
        }
    }

    private void timeoutAll(
            int size,
            int nextIndex,
            Map<Future<IndexedOutcome>, InFlight> running,
            Outcome[] outcomes) {
        running.forEach((future, item) -> {
            future.cancel(true);
            outcomes[item.index()] = Outcome.timeout();
        });
        running.clear();
        for (int index = nextIndex; index < size; index++) {
            outcomes[index] = Outcome.timeout();
        }
    }

    private ExecutorService executor(int concurrency) {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "business-person-fan-out-" + sequence.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public record PersonRequest(
            String displayLabel,
            boolean allowAnomalyDisclosure,
            Command command) {

        @Override
        public String toString() {
            return "PersonRequest[displayLabelPresent=" + StringUtils.hasText(displayLabel)
                    + ", allowAnomalyDisclosure=" + allowAnomalyDisclosure + ']';
        }
    }

    private record IndexedOutcome(int index, Outcome outcome) {
    }

    private record InFlight(int index, AtomicLong startedAtNanos) {

        private void markStarted() {
            startedAtNanos.compareAndSet(0L, System.nanoTime());
        }

        private long deadlineNanos(long overallDeadline, long personTimeoutNanos) {
            long personDeadline = personDeadlineNanos(personTimeoutNanos);
            return personDeadline == Long.MAX_VALUE
                    ? overallDeadline
                    : Math.min(overallDeadline, personDeadline);
        }

        private long personDeadlineNanos(long personTimeoutNanos) {
            long started = startedAtNanos.get();
            return started == 0L ? Long.MAX_VALUE : started + personTimeoutNanos;
        }
    }

    private record Outcome(PersonQueryStatus status, Result result) {

        private static Outcome success(Result result) {
            return new Outcome(PersonQueryStatus.SUCCESS, result);
        }

        private static Outcome partial(Result result) {
            return new Outcome(PersonQueryStatus.PARTIAL, result);
        }

        private static Outcome failed() {
            return new Outcome(PersonQueryStatus.FAILED, null);
        }

        private static Outcome timeout() {
            return new Outcome(PersonQueryStatus.TIMEOUT, null);
        }

        private static Outcome cancelled() {
            return new Outcome(PersonQueryStatus.CANCELLED, null);
        }
    }

    /** 使用单调时钟均匀发放调用配额，避免额外引入限流依赖或调度线程。 */
    private static final class RateGate {

        private final AtomicLong nextPermitNanos = new AtomicLong();
        private final long intervalNanos;

        private RateGate(int requestsPerSecond) {
            this.intervalNanos = Math.max(1L, TimeUnit.SECONDS.toNanos(1) / requestsPerSecond);
        }

        private boolean acquire(BooleanSupplier cancellationRequested) {
            while (true) {
                if (cancellationRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    return false;
                }
                long now = System.nanoTime();
                long permitNanos = nextPermitNanos.get();
                long waitNanos = permitNanos - now;
                if (waitNanos <= 0L
                        && nextPermitNanos.compareAndSet(
                        permitNanos,
                        now + intervalNanos
                )) {
                    return true;
                }
                if (waitNanos > 0L) {
                    LockSupport.parkNanos(
                            Math.min(waitNanos, TimeUnit.MILLISECONDS.toNanos(10))
                    );
                }
            }
        }
    }
}
