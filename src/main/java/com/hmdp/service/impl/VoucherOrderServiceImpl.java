package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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


    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
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
        // 4.1充足 扣减库存
        boolean success = seckillVoucherService.
                update().setSql("stock = stock - 1").eq("voucher_id", voucherId).update();
        if (!success){
            return Result.fail("库存不足！");
        }
        // 5.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 5.1订单id
        Long orderID = redisWorker.nextId("order");
        voucherOrder.setId(orderID);
        // 5.2用户id
        Long userID = UserHolder.getUser().getId();
        voucherOrder.setUserId(userID);
        // 5.3代金卷id
        voucherOrder.setVoucherId(voucherId);
        // 5.4保存数据库
        save(voucherOrder);
        // 6.返回订单id

        return Result.ok(voucherId);
    }
}
