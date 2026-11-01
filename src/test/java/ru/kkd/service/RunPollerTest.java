package ru.kkd.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.CannotCreateTransactionException;
import ru.kkd.config.SchedulingConfig;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunStatus;
import ru.kkd.repository.RunRepository;

// Такт это цикл, а часть подмен всегда отдаёт один и тот же прогон. Если код не завершит такт после сбоя,
// упасть должен тест, а не зависнуть сборка. В обычном режиме JUnit только прерывает поток, поэтому поток отдельный.
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RunPollerTest {

    private static final Run FIRST = running("RUN-001", "2026-10-19");
    private static final Run SECOND = running("RUN-002", "2026-10-26");

    private final RunRepository runs = mock(RunRepository.class);
    private final RunProcessor processor = mock(RunProcessor.class);
    private final RunPoller poller = new RunPoller(runs, processor);

    private final Logger logger = (Logger) LoggerFactory.getLogger("ru.kkd.service.RunPoller");
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    @BeforeEach
    void attachLog() {
        log.start();
        logger.addAppender(log);
    }

    @AfterEach
    void detachLog() {
        logger.detachAppender(log);
        log.stop();
    }

    // Обработка прогонов в такте, раздел 6.2

    @Test
    void twoRunningRunsAreProcessedInOrderWithinOneTick() {
        when(runs.findNextRunning()).thenReturn(Optional.of(FIRST), Optional.of(SECOND), Optional.empty());

        poller.tick();

        // Выборка повторяется после каждого прогона: прошлый прогон мог изменить, какой будет следующим.
        InOrder order = inOrder(runs, processor);
        order.verify(runs).findNextRunning();
        order.verify(processor).process(FIRST);
        order.verify(runs).findNextRunning();
        order.verify(processor).process(SECOND);
        order.verify(runs).findNextRunning();
        order.verifyNoMoreInteractions();
    }

    @Test
    void withoutRunningRunsProcessorIsNotCalled() {
        when(runs.findNextRunning()).thenReturn(Optional.empty());

        poller.tick();

        verify(runs).findNextRunning();
        verifyNoInteractions(processor);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notReadyErrors")
    void notReadyDatabaseWhileFetchingEndsTickWithoutThrowing(RuntimeException error) {
        when(runs.findNextRunning()).thenThrow(error);

        assertThatCode(poller::tick).doesNotThrowAnyException();

        verify(runs).findNextRunning();
        verifyNoInteractions(processor);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notReadyErrors")
    void notReadyDatabaseWhileProcessingEndsTickWithoutThrowing(RuntimeException error) {
        when(runs.findNextRunning()).thenReturn(Optional.of(FIRST), Optional.of(SECOND), Optional.empty());
        doThrow(error).when(processor).process(FIRST);

        assertThatCode(poller::tick).doesNotThrowAnyException();

        verify(processor).process(FIRST);
        verify(processor, never()).process(SECOND);
        verify(runs).findNextRunning();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherErrors")
    void otherErrorWhileFetchingEndsTickWithoutThrowing(RuntimeException error) {
        when(runs.findNextRunning()).thenThrow(error);

        assertThatCode(poller::tick).doesNotThrowAnyException();

        verify(runs).findNextRunning();
        verifyNoInteractions(processor);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherErrors")
    void otherErrorWhileProcessingEndsTickAndNextRunIsNotTaken(RuntimeException error) {
        when(runs.findNextRunning()).thenReturn(Optional.of(FIRST), Optional.of(SECOND), Optional.empty());
        doThrow(error).when(processor).process(FIRST);

        assertThatCode(poller::tick).doesNotThrowAnyException();

        verify(processor).process(FIRST);
        verify(processor, never()).process(SECOND);
        verify(runs).findNextRunning();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allErrors")
    void nextTickTakesTheSameRunAgain(RuntimeException error) {
        // Прогон остаётся running, поэтому выборка снова отдаёт его. На втором такте processor выполняется без сбоя.
        when(runs.findNextRunning()).thenReturn(Optional.of(FIRST), Optional.of(FIRST), Optional.empty());
        doThrow(error).doNothing().when(processor).process(FIRST);

        poller.tick();
        poller.tick();

        verify(processor, times(2)).process(FIRST);
        verify(runs, times(3)).findNextRunning();
    }

    // Журнал: логгер, уровни и стек по контракту RunPoller

    @Test
    void firstFailedTickLogsOneWarnWithoutThrowable() {
        when(runs.findNextRunning()).thenThrow(tableMissing());

        poller.tick();

        assertThat(logged()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
            // В тексте ошибки базы две строки, в журнал без стека многострочный текст попасть не должен.
            assertThat(event.getFormattedMessage()).doesNotContain("\n");
        });
    }

    @Test
    void repeatedNotReadyTicksDoNotRepeatWarn() {
        when(runs.findNextRunning()).thenThrow(tableMissing());

        poller.tick();
        poller.tick();
        poller.tick();

        assertThat(logged()).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
    }

    @Test
    void anotherReasonOfNotReadyIsTheSameState() {
        when(runs.findNextRunning()).thenThrow(tableMissing()).thenThrow(connectionLost());

        poller.tick();
        poller.tick();

        assertThat(logged()).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
    }

    @Test
    void notReadyWhileProcessingLogsOneWarnEvenIfFetchWorks() {
        // Выборка работает, а таблицы снимков нет: состояние не менялось, и журнал не должен мигать WARN и INFO.
        when(runs.findNextRunning()).thenReturn(Optional.of(FIRST));
        doThrow(tableMissing()).when(processor).process(FIRST);

        poller.tick();
        poller.tick();
        poller.tick();

        assertThat(logged()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void returnOfDatabaseLogsOneInfoWithoutThrowable() {
        when(runs.findNextRunning()).thenThrow(tableMissing()).thenReturn(Optional.empty());

        poller.tick();
        poller.tick();
        poller.tick();

        assertThat(logged()).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.INFO);
        assertThat(logged().get(1).getThrowableProxy()).isNull();
    }

    @Test
    void returnOfDatabaseIsLoggedOnceWhenTickProcessesRuns() {
        when(runs.findNextRunning())
                .thenThrow(tableMissing())
                .thenReturn(Optional.of(FIRST), Optional.of(SECOND), Optional.empty());

        poller.tick();
        poller.tick();

        assertThat(logged()).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.INFO);
    }

    @Test
    void returnOfDatabaseAfterNotReadyWhileProcessingLogsOneInfo() {
        when(runs.findNextRunning()).thenReturn(Optional.of(FIRST), Optional.of(FIRST), Optional.empty());
        doThrow(tableMissing()).doNothing().when(processor).process(FIRST);

        poller.tick();
        poller.tick();

        assertThat(logged()).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.INFO);
    }

    @Test
    void everyChangeOfStateIsLoggedOnce() {
        when(runs.findNextRunning())
                .thenThrow(tableMissing())
                .thenReturn(Optional.empty())
                .thenThrow(connectionLost())
                .thenThrow(connectionLost())
                .thenReturn(Optional.empty());

        for (int tick = 0; tick < 6; tick++) {
            poller.tick();
        }

        assertThat(logged())
                .extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.WARN, Level.INFO, Level.WARN, Level.INFO);
    }

    @Test
    void ticksWithoutOutageAndWithoutRunsLogNothing() {
        when(runs.findNextRunning()).thenReturn(Optional.empty());

        poller.tick();
        poller.tick();

        assertThat(logged()).isEmpty();
    }

    @Test
    void otherErrorWhileProcessingLogsErrorWithThrowable() {
        when(runs.findNextRunning()).thenReturn(Optional.of(FIRST), Optional.empty());
        doThrow(new IllegalStateException("сбой записи failed")).when(processor).process(FIRST);

        poller.tick();

        assertThat(logged()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("сбой записи failed");
        });
    }

    @Test
    void undefinedColumnWhileFetchingLogsErrorWithThrowableAndNotWarn() {
        when(runs.findNextRunning()).thenThrow(badSql("42703"));

        poller.tick();

        assertThat(logged()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    void otherErrorAfterNotReadyStillLogsErrorWithThrowable() {
        when(runs.findNextRunning()).thenThrow(tableMissing()).thenThrow(new IllegalStateException("сбой"));

        poller.tick();
        poller.tick();

        // Считать ли такую ошибку возвратом базы, контракт не говорит, поэтому INFO здесь не проверяется.
        assertThat(logged()).filteredOn(event -> event.getLevel() == Level.WARN).hasSize(1);
        assertThat(logged()).filteredOn(event -> event.getLevel() == Level.ERROR).singleElement()
                .satisfies(event -> assertThat(event.getThrowableProxy()).isNotNull());
    }

    @Test
    void otherErrorWithoutOutageIsNotFollowedByReturnOfDatabase() {
        when(runs.findNextRunning()).thenThrow(new IllegalStateException("сбой")).thenReturn(Optional.empty());

        poller.tick();
        poller.tick();

        assertThat(logged()).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    }

    // Настройка такта, разделы 6.2 и 10

    @Test
    void tickHasPauseAfterPreviousTickAndNoInitialDelay() throws Exception {
        Scheduled scheduled = RunPoller.class.getMethod("tick").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${kkd.run-poll-ms}");
        assertThat(scheduled.initialDelay()).isEqualTo(-1);
        assertThat(scheduled.initialDelayString()).isEmpty();
    }

    @Test
    void pollPeriodComesFromEnvironmentWithDefault2000() throws Exception {
        Properties properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

        assertThat(properties.getProperty("kkd.run-poll-ms")).isEqualTo("${KKD_RUN_POLL_MS:2000}");
    }

    @Test
    void schedulingIsEnabled() {
        assertThat(SchedulingConfig.class).hasAnnotation(EnableScheduling.class);
    }

    static Stream<Arguments> notReadyErrors() {
        return Stream.of(
                arguments(named("таблицы нет, 42P01", tableMissing())),
                arguments(named("нет связи", connectionLost())),
                arguments(named("транзакция не начата", new CannotCreateTransactionException(
                        "Could not open JDBC Connection for transaction"))));
    }

    static Stream<Arguments> otherErrors() {
        return Stream.of(
                arguments(named("ошибка логики", new IllegalStateException("сбой"))),
                arguments(named("опечатка в колонке 42703", badSql("42703"))));
    }

    static Stream<Arguments> allErrors() {
        return Stream.concat(notReadyErrors(), otherErrors());
    }

    /** Текст в две строки, как у настоящей ошибки PostgreSQL. */
    private static BadSqlGrammarException tableMissing() {
        return new BadSqlGrammarException(
                "выборка", "SELECT * FROM kkd_run",
                new SQLException("ОШИБКА: отношение \"kkd_run\" не существует\n  Позиция: 15", "42P01"));
    }

    private static CannotGetJdbcConnectionException connectionLost() {
        return new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection");
    }

    private static BadSqlGrammarException badSql(String sqlState) {
        return new BadSqlGrammarException("выборка", "SELECT * FROM kkd_run", new SQLException("ошибка базы", sqlState));
    }

    /** События от INFO и выше. Ниже INFO в журнал по умолчанию ничего не попадает, и контракт этого не касается. */
    private List<ILoggingEvent> logged() {
        return log.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO)).toList();
    }

    private static Run running(String id, String snapshotDate) {
        return new Run(id, LocalDate.parse(snapshotDate), RunStatus.RUNNING, null, null);
    }
}
