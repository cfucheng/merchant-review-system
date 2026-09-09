package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    /**
     * 保存用户信息
     * @param user
     */
    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    /**
     * 获取用户信息
     * @return
     */
    public static UserDTO getUser(){
        return tl.get();
    }

    /**
     * 移除用户信息
     */
    public static void removeUser(){
        tl.remove();
    }
}
