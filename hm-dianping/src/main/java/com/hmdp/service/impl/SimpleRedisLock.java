package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import com.hmdp.service.ILock;
import net.sf.jsqlparser.statement.select.KSQLWindow;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX="lock:";
    private static final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";  //用于区分不同jvm下的线程

    public SimpleRedisLock() {
    }
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //添加互斥锁
    @Override
    public boolean tryLock(long timeOutSec) {
        //集群模式下，线程的id可能会出现重复，因为线程创建的时候，线程的id是递增的
        //同一个jvm中，线程id不会出现重复，但是不同的jvm中坑会出现线程号的重复
        //所以需要添加uuid以区别不同的jvm产生的线程
        String threadId =ID_PREFIX+Thread.currentThread().getId();  //线程id
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeOutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    //释放锁
    @Override
    public void unlock() {
        //释放锁时需要判断是不是当前进程自己的锁
        String threadId =ID_PREFIX+Thread.currentThread().getId();
        //获取redis中锁的标识，也就是存储在redis中的进程id
        String threadObtain = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //如果当前进程的标识和redis中存储一致，则释放锁
        if (threadId.equals(threadObtain)){
            stringRedisTemplate.delete(KEY_PREFIX+name);
        }
    }

}
