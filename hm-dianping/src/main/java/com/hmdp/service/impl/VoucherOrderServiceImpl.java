package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 实现优惠券秒杀功能
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //根据id查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //优惠券是否还未开始抢购
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        if (beginTime.isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        //优惠券是否过期
        LocalDateTime endTime = seckillVoucher.getEndTime();
        if (endTime.isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已结束");
        }
        //库存是否充足
        Integer stock = seckillVoucher.getStock();
        if (stock<1){
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();
        //创建锁对象
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
        //获取锁
        boolean isLock = simpleRedisLock.tryLock(1200);
        //获取失败，返回失败信息
        if (!isLock) {
            return Result.fail("不允许重复下单！");
        }
        //成功，执行事务
        try {
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.creatVoucherOrder(voucherId);
        } finally {
            //释放锁
            simpleRedisLock.unlock();
        }

        /*//对用户id值进行加锁
        //需要对userId的值进行加锁，因为每次调用这个方法的时候，都会得到一个新的对象，不管值是否相同，所以，应该对对象的值进行加锁 ，将其转换为String类型
        //但是toString()方法底层是创建一个新的String对象，并不能保证对象是相同的
        //添加intern()方法从字符串常量池中找到和当前值相同的值的地址并返回，这样可以确保对同一个用户的id值进行加锁
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            //获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.creatVoucherOrder(voucherId);*/
    }
    public Result creatVoucherOrder(Long voucherId){
         //5.一人一单
         //根据用户的id进行商品订单的查询
         Long userId = UserHolder.getUser().getId();
         //根据userId进行下单数量查询
         Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
         if (count > 0) {
             return Result.fail("用户已经购买过一次！");
         }
         //6.扣减库存
         boolean success = seckillVoucherService.update().setSql("stock=stock-1")
                 .eq("voucher_id", voucherId)
                 .gt("stock", 0)  //优化改进乐观锁
//                .eq("stock",stock)
                 .update();
         //扣减库存
         if (!success) {
             return Result.fail("库存不足！");
         }
         //生成优惠券秒杀订单
         VoucherOrder voucherOrder = new VoucherOrder();
         Long orderId = redisIdWorker.nextId("order");
         //设置订单编号
         voucherOrder.setId(orderId);
         //设置用户编号
         voucherOrder.setUserId(userId);
         //设置优惠券id
         voucherOrder.setVoucherId(voucherId);
         //保存秒杀券
         save(voucherOrder);
         return Result.ok(orderId);
    }
}
