package ru.kkd.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

class DbStateTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("tableIsMissing")
    void missingTableMeansNotReady(Throwable error) {
        assertThat(DbState.isNotReady(error)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noConnection")
    void lostConnectionMeansNotReady(Throwable error) {
        assertThat(DbState.isNotReady(error)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherErrors")
    void otherErrorsDoNotMeanNotReady(Throwable error) {
        assertThat(DbState.isNotReady(error)).isFalse();
    }

    @Test
    void deadlockMeansNotReady() {
        assertThat(DbState.isNotReady(new SQLException("deadlock detected", "40P01"))).isTrue();
    }

    @Test
    void nullIsNotNotReady() {
        assertThat(DbState.isNotReady(null)).isFalse();
    }

    // Отдельный поток нужен тестам цикла: в обычном режиме JUnit только прерывает поток,
    // а бесконечный цикл на прерывание не отзовётся, и сборка зависнет вместо падения теста.

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void causeCycleWithoutSignsOfNotReadyDoesNotHang() {
        Throwable cycle = cycleOf(new IllegalStateException("A"), new IllegalStateException("B"));

        assertThat(DbState.isNotReady(cycle)).isFalse();
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void causeCycleWithMissingTableIsNotReadyAndDoesNotHang() {
        Throwable cycle = cycleOf(new IllegalStateException("A"), sql("42P01"));

        assertThat(DbState.isNotReady(cycle)).isTrue();
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void causeCycleBelowTopOfChainDoesNotHang() {
        assertThat(DbState.isNotReady(cycleBelowTop())).isFalse();
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void shortReasonOfCauseCycleDoesNotHang() {
        Throwable first = new IllegalStateException("A");
        Throwable second = new IllegalStateException("B");

        // Исходной причины у цикла нет, поэтому годится любое исключение из него.
        assertThat(DbState.shortReason(cycleOf(first, second))).isIn(first.toString(), second.toString());
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void shortReasonOfCauseCycleBelowTopOfChainDoesNotHang() {
        assertThat(DbState.shortReason(cycleBelowTop())).isNotBlank();
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void causeCycleThroughApplicationExceptionDoesNotHang() {
        TransactionSystemException failure = new TransactionSystemException(
                "JDBC rollback failed", new SQLException("Connection is closed"));
        failure.initApplicationException(new IllegalStateException("A", failure));

        assertThat(DbState.isNotReady(failure)).isFalse();
        assertThat(DbState.shortReason(failure)).isEqualTo("java.lang.IllegalStateException: A");
    }

    @Test
    void shortReasonOfFailedRollbackIsRootOfOriginalError() {
        Throwable failure = rollbackFailedAfter(new DataAccessResourceFailureException(
                "I/O error", new PSQLException("An I/O error occurred\nподробности", PSQLState.CONNECTION_FAILURE)));

        assertThat(DbState.shortReason(failure)).isEqualTo("org.postgresql.util.PSQLException: An I/O error occurred");
    }

    @Test
    void shortReasonOfFailedRollbackWithoutOriginalErrorIsItsOwnCause() {
        assertThat(DbState.shortReason(rollbackFailedOnClosedConnection()))
                .isEqualTo("java.sql.SQLException: Connection is closed");
    }

    @Test
    void shortReasonIsFirstLineOfRootCause() {
        SQLException root = new SQLException("ОШИБКА: отношение \"goal\" не существует\n  Позиция: 15", "42P01");

        assertThat(DbState.shortReason(badSql(root)))
                .isEqualTo("java.sql.SQLException: ОШИБКА: отношение \"goal\" не существует");
    }

    @Test
    void shortReasonOfErrorWithoutCauseIsItsOwnToString() {
        assertThat(DbState.shortReason(new IllegalStateException("сбой")))
                .isEqualTo("java.lang.IllegalStateException: сбой");
    }

    @Test
    void shortReasonWithoutMessageIsClassName() {
        assertThat(DbState.shortReason(new IllegalStateException())).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void shortReasonGoesThroughWholeChain() {
        Throwable chain = new CannotCreateTransactionException(
                "Could not open JDBC Connection for transaction",
                new SQLTransientConnectionException(
                        "HikariPool-1 - Connection is not available",
                        new PSQLException("Connection to db:5432 refused\nCheck the host", PSQLState.CONNECTION_UNABLE_TO_CONNECT)));

        assertThat(DbState.shortReason(chain))
                .isEqualTo("org.postgresql.util.PSQLException: Connection to db:5432 refused");
    }

    static Stream<Arguments> tableIsMissing() {
        return Stream.of(
                arguments(named("SQLException на верху цепочки", sql("42P01"))),
                arguments(named("PSQLException на верху цепочки", undefinedTable())),
                arguments(named("BadSqlGrammarException с причиной SQLException", badSql(sql("42P01")))),
                arguments(named("BadSqlGrammarException с причиной PSQLException", badSql(undefinedTable()))),
                arguments(named("глубоко в цепочке", new IllegalStateException(
                        "снаружи", new RuntimeException("середина", badSql(sql("42P01")))))),
                arguments(named("SQLException без кода поверх причины с кодом", new SQLException(
                        "обёртка", sql("42P01")))),
                arguments(named("пакетная запись", badSql(new BatchUpdateException(
                        "Batch entry 0 INSERT INTO kkd_issue", "42P01", new int[0], undefinedTable())))));
    }

    static Stream<Arguments> noConnection() {
        return Stream.of(
                arguments(named("08001", new PSQLException("Connection refused", PSQLState.CONNECTION_UNABLE_TO_CONNECT))),
                arguments(named("08006", new PSQLException("An I/O error occurred", PSQLState.CONNECTION_FAILURE))),
                arguments(named("08003, соединение уже закрыто", new PSQLException(
                        "This connection has been closed", PSQLState.CONNECTION_DOES_NOT_EXIST))),
                arguments(named("08006 в глубине цепочки", new DataAccessResourceFailureException(
                        "I/O error", new PSQLException("An I/O error occurred", PSQLState.CONNECTION_FAILURE)))),
                arguments(named("CannotGetJdbcConnectionException без причины", new CannotGetJdbcConnectionException(
                        "Failed to obtain JDBC Connection"))),
                arguments(named("CannotCreateTransactionException без причины", new CannotCreateTransactionException(
                        "Could not open JDBC Connection for transaction"))),
                arguments(named("CannotGetJdbcConnectionException в глубине цепочки", new IllegalStateException(
                        "снаружи", new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection")))),
                arguments(named("соединение не получено, полная цепочка", new CannotGetJdbcConnectionException(
                        "Failed to obtain JDBC Connection",
                        new SQLTransientConnectionException(
                                "HikariPool-1 - Connection is not available",
                                new PSQLException("Connection refused", PSQLState.CONNECTION_UNABLE_TO_CONNECT))))),
                arguments(named("транзакция не начата, полная цепочка", new CannotCreateTransactionException(
                        "Could not open JDBC Connection for transaction",
                        new SQLTransientConnectionException("HikariPool-1 - Connection is not available")))),
                arguments(named("57P01, сервер закрыл соединение", sql("57P01"))),
                arguments(named("57P01 в глубине цепочки", new DataAccessResourceFailureException(
                        "terminating connection due to administrator command", sql("57P01")))),
                arguments(named("57P02, сервер закрыл соединение из-за сбоя другого процесса", sql("57P02"))),
                arguments(named("57P03, сервер не принимает соединения", sql("57P03"))),
                arguments(named("обрыв при фиксации", new TransactionSystemException(
                        "Could not commit JDBC transaction",
                        new PSQLException("An I/O error occurred", PSQLState.CONNECTION_FAILURE)))),
                arguments(named("обрыв посреди транзакции, откат тоже упал", rollbackFailedAfter(
                        new DataAccessResourceFailureException(
                                "I/O error",
                                new PSQLException("An I/O error occurred", PSQLState.CONNECTION_FAILURE))))),
                arguments(named("57P01 посреди транзакции, откат тоже упал", rollbackFailedAfter(
                        new DataAccessResourceFailureException(
                                "terminating connection due to administrator command", sql("57P01"))))),
                arguments(named("обрыв при фиксации, откат тоже упал, исходной ошибки нет",
                        rollbackFailedOnClosedConnection())));
    }

    static Stream<Arguments> otherErrors() {
        return Stream.of(
                arguments(named("42703 на верху цепочки", sql("42703"))),
                arguments(named("42703 в BadSqlGrammarException", badSql(sql("42703")))),
                arguments(named("42703 в PSQLException", badSql(new PSQLException(
                        "столбец не существует", PSQLState.UNDEFINED_COLUMN)))),
                arguments(named("42601, другой код класса 42", badSql(sql("42601")))),
                arguments(named("42P02, код рядом с 42P01", badSql(sql("42P02")))),
                arguments(named("42P07, таблица уже есть", badSql(sql("42P07")))),
                arguments(named("57014, запрос отменён, связь есть", sql("57014"))),
                arguments(named("23505 в DuplicateKeyException", new DuplicateKeyException(
                        "дубль", sql("23505")))),
                arguments(named("23505 при фиксации", new TransactionSystemException(
                        "Could not commit JDBC transaction", sql("23505")))),
                arguments(named("сбой фиксации без причины", new TransactionSystemException(
                        "Could not commit JDBC transaction"))),
                arguments(named("сбой фиксации, причина не SQLException", new TransactionSystemException(
                        "Could not commit JDBC transaction", new IllegalStateException("сбой")))),
                arguments(named("ошибка логики, откат тоже упал", rollbackFailedAfter(
                        new IllegalStateException("сбой")))),
                arguments(named("0A000, откат упал: Hikari закрыл соединение, но решает исходная ошибка",
                        rollbackFailedAfter(sql("0A000")))),
                arguments(named("обычное IllegalStateException", new IllegalStateException("сбой"))),
                arguments(named("IllegalStateException с обычной причиной", new IllegalStateException(
                        "сбой", new IllegalArgumentException("причина")))),
                arguments(named("SQLException без SQLState", new SQLException("сбой"))),
                arguments(named("SQLException с пустым SQLState", new SQLException("сбой", ""))),
                arguments(named("BadSqlGrammarException без SQLState", badSql(new SQLException("сбой")))));
    }

    private static SQLException sql(String sqlState) {
        return new SQLException("ошибка базы", sqlState);
    }

    private static SQLException undefinedTable() {
        return new PSQLException("ОШИБКА: отношение не существует", PSQLState.UNDEFINED_TABLE);
    }

    private static BadSqlGrammarException badSql(SQLException cause) {
        return new BadSqlGrammarException("выборка", "SELECT * FROM goal_snapshot", cause);
    }

    /** Цепочка first -> second -> first. Самого себя причиной сделать нельзя, а через второе исключение можно. */
    private static Throwable cycleOf(Throwable first, Throwable second) {
        second.initCause(first);
        first.initCause(second);
        return first;
    }

    /** Цепочка A -> B -> C -> B: верх цепочки в цикл не входит, поэтому сравнения с ним не хватит. */
    private static Throwable cycleBelowTop() {
        Throwable second = new IllegalStateException("B");
        Throwable third = new IllegalStateException("C");
        second.initCause(third);
        third.initCause(second);
        return new IllegalStateException("A", second);
    }

    /** Откат упал вслед за исходной ошибкой. Так поступает TransactionTemplate: исходная ошибка не становится причиной. */
    private static TransactionSystemException rollbackFailedAfter(Throwable original) {
        TransactionSystemException failure = rollbackFailedOnClosedConnection();
        failure.initApplicationException(original);
        return failure;
    }

    /** Такой вид получен запуском: после обрыва Hikari закрывает соединение и отвечает SQLException без SQLState. */
    private static TransactionSystemException rollbackFailedOnClosedConnection() {
        return new TransactionSystemException("JDBC rollback failed", new SQLException("Connection is closed"));
    }
}
