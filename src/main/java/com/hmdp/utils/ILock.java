package com.hmdp.utils;


/**
 * @全限定符: com.hmdp.utils.ILock
 * @Description:
 */

public interface ILock {

    /**
     * 尝试获取锁
     * @param TimeoutSec 锁持有的超时时间，过期自动释放
     * @return true代表获取锁成功，false代表获取锁失败
     */
    Boolean tryLock(Long TimeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
