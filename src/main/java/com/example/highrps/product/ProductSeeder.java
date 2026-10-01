package com.example.highrps.product;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import com.example.highrps.config.AppProperties;

/** Local/demo data so load tests have something to read. Disabled in AWS. */
@Component
@ConditionalOnBooleanProperty("app.seed.enabled")
class ProductSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductSeeder.class);

    private final ProductRepository repository;
    private final int count;

    ProductSeeder(ProductRepository repository, AppProperties props) {
        this.repository = repository;
        this.count = props.seed().products();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!repository.isEmpty()) {
            return;
        }
        List<Product> products = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            products.add(new Product("p-" + i, "Product " + i, "Demo product #" + i, 100L * i, "USD", 1));
        }
        repository.saveAll(products);
        log.info("Seeded {} products (ids p-1 .. p-{})", count, count);
    }
}
