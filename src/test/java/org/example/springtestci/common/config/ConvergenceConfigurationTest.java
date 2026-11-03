package org.example.springtestci.common.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.example.db.ConvergenceTool;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ConvergenceConfigurationTest {
  @Test
  void runtimeAndRunnerUseTheSameStrictLocations() {
    for (String lineage : new String[] {"dated", "legacy"}) {
      String profile = lineage.equals("legacy") ? "db-legacy-convergence" : "db-convergence";
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withPropertyValues(
              "spring.profiles.active=prod,supabase," + profile,
              "DOTENV_PATH=build/disabled-env/.env")
          .run(
              context -> {
                Binder binder = Binder.get(context.getEnvironment());
                assertEquals(
                    Arrays.asList(ConvergenceTool.locations(lineage)),
                    binder.bind("spring.flyway.locations", Bindable.listOf(String.class)).get());
                assertTrue(
                    binder
                        .bind(
                            "spring.flyway.ignore-migration-patterns",
                            Bindable.listOf(String.class))
                        .get()
                        .isEmpty());
                assertFalse(binder.bind("spring.flyway.out-of-order", Boolean.class).get());
                assertFalse(
                    binder
                        .bind("spring.flyway.postgresql.transactional-lock", Boolean.class)
                        .get());
                assertEquals(
                    "public", binder.bind("spring.flyway.default-schema", String.class).get());
                assertEquals(
                    "public",
                    context
                        .getEnvironment()
                        .getProperty("spring.jpa.properties.hibernate.default_schema"));
                assertEquals(
                    "SET search_path TO public",
                    context
                        .getEnvironment()
                        .getProperty("spring.datasource.hikari.connection-init-sql"));
              });
    }
  }
}
