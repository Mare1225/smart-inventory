package ec.usfq.smartinventory.web;

import ec.usfq.smartinventory.config.AppConfig;
import ec.usfq.smartinventory.config.SecurityProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Configuracion comun de las pruebas @WebMvcTest. El slice web no ejecuta @ConfigurationPropertiesScan ni crea un
 * MeterRegistry: se registran aqui las propiedades y metricas que usan los filtros.
 */
@TestConfiguration
@EnableConfigurationProperties({SecurityProperties.class, AppConfig.RateLimitProperties.class})
public class WebSliceTestConfig {

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
