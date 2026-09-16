package com.hmdp.utils;


public interface ILock {
    /**
     * 尝试获取锁
     * @param timeout 锁的超时时间
     * @return 是否获取成功
     */
    boolean tryLock(long timeout);

    /**
     * 释放锁
     */
    void unlock();


}
