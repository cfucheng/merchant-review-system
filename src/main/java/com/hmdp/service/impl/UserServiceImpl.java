package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.TokenProperties;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private TokenProperties tokenProperties;

    /**
     * 发送手机验证码
     */
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号码
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.不符合返回错误信息
            return Result.fail("手机号码格式不正确");
        }
        // 3.符合生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到redis 设置有效期:2分钟
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5.发送验证码
        log.info("发送验证码成功! 验证码: {}", code);
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    public Result Login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号码格式不正确");
        }
        // 2.校验验证码 从redis中获取缓存的验证码进行比对
        String code = loginForm.getCode();
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        // 3.不一致 返回报错
        if (code == null || !code.equals(cacheCode)) {
            return Result.fail("验证码错误");
        }
        // 4.一致 查表是否为新用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();
        if (user == null) {
            // 5.是 创建账号 存入session
            user = createUserWithPhone(phone);
        }

        // 6.保存新用户到redis中
        // 6.1随机生成token,作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 6.2将User转换为Hash类型存储
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        // 6.3存储到redis中
        String tokenKey = tokenProperties.getKeyPrefix() + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 6.4设置redis缓存有效期
        stringRedisTemplate.expire(tokenKey, tokenProperties.getTtlMinutes(), TimeUnit.MINUTES);
        // 7.返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        // 创建用户
        User user = new User();
        // 存入手机号
        user.setPhone(phone);
        // 设置昵称
        user.setNickName("user_" + RandomUtil.randomString(8));
        // 保存用户
        save(user);
        // 返回用户
        return user;
    }
}
