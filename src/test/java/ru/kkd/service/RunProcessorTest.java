package ru.kkd.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.kkd.domain.TestData.activeGoal;
import static ru.kkd.domain.TestData.keyResult;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.stubbing.Answer;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import ru.kkd.domain.delta.BaseRunSelector;
import ru.kkd.domain.model.GoalLevel;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunStatus;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.Snapshot;
import ru.kkd.domain.rules.RuleCatalog;
import ru.kkd.domain.rules.RuleEngine;
import ru.kkd.repository.IssueRepository;
import ru.kkd.repository.RuleRepository;
import ru.kkd.repository.RunRepository;
import ru.kkd.repository.SnapshotRepository;

class RunProcessorTest {

    private static final String RUN_ID = "RUN-002";
    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 10, 26);
    private static final Run RUN = run(RUN_ID, SNAPSHOT_DATE, RunStatus.RUNNING);

    // Текст из раздела 8.2 design.md. Сверяем с ним, а не с константой класса: константа могла бы уйти от спецификации.
    private static final String SNAPSHOT_TOO_OLD_TEXT =
            "Снимка нет, а дата снимка раньше последнего завершённого прогона";

    private static final String EXCEPTION_TEXT = "select owner_name from goal_snapshot: подробности сбоя";

    // У G-1 нет КР, факт КР-2 старше порога на день, факт КР-3 ровно на пороге: настоящий движок даёт две проблемы.
    private static final Snapshot SNAPSHOT = new Snapshot(
            SNAPSHOT_DATE,
            List.of(
                    activeGoal("G-1", "HQ-CIB", GoalLevel.DEPARTMENT, null),
                    activeGoal("G-2", "HQ-CIB", GoalLevel.DEPARTMENT, null),
                    activeGoal("G-3", "HQ-CIB", GoalLevel.DEPARTMENT, null)),
            List.of(
                    keyResult("KR-2", "G-2", "100.00", SNAPSHOT_DATE.minusDays(46)),
                    keyResult("KR-3", "G-3", "100.00", SNAPSHOT_DATE.minusDays(45))));

    private static final RuleCatalog CATALOG = new RuleCatalog(Map.of(
            RuleCode.GOAL_NO_KR, Severity.CRITICAL,
            RuleCode.KR_WEIGHTS_SUM, Severity.MAJOR,
            RuleCode.CASCADE_NO_PARENT, Severity.MAJOR,
            RuleCode.KR_STALE_PROGRESS, Severity.MINOR));

    private final RunRepository runs = mock(RunRepository.class);
    private final SnapshotRepository snapshots = mock(SnapshotRepository.class);
    private final RuleRepository rules = mock(RuleRepository.class);
    private final IssueRepository issues = mock(IssueRepository.class);

    // Движок и выбор базы настоящие, подмена только там, где нужно исключение.
    private final RuleEngine engine = spy(new RuleEngine());
    private final BaseRunSelector selector = spy(new BaseRunSelector());

    private final RunProcessor processor = processorWith(TransactionOperations.withoutTransaction());

    // Вызовы и границы транзакций по порядку, для тестов раздела «Транзакции».
    private final List<String> events = new ArrayList<>();

    @BeforeEach
    void snapshotExistsAndThisIsTheOnlyRun() {
        when(runs.findAll()).thenReturn(List.of(RUN));
        when(snapshots.exists(RUN_ID)).thenReturn(true);
        when(snapshots.load(RUN_ID, SNAPSHOT_DATE)).thenReturn(SNAPSHOT);
        when(rules.loadCatalog()).thenReturn(CATALOG);
    }

    // Успех

    @Test
    void successSavesIssuesOfRuleEngineAndCompletesWithBase() {
        when(runs.findAll()).thenReturn(List.of(completed("RUN-001", SNAPSHOT_DATE.minusDays(7)), RUN));

        processor.process(RUN);

        assertThat(savedIssues())
                .extracting(Issue::ruleCode, Issue::objectId, Issue::severity)
                .containsExactly(
                        tuple(RuleCode.GOAL_NO_KR, "G-1", Severity.CRITICAL),
                        tuple(RuleCode.KR_STALE_PROGRESS, "KR-2", Severity.MINOR));
        verify(runs).markCompleted(RUN_ID, "RUN-001");
        verify(runs, never()).markFailed(any(), any());
    }

    @Test
    void existingSnapshotIsNotFrozenAgain() {
        processor.process(RUN);

        verify(snapshots, never()).freeze(any());
        verify(snapshots).load(RUN_ID, SNAPSHOT_DATE);
        verify(engine).check(SNAPSHOT, CATALOG);
    }

    @Test
    void missingSnapshotIsFrozenBeforeLoadAndStepsGoInOrderOfSection62() {
        when(snapshots.exists(RUN_ID)).thenReturn(false);

        processor.process(RUN);

        InOrder order = inOrder(snapshots, engine, issues, runs);
        order.verify(snapshots).freeze(RUN_ID);
        order.verify(snapshots).load(RUN_ID, SNAPSHOT_DATE);
        order.verify(engine).check(SNAPSHOT, CATALOG);
        order.verify(issues).saveAll(eq(RUN_ID), anyList());
        order.verify(runs).markCompleted(RUN_ID, null);
        verify(runs, never()).markFailed(any(), any());
    }

    @Test
    void runWithoutIssuesStillCompletes() {
        when(snapshots.load(RUN_ID, SNAPSHOT_DATE)).thenReturn(new Snapshot(SNAPSHOT_DATE, List.of(), List.of()));

        processor.process(RUN);

        // Вызывать saveAll с пустым списком можно, записывать строки нельзя.
        ArgumentCaptor<List<Issue>> saved = ArgumentCaptor.captor();
        verify(issues, atMost(1)).saveAll(eq(RUN_ID), saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(written -> assertThat(written).isEmpty());
        verify(runs).markCompleted(RUN_ID, null);
        verify(runs, never()).markFailed(any(), any());
    }

    // База сравнения, раздел 6.4

    @Test
    void firstRunCompletesWithoutBase() {
        processor.process(RUN);

        verify(runs).markCompleted(RUN_ID, null);
        verify(runs, never()).markFailed(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"FAILED", "RUNNING"})
    void earlierRunsThatAreNotCompletedGiveNoBase(RunStatus status) {
        when(runs.findAll()).thenReturn(List.of(
                run("RUN-000", SNAPSHOT_DATE.minusDays(14), status),
                run("RUN-001", SNAPSHOT_DATE.minusDays(7), status),
                RUN));

        processor.process(RUN);

        verify(runs).markCompleted(RUN_ID, null);
        verify(runs, never()).markFailed(any(), any());
    }

    @Test
    void failedRunBetweenIsSkippedForBase() {
        when(runs.findAll()).thenReturn(List.of(
                completed("RUN-000", SNAPSHOT_DATE.minusDays(14)),
                run("RUN-001", SNAPSHOT_DATE.minusDays(7), RunStatus.FAILED),
                RUN));

        processor.process(RUN);

        verify(runs).markCompleted(RUN_ID, "RUN-000");
    }

    // Снимок, который нельзя снять, разделы 6.2 и 8.2

    @Test
    void missingSnapshotWithDateBeforeLastCompletedMarksRunFailedWithTextOfSection82() {
        when(snapshots.exists(RUN_ID)).thenReturn(false);
        when(runs.findAll()).thenReturn(List.of(
                completed("RUN-000", SNAPSHOT_DATE.minusDays(7)),
                RUN,
                completed("RUN-003", SNAPSHOT_DATE.plusDays(1))));

        assertThatCode(() -> processor.process(RUN)).doesNotThrowAnyException();

        verify(runs).markFailed(RUN_ID, SNAPSHOT_TOO_OLD_TEXT);
        verify(snapshots, never()).freeze(any());
        verifyNoInteractions(engine, issues);
        verify(runs, never()).markCompleted(any(), any());
    }

    @Test
    void missingSnapshotWithDateEqualToLastCompletedIsFrozen() {
        when(snapshots.exists(RUN_ID)).thenReturn(false);
        when(runs.findAll()).thenReturn(List.of(completed("RUN-001", SNAPSHOT_DATE), RUN));

        processor.process(RUN);

        verify(snapshots).freeze(RUN_ID);
        verify(runs).markCompleted(RUN_ID, "RUN-001");
        verify(runs, never()).markFailed(any(), any());
    }

    @Test
    void dateEqualToLastCompletedIsNotTooOldEvenWhenItsIdIsGreater() {
        when(snapshots.exists(RUN_ID)).thenReturn(false);
        when(runs.findAll()).thenReturn(List.of(RUN, completed("RUN-009", SNAPSHOT_DATE)));

        processor.process(RUN);

        // Порядок (snapshot_date, id) ставит RUN-009 позже текущего, поэтому базой он не становится.
        verify(snapshots).freeze(RUN_ID);
        verify(runs).markCompleted(RUN_ID, null);
        verify(runs, never()).markFailed(any(), any());
    }

    @Test
    void missingSnapshotWithDateAfterLastCompletedIsFrozen() {
        when(snapshots.exists(RUN_ID)).thenReturn(false);
        when(runs.findAll()).thenReturn(List.of(completed("RUN-001", SNAPSHOT_DATE.minusDays(1)), RUN));

        processor.process(RUN);

        verify(snapshots).freeze(RUN_ID);
        verify(runs).markCompleted(RUN_ID, "RUN-001");
        verify(runs, never()).markFailed(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"FAILED", "RUNNING"})
    void laterRunThatIsNotCompletedDoesNotMakeSnapshotTooOld(RunStatus status) {
        when(snapshots.exists(RUN_ID)).thenReturn(false);
        when(runs.findAll()).thenReturn(List.of(RUN, run("RUN-003", SNAPSHOT_DATE.plusDays(7), status)));

        processor.process(RUN);

        verify(snapshots).freeze(RUN_ID);
        verify(runs).markCompleted(RUN_ID, null);
        verify(runs, never()).markFailed(any(), any());
    }

    @Test
    void existingSnapshotIsCalculatedEvenWithDateBeforeLastCompleted() {
        when(runs.findAll()).thenReturn(List.of(RUN, completed("RUN-003", SNAPSHOT_DATE.plusDays(7))));

        processor.process(RUN);

        verify(snapshots, never()).freeze(any());
        verify(runs).markCompleted(RUN_ID, null);
        verify(runs, never()).markFailed(any(), any());
    }

    // Сбои, раздел 6.3

    @Test
    void engineFailureMarksRunFailedWithConstantTextWithoutExceptionText() {
        doThrow(new IllegalStateException(EXCEPTION_TEXT)).when(engine).check(any(), any());

        assertThatCode(() -> processor.process(RUN)).doesNotThrowAnyException();

        ArgumentCaptor<String> error = ArgumentCaptor.captor();
        verify(runs).markFailed(eq(RUN_ID), error.capture());
        assertThat(error.getValue())
                .isEqualTo(RunProcessor.GENERAL_ERROR)
                .isNotBlank()
                .isNotEqualTo(SNAPSHOT_TOO_OLD_TEXT)
                .doesNotContain(EXCEPTION_TEXT)
                .doesNotContain("IllegalStateException");
        verify(issues, never()).saveAll(any(), any());
        verify(runs, never()).markCompleted(any(), any());
    }

    @Test
    void exceptionTextAndStackGoToLogOnly() {
        RuntimeException failure = new IllegalStateException(EXCEPTION_TEXT);
        doThrow(failure).when(engine).check(any(), any());
        Logger logger = (Logger) LoggerFactory.getLogger("ru.kkd");
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        logger.addAppender(log);

        try {
            processor.process(RUN);
        } finally {
            logger.detachAppender(log);
        }

        assertThat(log.list).anySatisfy(event -> {
            assertThat(event.getLevel().isGreaterOrEqual(Level.WARN)).isTrue();
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo(EXCEPTION_TEXT);
        });
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("notReadyAtEveryStep")
    void notReadyDatabaseAtAnyStepGoesUpWithoutMarkingFailed(Step step, RuntimeException error) {
        failAt(step, error);

        assertThatThrownBy(() -> processor.process(RUN)).isSameAs(error);

        verify(runs, never()).markFailed(any(), any());
        // На шаге MARK_COMPLETED первый вызов и есть сбой, повторять его нельзя. На других шагах до него дело не доходит.
        verify(runs, step == Step.MARK_COMPLETED ? times(1) : never()).markCompleted(any(), any());
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("otherErrorsAtEveryStep")
    void otherErrorAtAnyStepMarksRunFailedAndDoesNotGoUp(Step step, RuntimeException error) {
        failAt(step, error);

        assertThatCode(() -> processor.process(RUN)).doesNotThrowAnyException();

        verify(runs).markFailed(RUN_ID, RunProcessor.GENERAL_ERROR);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writeFailures")
    void failureToWriteGeneralErrorGoesUp(RuntimeException writeFailure) {
        doThrow(new IllegalStateException(EXCEPTION_TEXT)).when(engine).check(any(), any());
        doThrow(writeFailure).when(runs).markFailed(RUN_ID, RunProcessor.GENERAL_ERROR);

        assertThatThrownBy(() -> processor.process(RUN)).isSameAs(writeFailure);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writeFailures")
    void failureToWriteTooOldErrorGoesUp(RuntimeException writeFailure) {
        when(snapshots.exists(RUN_ID)).thenReturn(false);
        when(runs.findAll()).thenReturn(List.of(RUN, completed("RUN-003", SNAPSHOT_DATE.plusDays(1))));
        // Подмена только для этого текста: если обработчик поймает сбой и запишет общий текст, сбой не дойдёт наверх.
        doThrow(writeFailure).when(runs).markFailed(RUN_ID, SNAPSHOT_TOO_OLD_TEXT);

        assertThatThrownBy(() -> processor.process(RUN)).isSameAs(writeFailure);

        verify(runs, never()).markFailed(RUN_ID, RunProcessor.GENERAL_ERROR);
    }

    // Транзакции: успех одной транзакцией, а failed отдельно от отменённой, разделы 6.2 и 6.3

    @Test
    void successRunsInOneTransaction() {
        when(snapshots.exists(RUN_ID)).thenReturn(false);
        recordCalls();

        processorWith(new RecordingTransactions()).process(RUN);

        assertThat(events).containsExactly("begin", "freeze", "load", "saveAll", "markCompleted", "commit");
    }

    @Test
    void engineFailureRollsBackBeforeFailedIsWritten() {
        recordCalls();
        doThrow(new IllegalStateException(EXCEPTION_TEXT)).when(engine).check(any(), any());

        processorWith(new RecordingTransactions()).process(RUN);

        assertThat(events).containsSubsequence("begin", "rollback", "markFailed");
    }

    @Test
    void failureAfterIssuesAreWrittenRollsThemBackBeforeFailedIsWritten() {
        recordCalls();
        doThrow(new IllegalStateException(EXCEPTION_TEXT)).when(runs).markCompleted(any(), any());

        processorWith(new RecordingTransactions()).process(RUN);

        assertThat(events).containsSubsequence("begin", "saveAll", "rollback", "markFailed");
    }

    @Test
    void notReadyDatabaseRollsBackAndDoesNotWriteFailed() {
        RuntimeException tableMissing = sqlError("42P01");
        recordCalls();
        doThrow(tableMissing).when(rules).loadCatalog();

        assertThatThrownBy(() -> processorWith(new RecordingTransactions()).process(RUN)).isSameAs(tableMissing);

        assertThat(events).containsSubsequence("begin", "rollback").doesNotContain("markFailed");
    }

    @Test
    void connectionLostWhenTransactionStartsGoesUpWithoutMarkingFailed() {
        RuntimeException noConnection = new CannotCreateTransactionException(
                "Could not open JDBC Connection for transaction",
                new SQLTransientConnectionException("Connection is not available, request timed out"));
        // Сбой только один раз: связь вернулась, следующая транзакция начнётся, и подмена не спрячет запись failed.
        TransactionOperations failsOnce = new TransactionOperations() {
            private boolean failed;

            @Override
            public <T> T execute(TransactionCallback<T> action) {
                if (!failed) {
                    failed = true;
                    throw noConnection;
                }
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };

        assertThatThrownBy(() -> processorWith(failsOnce).process(RUN)).isSameAs(noConnection);

        verify(runs, never()).markFailed(any(), any());
        verifyNoInteractions(engine, issues);
    }

    @Test
    void connectionLostOnCommitGoesUpWithoutMarkingFailed() {
        RuntimeException lostOnCommit = new TransactionSystemException(
                "Could not commit JDBC transaction",
                new PSQLException("An I/O error occurred while sending to the backend", PSQLState.CONNECTION_FAILURE));

        assertThatThrownBy(() -> processorWith(new RecordingTransactions(lostOnCommit)).process(RUN))
                .isSameAs(lostOnCommit);

        verify(runs, never()).markFailed(any(), any());
    }

    @Test
    void otherFailureOnCommitMarksRunFailed() {
        RuntimeException refusedOnCommit = new TransactionSystemException(
                "Could not commit JDBC transaction", new SQLException("нарушение ключа", "23505"));

        assertThatCode(() -> processorWith(new RecordingTransactions(refusedOnCommit)).process(RUN))
                .doesNotThrowAnyException();

        verify(runs).markFailed(RUN_ID, RunProcessor.GENERAL_ERROR);
    }

    @Test
    void connectionLostInsideTransactionWithFailedRollbackGoesUpWithoutMarkingFailed() {
        // Настоящий TransactionTemplate: если откат тоже упал, наверх идёт исключение отката, а не исходное.
        doThrow(new DataAccessResourceFailureException(
                "I/O error",
                new PSQLException("An I/O error occurred while sending to the backend", PSQLState.CONNECTION_FAILURE)))
                .when(snapshots).load(RUN_ID, SNAPSHOT_DATE);
        RunProcessor withFailingRollback = processorWith(transactionsWithFailingRollback());

        assertThatThrownBy(() -> withFailingRollback.process(RUN)).isInstanceOf(TransactionSystemException.class);

        verify(runs, never()).markFailed(any(), any());
    }

    @Test
    void connectionLostOnCommitWithFailedRollbackGoesUpWithoutMarkingFailed() {
        // Настоящий TransactionTemplate, сбой фиксации в том виде, как его отдаёт JdbcTransactionManager:
        // менеджер пробует откат, а наверх идёт исключение отката, исходная ошибка не сохраняется.
        RunProcessor withLostCommit = processorWith(transactionsWithFailingRollback(
                new DataAccessResourceFailureException(
                        "JDBC commit",
                        new PSQLException("An I/O error occurred", PSQLState.CONNECTION_FAILURE))));

        assertThatThrownBy(() -> withLostCommit.process(RUN)).isInstanceOf(TransactionSystemException.class);

        verify(runs, never()).markFailed(any(), any());
    }

    @Test
    void otherErrorInsideTransactionWithFailedRollbackMarksRunFailed() {
        // Hikari закрывает соединение и при коде 0A000, поэтому откат падает, хотя связь есть. Решает исходная ошибка.
        doThrow(sqlError("0A000")).when(snapshots).load(RUN_ID, SNAPSHOT_DATE);
        RunProcessor withFailingRollback = processorWith(transactionsWithFailingRollback());

        assertThatCode(() -> withFailingRollback.process(RUN)).doesNotThrowAnyException();

        verify(runs).markFailed(RUN_ID, RunProcessor.GENERAL_ERROR);
    }

    // Шаги прогона, на которых может случиться сбой. Каждый вызывается и в успешном пути.
    enum Step {
        LIST_RUNS,
        CHECK_SNAPSHOT,
        FREEZE,
        LOAD_SNAPSHOT,
        LOAD_CATALOG,
        RULE_ENGINE,
        SELECT_BASE,
        SAVE_ISSUES,
        MARK_COMPLETED
    }

    private void failAt(Step step, RuntimeException error) {
        switch (step) {
            case LIST_RUNS -> doThrow(error).when(runs).findAll();
            case CHECK_SNAPSHOT -> doThrow(error).when(snapshots).exists(RUN_ID);
            case FREEZE -> {
                when(snapshots.exists(RUN_ID)).thenReturn(false);
                doThrow(error).when(snapshots).freeze(RUN_ID);
            }
            case LOAD_SNAPSHOT -> doThrow(error).when(snapshots).load(RUN_ID, SNAPSHOT_DATE);
            case LOAD_CATALOG -> doThrow(error).when(rules).loadCatalog();
            case RULE_ENGINE -> doThrow(error).when(engine).check(any(), any());
            case SELECT_BASE -> doThrow(error).when(selector).select(any(), any());
            case SAVE_ISSUES -> doThrow(error).when(issues).saveAll(eq(RUN_ID), any());
            case MARK_COMPLETED -> doThrow(error).when(runs).markCompleted(any(), any());
        }
    }

    static Stream<Arguments> notReadyAtEveryStep() {
        return Arrays.stream(Step.values()).flatMap(step -> notReadyErrors().map(error -> arguments(step, error)));
    }

    static Stream<Arguments> otherErrorsAtEveryStep() {
        return Arrays.stream(Step.values()).flatMap(step -> otherErrors().map(error -> arguments(step, error)));
    }

    static Stream<Named<RuntimeException>> notReadyErrors() {
        return Stream.of(
                named("42P01 в SQLException", sqlError("42P01")),
                named("42P01 в PSQLException", new BadSqlGrammarException(
                        "выборка", "SELECT * FROM goal_snapshot",
                        new PSQLException("отношение не существует", PSQLState.UNDEFINED_TABLE))),
                named("соединение не получено", new CannotGetJdbcConnectionException(
                        "Failed to obtain JDBC Connection",
                        new SQLTransientConnectionException("Connection is not available, request timed out"))),
                named("обрыв связи 08006", new DataAccessResourceFailureException(
                        "I/O error",
                        new PSQLException("An I/O error occurred", PSQLState.CONNECTION_FAILURE))));
    }

    static Stream<Named<RuntimeException>> otherErrors() {
        return Stream.of(
                named("опечатка в колонке 42703", sqlError("42703")),
                named("синтаксис 42601", sqlError("42601")),
                named("дубль ключа 23505", new DuplicateKeyException(
                        "дубль", new SQLException("нарушение ключа", "23505"))),
                named("ошибка логики", new IllegalStateException(EXCEPTION_TEXT)));
    }

    static Stream<Arguments> writeFailures() {
        return Stream.of(
                arguments(named("ошибка логики", new IllegalStateException("сбой записи"))),
                arguments(named("опечатка в колонке 42703", sqlError("42703"))),
                arguments(named("обрыв связи", new DataAccessResourceFailureException(
                        "I/O error",
                        new PSQLException("An I/O error occurred", PSQLState.CONNECTION_FAILURE)))));
    }

    private static BadSqlGrammarException sqlError(String sqlState) {
        return new BadSqlGrammarException(
                "выборка", "SELECT * FROM goal_snapshot", new SQLException("ошибка базы", sqlState));
    }

    private RunProcessor processorWith(TransactionOperations transactions) {
        return new RunProcessor(runs, snapshots, rules, issues, engine, selector, transactions);
    }

    private List<Issue> savedIssues() {
        ArgumentCaptor<List<Issue>> saved = ArgumentCaptor.captor();
        verify(issues).saveAll(eq(RUN_ID), saved.capture());
        return saved.getValue();
    }

    private void recordCalls() {
        doAnswer(record("freeze")).when(snapshots).freeze(RUN_ID);
        doAnswer(call -> {
            events.add("load");
            return SNAPSHOT;
        }).when(snapshots).load(RUN_ID, SNAPSHOT_DATE);
        doAnswer(record("saveAll")).when(issues).saveAll(eq(RUN_ID), any());
        doAnswer(record("markCompleted")).when(runs).markCompleted(any(), any());
        doAnswer(record("markFailed")).when(runs).markFailed(any(), any());
    }

    private Answer<Object> record(String event) {
        return call -> {
            events.add(event);
            return null;
        };
    }

    private static Run completed(String id, LocalDate snapshotDate) {
        return run(id, snapshotDate, RunStatus.COMPLETED);
    }

    private static Run run(String id, LocalDate snapshotDate, RunStatus status) {
        return new Run(id, snapshotDate, status, null, null);
    }

    /** Настоящей базы в тесте нет, поэтому начало и конец транзакции пишутся в общий журнал вместе с вызовами. */
    private final class RecordingTransactions implements TransactionOperations {

        private RuntimeException firstCommitFailure;

        RecordingTransactions() {
            this(null);
        }

        RecordingTransactions(RuntimeException firstCommitFailure) {
            this.firstCommitFailure = firstCommitFailure;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            events.add("begin");
            SimpleTransactionStatus status = new SimpleTransactionStatus();
            T result;
            try {
                result = action.doInTransaction(status);
            } catch (RuntimeException | Error failure) {
                events.add("rollback");
                throw failure;
            }
            if (firstCommitFailure != null) {
                RuntimeException failure = firstCommitFailure;
                firstCommitFailure = null;
                events.add("rollback");
                throw failure;
            }
            events.add(status.isRollbackOnly() ? "rollback" : "commit");
            return result;
        }
    }

    private static TransactionOperations transactionsWithFailingRollback() {
        return transactionsWithFailingRollback(null);
    }

    private static TransactionOperations transactionsWithFailingRollback(RuntimeException commitFailure) {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                if (commitFailure != null) {
                    throw commitFailure;
                }
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                throw new TransactionSystemException("JDBC rollback failed", new SQLException("Connection is closed"));
            }
        });
    }
}
