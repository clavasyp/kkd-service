package ru.kkd.service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/** Признак неготовой базы: таблицы нет или нет связи. Раздел 6.3 design.md. */
public final class DbState {

    private static final String UNDEFINED_TABLE = "42P01";
    private static final String CONNECTION_EXCEPTION_CLASS = "08";
    private static final Set<String> SERVER_STOPPING = Set.of("57P01", "57P02", "57P03");

    private DbState() {
    }

    /** Смотрит всю цепочку причин: SQLState лежит в исключении драйвера, обёрнутом в исключения Spring. */
    public static boolean isNotReady(Throwable error) {
        return causeChain(error).stream().anyMatch(DbState::signalsNotReady);
    }

    /** Для журнала: первая строка исходной причины. Дальше у ошибок PostgreSQL идёт позиция в запросе. */
    public static String shortReason(Throwable error) {
        List<Throwable> chain = causeChain(error);
        return chain.get(chain.size() - 1).toString().lines().findFirst().orElse("");
    }

    private static boolean signalsNotReady(Throwable error) {
        if (error instanceof CannotGetJdbcConnectionException
                || error instanceof CannotCreateTransactionException
                || isRollbackFailureAfterLostCommit(error)) {
            return true;
        }
        // Тип BadSqlGrammarException не подходит: Spring выдаёт его на весь класс 42, в том числе на опечатку в колонке.
        return error instanceof SQLException sqlError && signalsNotReady(sqlError.getSQLState());
    }

    private static boolean signalsNotReady(String sqlState) {
        return sqlState != null
                && (sqlState.equals(UNDEFINED_TABLE)
                        || sqlState.startsWith(CONNECTION_EXCEPTION_CLASS)
                        || SERVER_STOPPING.contains(sqlState));
    }

    // Обрыв на фиксации: Spring пробует откатить транзакцию, а Hikari уже закрыл соединение и отвечает
    // SQLException без кода. Наверх идёт только это исключение, ошибка фиксации в нём не сохраняется.
    private static boolean isRollbackFailureAfterLostCommit(Throwable error) {
        return error instanceof TransactionSystemException failure
                && failure.getApplicationException() == null
                && failure.getCause() instanceof SQLException cause
                && cause.getSQLState() == null;
    }

    // Защита от цикла в причинах: разбор ошибки не должен подвесить поток обработчика.
    private static List<Throwable> causeChain(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        addWithCauses(error, chain, Collections.newSetFromMap(new IdentityHashMap<>()));
        return chain;
    }

    private static void addWithCauses(Throwable error, List<Throwable> chain, Set<Throwable> seen) {
        if (error == null || !seen.add(error)) {
            return;
        }
        chain.add(error);
        addWithCauses(error.getCause(), chain, seen);
        // Сбой посреди транзакции, и упал ещё и откат: наверх идёт исключение отката, а исходная ошибка лежит
        // не в причинах. Решает она, поэтому идёт в цепочку, причём последней: shortReason берёт её корень.
        if (error instanceof TransactionSystemException failure) {
            addWithCauses(failure.getApplicationException(), chain, seen);
        }
    }
}
