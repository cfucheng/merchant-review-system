package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

    /**
     * 发送手机验证码
     */
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号码
        if (RegexUtils.isPhoneInvalid(phone)){
            // 2.不符合返回错误信息
            return Result.fail("手机号码格式不正确");
        }
        // 3.符合生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到session
        session.setAttribute("code", code);
        // 5.发送验证码
        log.info("发送验证码成功! 验证码: {}",code);
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    public Result Login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号码格式不正确");
        }
        // 2.校验验证码
        String code = loginForm.getCode();
        Object cacheCode = session.getAttribute("code");
        // 3.不一致 返回报错
        if (code == null || !code.equals(cacheCode)){
            return Result.fail("验证码错误");
        }
        // 4.一致 查表是否为新用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();
        // 5.是 创建账号 存入session
        if (user == null){
            createUserWithPhone(phone);
        }
        // 6.不是 存入session 返回成功
        UserDTO userDTO = new UserDTO();
        // 保护用户敏感信息
        BeanUtils.copyProperties(user, userDTO);
        session.setAttribute("user", userDTO);
        return Result.ok();

    }

    private void createUserWithPhone(String phone) {
        // 创建用户
        User user = new User();
        // 存入手机号
        user.setPhone(phone);
        // 设置昵称
        user.setNickName("user_" + RandomUtil.randomString(8));
        // 保存用户
        save(user);
    }
}
