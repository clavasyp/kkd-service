package ru.kkd.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class TransactionConfig {

    /**
     * Транзакции RunProcessor. Заморозка это два запроса, и оба должны видеть одно состояние базы: раздел 6.2 design.md.
     * При READ COMMITTED у каждого свой снимок, и чужая правка между ними даёт снимок, которого не было.
     */
    @Bean
    public TransactionOperations transactionOperations(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template;
    }
}
