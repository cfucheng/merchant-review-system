package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询商铺分类并按排序字段升序排序 String类型
     * @return
     */
    public Result queryOrderByAsc() {
        // 1.在redis中查询商铺信息
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        String cacheType = stringRedisTemplate.opsForValue().get(key);
        // 2.存在 直接返回
        if (StrUtil.isNotBlank(cacheType)){
            List<ShopType> shopTypes = JSONUtil.toList(cacheType, ShopType.class);
            return Result.ok(shopTypes);
        }
        //4.不存在，从数据库中查询写入redis
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        // 4.数据库不存在，返回错误
        if (shopTypes == null) {
            return Result.fail("暂无店铺类型数据！");
        }
        // 5.数据库存在，写入redis并返回
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypes));
        // 6.返回
        return Result.ok(shopTypes);
    }

    /**
     * 查询商铺分类并按排序字段升序排序 List类型
     * @return
     */
/*    public Result queryByList() {
        //1.在redis中查询  LRANGE CACHE_SHOP_TYPE_KEY 0(开头) -1(结尾)
        List<String> shopTypeList = stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOP_TYPE_KEY, 0, -1);
        //2.判断redis中是否存在
        // * 从redis中拿取的是JSON格式，需要逐一转换为ShopType格式进行返回才可生效！！！
        if (shopTypeList != null && !shopTypeList.isEmpty()) {
            //3.redis中存在，直接返回
            List<ShopType> typeList = new ArrayList<>();
            for (String s : shopTypeList) {
                ShopType shopType = JSONUtil.toBean(s, ShopType.class);
                typeList.add(shopType);
            }
            return Result.ok(typeList);
        }
        //4.redis中不存在，根据id查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //5.数据库中不存在，直接报错
        if (typeList.isEmpty()) {
            return Result.fail("暂无店铺类型数据！");
        }
        //6.数据库中存在，先把数据写入redis
        // * 从数据库中拿取的是ShopType格式，需要逐一转换为JSON格式才可保存到redis中！！！
        List<String> list = new ArrayList<>();
        for (ShopType shopType : typeList) {
            String s = JSONUtil.toJsonStr(shopType);
            list.add(s);
        }
        stringRedisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOP_TYPE_KEY, list);
        //7.返回
        return Result.ok(typeList);
    }*/



}
