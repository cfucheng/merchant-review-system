-- 1.参数列表
-- 1.1优惠卷id
local voucherId = ARGV[1]
-- 1.2用户id
local userId = ARGV[2]
-- 1.3订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1库存key
local stockKey = "seckill:stock:" .. voucherId
-- 2.2订单key
local orderKey = "seckill:order:" .. voucherId
-- 3.执行业务
-- 3.1判断库存是否充足
if (tonumber(redis.call('get',stockKey)) <= 0) then
    -- 3.2不充足 返回1
    return 1
end
-- 3.3充足 判单用户是否下单 SISMEMBER orderKey userId
if (redis.call('sismember',orderKey,userId)) then
  -- 3.4用户已下单 返回2
  return 2
end
-- 3.5用户未下单 扣减库存
redis.call('incrby',stockKey,-1)
-- 4.保存用户订单
redis.call('sadd',orderKey,userId)
-- 4.1发送消息到消息队列
redis.call("xadd",'stream.orders','*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

-- 5.返回结果
return 0


