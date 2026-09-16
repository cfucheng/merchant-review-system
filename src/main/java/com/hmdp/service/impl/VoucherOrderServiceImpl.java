package com.hmdp.service.impl;

import com.hmdp.config.RedissonConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisWorker redisWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;


    @Override
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
/*        集群下会失效
        // 6.对同一用户加锁，保证一人一单的线程安全
        //   userId.toString().intern() 保证同一用户获取到同一把锁，不同用户之间不互斥
        synchronized (userId.toString().intern()) {
            // 7.获取AOP代理对象，使createVoucherOrder方法上的@Transactional事务注解生效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 8.通过代理对象调用，确保事务正常生效，创建优惠券订单
            return proxy.createVoucherOrder(voucherId);
        }*/

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
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 1.一人一单
        Long userId = UserHolder.getUser().getId();
        // 1.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 1.2判断订单
        if (count > 0) {
            return Result.fail("用户已购买过该优惠券");
        }
        // 2.充足 扣减库存 乐观锁
        boolean success = seckillVoucherService.
                update().setSql("stock = stock - 1").
                eq("voucher_id", voucherId).gt("stock", 0).
                update();
        if (!success) {
            return Result.fail("库存不足！");
        }
        // 3.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 3.1订单id
        Long orderID = redisWorker.nextId("order");
        voucherOrder.setId(orderID);
        // 3.2用户id
        voucherOrder.setUserId(userId);
        // 3.3代金卷id
        voucherOrder.setVoucherId(voucherId);
        // 3.4保存数据库
        save(voucherOrder);
        // 4.返回订单id
        return Result.ok(orderID);
    }
}
