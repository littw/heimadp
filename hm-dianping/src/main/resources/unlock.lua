---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by Administrator.
--- DateTime: 2024/4/22 11:03
---

--比较线程标识是否与锁中的一致
if(redis.call('get',KEY[1]==ARGV[1])) then
    --一致则释放锁
    return redis.call('del',KEY[1])
end
return 0

