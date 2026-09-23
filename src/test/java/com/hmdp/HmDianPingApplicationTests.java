package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
@Slf4j
class HmDianPingApplicationTests {

    @Autowired
    private ShopServiceImpl shopService;
    @Autowired
    private RedisWorker redisWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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

    @Test
    void loadShopData() {
        // 1.查询店铺信息
        List<Shop> shopList = shopService.list();
        // 2.将店铺按typeId类型分组 分为list集合
        Map<Long, List<Shop>> shopMap = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批写入redis中
        for (Map.Entry<Long, List<Shop>> entry : shopMap.entrySet()) {
            // 3.1获取到店铺类型id
            Long typeId = entry.getKey();
            // 3.2按照店铺类型进行分组
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> geoLocations = new ArrayList<>(value.size());
            String key = SHOP_GEO_KEY + typeId;
            for (Shop shop : value) {
                geoLocations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            // 4.写入redis
            stringRedisTemplate.opsForGeo().add(key, geoLocations);
        }


    }


}
