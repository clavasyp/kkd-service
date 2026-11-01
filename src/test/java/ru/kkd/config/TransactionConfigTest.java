package ru.kkd.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

class TransactionConfigTest {

    // Автонастройка подключена: RunProcessor получает бин по типу, и нужно видеть, что победил наш.
    @Test
    void transactionsOfRunUseRepeatableRead() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TransactionAutoConfiguration.class))
                .withUserConfiguration(TransactionConfig.class)
                .withBean(PlatformTransactionManager.class, () -> manager)
                .run(context -> context.getBean(TransactionOperations.class).executeWithoutResult(status -> {
                }));

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.captor();
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
}
