package ru.kkd;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.multipart.MultipartResolver;
import ru.kkd.api.KkdController;
import ru.kkd.service.RunPoller;

/**
 * Полный контекст сервиса без HTTP и без базы: Hikari подключается при первом запросе, а такт обработчика подменён.
 * WebMvcTest подменяет сервисы, поэтому забытый бин или аннотация видны только здесь.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ApplicationContextTest {

    @MockitoBean
    private RunPoller poller;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private KkdController controller;

    @Autowired
    private HikariDataSource dataSource;

    @Test
    void contextStartsWithAllBeans() {
        assertThat(controller).isNotNull();
    }

    @Test
    void connectionTimeoutIsFromSection85() {
        assertThat(dataSource.getConnectionTimeout()).isEqualTo(3000);
    }

    @Test
    void multipartParsingIsOff() {
        assertThat(context.getBeanProvider(MultipartResolver.class).getIfAvailable()).isNull();
    }
}
