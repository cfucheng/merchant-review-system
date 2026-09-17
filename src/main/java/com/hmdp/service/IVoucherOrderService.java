package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 秒杀优惠券
     * @param voucherId
     * @return
     */
    Result seckillVoucher(Long voucherId);


    /**
     * 创建优惠券订单（带事务）
     * 包含一人一单校验、扣减库存、创建订单
     * 注意：必须通过AOP代理对象调用，否则@Transactional注解不生效
     *
     * @param voucherOrder 优惠券id
     */
    void createVoucherOrder(VoucherOrder voucherOrder);
}
