package ru.kkd.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Какую транзакцию открывает прокси Spring вокруг метода сервиса. Видно действие аннотации, а не только её наличие:
 * метод, до которого прокси не дотягивается, транзакции не получит, и тест упадёт.
 */
final class TransactionProbe {

    private TransactionProbe() {
    }

    static <T> TransactionDefinition definitionOfCall(Class<T> type, T target, Consumer<T> call) {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TransactionAutoConfiguration.class))
                .withBean(PlatformTransactionManager.class, () -> manager)
                .withBean(type, () -> target)
                .run(context -> call.accept(context.getBean(type)));

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.captor();
        verify(manager).getTransaction(definition.capture());
        verify(manager).commit(any());
        return definition.getValue();
    }
}
