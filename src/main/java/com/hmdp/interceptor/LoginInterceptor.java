package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.config.TokenProperties;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 *@Author: chengfu Chen
 *@CreateTime: 2026-09-09  15:53
 *@Description: TODO
 *@Version: 1.0
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private TokenProperties tokenProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.从请求头中获取token
        String token = request.getHeader(tokenProperties.getHeaderName());
        if (StrUtil.isBlank(token)) {//判断是否为空
            // 不存在 拦截 返回401
            response.setStatus(401);
            return false;
        }
        // 2.基于token获取用户信息
        String key = tokenProperties.getKeyPrefix() + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        // 3.判断用户是否存在
        if (userMap.isEmpty()){
            // 不存在 拦截 返回401
            response.setStatus(401);
            return false;
        }

        // 4.将查询到的Hash数据转换为UserDTO对象
        UserDTO userDTO = BeanUtil.toBean(userMap, UserDTO.class);

        // 5.存在 保存信息到ThreadLocal
        UserHolder.saveUser(userDTO);

        // 6.刷新有效期
        stringRedisTemplate.expire(key, tokenProperties.getTtlMinutes(), TimeUnit.MINUTES);

        // 7. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
