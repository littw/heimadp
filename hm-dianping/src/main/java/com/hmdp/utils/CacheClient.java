package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;

@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //写入缓存
    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        this.stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,timeUnit);
    }
    //设置逻辑过期时间
    public void setLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit){
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        redisData.setData(value);
        this.stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }
    //缓存穿透
    public <R,ID>R queryWithPassThrough(String keyPrefix, Long id, Class<R> type, Function<ID,R> dbCallBack,Long time,TimeUnit timeUnit){
        String key= keyPrefix+id;
        //先从redis当中查询店铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //如果店铺信息非空，就返回
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, type);
        }
        //判断命中的是否是空值：空值无法确定是否存在，在查询数据库之前进行空值的判断
        if (shopJson!=null){
            return null;
        }
        R r=dbCallBack.apply((ID) id);

        if (r==null){
            //缓存穿透：将null值存储在redis中并设置ttl
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key,JSONUtil.toJsonStr(r),time, timeUnit);
        return r;
    }

    //缓存击穿：互斥锁
    public <R,ID>R queryWithLogicalExpire(String keyPrefix, Long id, Class<R> type, Function<ID,R> dbCallBack,Long time,TimeUnit timeUnit){
        //1.获取店铺在redis中的key
        String key= keyPrefix+id;
        //2.从redis当中查询店铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)){
            // 3.不存在，直接返回
            return null;
        }
        //4.命中，需要现将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);//RedisData中存储的数据是Object类型，可以考虑使用泛型，但是好像还是取不出数据
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，直接返回
            return r;
        }
        //5.2过期，缓存重建
        //6.缓存重建
        //6.1获得互斥锁
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //6.2互斥锁获取成功,开启独立线程，重建缓存
        if (isLock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R newR = dbCallBack.apply((ID) id);
                    this.setLogicalExpire(key,newR,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //解锁
                    unLock(lockKey);
                }
            });
        }
        //6.3返回商铺的过期信息
        return r;
    }

    //缓存击穿：逻辑过期时间
    public <R,ID>R queryWithMutex(String keyPrefix, Long id, Class<R> type, Function<ID,R> dbCallBack,Long time,TimeUnit timeUnit){
        //1.获取店铺在redis中的key
        String key=keyPrefix+id;
        //2.从redis当中查询店铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)){
            //2.1.如果redis中存在店铺信息，就返回
            return JSONUtil.toBean(shopJson, type);
        }
        //3.判断命中的是否是空值：空值无法确定是否存在，在查询数据库之前进行空值的判断
        if (shopJson!=null){
            //redis中存储空值，返回空值，缓解缓存穿透问题
            return null;
        }
        //4.防止缓存雪崩
        //4.1.获取锁
        String lock=RedisConstants.LOCK_SHOP_KEY+id; //每个店铺都会设置一个锁
        R r=null;
        try {
            boolean isLock = tryLock(lock);
            //4.2如果当前进程没有获取到互斥锁，就进行休眠
            if (!isLock){
                Thread.sleep(50);
                return queryWithMutex(keyPrefix,id,type,dbCallBack,time,timeUnit);
            }
            //4.2.进行数据库的查询
            r= dbCallBack.apply((ID) id);
            //模拟重建延时
            Thread.sleep(200);
            //4.3.redis的写入
            if (r==null){
                //缓存穿透：将null值存储在redis中并设置ttl
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            this.set(key,r,time, timeUnit);
            //4.4.释放锁
            unLock(lock);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //4.5返回数据
            return r;
        }
    }

    /**
     * 添加互斥锁
     * @param key
     * @return
     */
    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     * @param key
     */
    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }

}
