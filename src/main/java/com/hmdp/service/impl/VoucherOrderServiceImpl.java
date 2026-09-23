package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.LOCK_ORDER_KEY;
import static java.lang.Thread.sleep;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisWorker redisWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    private IVoucherOrderService proxy;
    
    private volatile boolean running = true;

    /**
     * 加载Lua脚本：执行脚本保证原子性操作
     */
    private static final DefaultRedisScript<Long> SEKILL_SCRIPT;

    /**
     * 静态代码块，类加载时执行，初始化Lua脚本
     */
    static {
        SEKILL_SCRIPT = new DefaultRedisScript<>();
        SEKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SEKILL_SCRIPT.setResultType(Long.class);
    }


    /**
     * 创建单线程线程池
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 在VoucherServiceImpl类初始化时执行线程池
     * Spring启动后，开启后台订单处理线程
     */
    @PostConstruct
    public void init() {
        // 1. 尝试创建消费者组（如果队列不存在，MKSTREAM会自动创建队列）
        try {
            stringRedisTemplate.opsForStream().createGroup("stream.orders", "g1");
        } catch (Exception e) {
            log.info("消费者组已存在，无需创建");
        }
        // 2. 启动后台线程池
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    
    @PreDestroy
    public void destroy() {
        log.info("开始关闭订单处理线程...");
        running = false;
        SECKILL_ORDER_EXECUTOR.shutdown();
        try {
            if (!SECKILL_ORDER_EXECUTOR.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                SECKILL_ORDER_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            SECKILL_ORDER_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("订单处理线程已关闭");
    }

    String queueName = "stream.orders";
    public class VoucherOrderHandler implements Runnable {


        // 不断从消息队列取订单
        @Override
        public void run() {
            while (running) {
                try {
                    // 1.获取消息队列中的订单消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 2.1不成功 继续获取
                        continue;
                    }
                    // 2.2成功 可以下单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 2.3创建订单
                    handleVoucherOrder(voucherOrder);
                    // 3.ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    if (!running) {
                        log.info("应用正在关闭，退出订单处理循环");
                        break;
                    }
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while (running) {
            try {
                // 1.获取消息队列中的订单消息
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                // 2.判断消息是否获取成功
                if (list == null || list.isEmpty()) {
                    // 2.1不成功 继续获取
                    break;
                }
                // 2.2成功 可以下单
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                handleVoucherOrder(voucherOrder);
                // 3.ACK确认
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
            } catch (Exception e) {
                if (!running) {
                    log.info("应用正在关闭，退出Pending-List处理循环");
                    break;
                }
                log.error("处理Ping-List异常", e);
                try {
                    sleep(20);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    log.warn("处理Pending-List线程被中断");
                    break;
                }
            }
        }

    }




    /*    *//**
     * 创建阻塞队列：异步下单，提高效率
     *//*
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    *//**
     * 线程池异步执行优惠券订单任务
     */

    /*
    public class VoucherOrderHandler implements Runnable {
        // 不断从阻塞队列取订单
        @Override
        public void run() {
            while (true) {
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 异步创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/


    /**
     * 创建订单
     *
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户id，这里是多线程不能在ThreadLocal中直接获得当前用户id
        Long userId = voucherOrder.getUserId();
        //2.创建锁对象（锁键设计："lock:order:" + userId 以用户ID为粒度，不同用户请求可并行）
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
        //3.获取锁(无参表示只尝试获取锁一次)
        boolean isLock = lock.tryLock();
        //4.判断获取锁是否成功
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单");
            return;
        }
        try {
            //获取锁成功，创建订单
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SEKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            // 2.1否 没有购买资格 返回错误信息
            return Result.fail(r == 1 ? "库存不足" : "请勿重复下单");
        }
        //3.获取代理对象，使用代理对象调用第三方事务方法，防止事务失效
        proxy = (IVoucherOrderService) AopContext.currentProxy();   //获取当前类的代理对象
        //4.返回订单id
        return Result.ok(orderId);
    }


    /**
     * 抢购秒杀优惠券
     *
     * @param voucherId
     * @return
     */
/*    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SEKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        // 2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            // 2.1否 没有购买资格 返回错误信息
            return Result.fail(r == 1 ? "库存不足" : "请勿重复下单");
        }

        // 2.2是 有购买资格  把下单信息保存到阻塞队列
        //2.2 result为0，用户具有秒杀资格，将订单信息(订单id,优惠券id,用户id)保存到阻塞队列中，实现异步下单
        //3.创建订单（在订单表tb_voucher_order插入一条数据）
        VoucherOrder voucherOrder = new VoucherOrder();
        //3.1 订单id
        long orderId = redisWorker.nextId("order");
        voucherOrder.setId(orderId);
        //3.2 用户id
        voucherOrder.setUserId(userId);
        //3.3 代金券id
        voucherOrder.setVoucherId(voucherId);
        // 3.4放入阻塞队列
        orderTasks.add(voucherOrder);
        //4.获取代理对象，使用代理对象调用第三方事务方法，防止事务失效
        proxy = (IVoucherOrderService) AopContext.currentProxy();   //获取当前类的代理对象
        //5.返回订单id
        return Result.ok(orderId);
    }*/


    /*    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 1.1 校验优惠券是否存在
        if (voucher == null) {
            return Result.fail("优惠券不存在！");
        }
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀活动未开始！");
        }
        // 3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀活动已结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() <= 0) {
            // 4.2不充足 返回错误信息
            return Result.fail("库存不足！");
        }

        // 5.获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
*/

    /*        集群下会失效
        // 6.对同一用户加锁，保证一人一单的线程安全
        //   userId.toString().intern() 保证同一用户获取到同一把锁，不同用户之间不互斥
        synchronized (userId.toString().intern()) {
            // 7.获取AOP代理对象，使createVoucherOrder方法上的@Transactional事务注解生效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 8.通过代理对象调用，确保事务正常生效，创建优惠券订单
            return proxy.createVoucherOrder(voucherId);
        }*/

    /*

        //使用redis分布式锁
        // 1.获取锁

        //SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);

        // 通过Redisson获取以用户ID为标识的分布式锁，防止同一用户并发下单
        RLock lock = redissonClient.getLock("order:" + userId);
        boolean isLock = lock.tryLock();
        // 2.判断是否获取锁成功
        if (!isLock) {
            // 2.1获取锁失败 返回错误信息或重试
            return Result.fail("请勿重复下单");
        }
        // 2.2获取锁成功
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 3.释放锁
            lock.unlock();
        }
    }*/

    /**
     * 通过数据库查询确保“一人一单”
     *
     * @param voucherOrder
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 1.一人一单
        Long userId = voucherOrder.getUserId();
        // 1.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 1.2判断订单
        if (count > 0) {
            log.info("用户已购买过该优惠券");
            return;
        }
        // 2.充足 扣减库存 乐观锁
        boolean success = seckillVoucherService.
                update().setSql("stock = stock - 1").
                eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).
                update();
        if (!success) {
            log.info("库存不足！");
            return;
        }
        save(voucherOrder);
    }
}
