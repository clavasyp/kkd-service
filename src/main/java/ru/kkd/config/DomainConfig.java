package ru.kkd.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.kkd.domain.delta.BaseRunSelector;
import ru.kkd.domain.rules.RuleEngine;

/** Домен не знает о Spring, поэтому его объекты собираются здесь. */
@Configuration
public class DomainConfig {

    @Bean
    public RuleEngine ruleEngine() {
        return new RuleEngine();
    }

    @Bean
    public BaseRunSelector baseRunSelector() {
        return new BaseRunSelector();
    }
}
