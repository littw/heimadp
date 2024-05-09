package com.hmdp.utils;

import org.apache.catalina.util.Introspection;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Stack;

@Component
public class RedisIdWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate ;
    private static final long BEGIN_TIME_STAMP=1704067200L;  //开始的时间戳
    private static final int COUNT_BITS=32;
    //生成全局唯一id:时间戳+当前日期
    public Long nextId(String keyPrefix){
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long second = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = second - BEGIN_TIME_STAMP;

        //生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd")); //2024:01:01
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //拼接返回
        return timeStamp<<COUNT_BITS|count;
    }

    public static void main(String[] args) {
        LocalDateTime time=LocalDateTime.of(2024,1,1,0,0,0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }
}
