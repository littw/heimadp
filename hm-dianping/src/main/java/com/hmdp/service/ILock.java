package com.hmdp.service;

public interface ILock {
    /**
     * 尝试获取锁
     * @param timeOutSec 锁持有的超时时间，过期自动释放
     * @return
     */
    boolean tryLock(long timeOutSec);

    /**
     * 释放锁
     */
    void unlock();
}
