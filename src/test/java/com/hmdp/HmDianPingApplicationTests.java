package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
@Slf4j
class HmDianPingApplicationTests {

    @Autowired
    private ShopServiceImpl shopService;
    @Autowired
    private RedisWorker redisWorker;

    @Test
    void contextLoads() {
        shopService.saveShop2Redis(1L, 10L);

    }

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testRedisWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                Long id = redisWorker.nextId("order");
                log.info("id:{}", id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        log.info("time: " + (end - begin));
    }


}
