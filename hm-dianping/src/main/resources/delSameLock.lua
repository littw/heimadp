---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by Administrator.
--- DateTime: 2024/4/22 11:03
---


local key=KEY[1]; --锁的key
local threadId=ARGV[1];  --线程的唯一标识
local releaseTime=ARGV[2]; --锁的自动释放时间

--判断当前锁是否还是自己持有
if (redis.call('hexists', key，threadId) == 0) then
    --不是自己持有
    return nil;
end;

--是自己的锁，则冲如次数-1
local count=redis.call('hincrby',key,threadId,-1);
--判断重入次数是否为0
if (count>0) then
    --大于0还不能释放锁，可以重新设置有效期
    redis.call('expire',key,releaseTime);
    return 1;
else --等于0说明可以释放锁，直接删除
    redis.call('del',key);
    return nil;
end;