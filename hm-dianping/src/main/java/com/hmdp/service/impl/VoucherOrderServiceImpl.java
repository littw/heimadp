package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
        //扣减库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id", voucherId).eq("stock",stock).update();
        if (!success){
            return Result.fail("库存不足！");
        }
        //生成优惠券秒杀订单
        VoucherOrder voucherOrder = new VoucherOrder();
        Long id = redisIdWorker.nextId("order");
        //设置订单编号
        voucherOrder.setId(id);
        //设置用户编号
        voucherOrder.setUserId(UserHolder.getUser().getId());
        //设置优惠券id
        voucherOrder.setVoucherId(seckillVoucher.getVoucherId());
        //保存秒杀券
        save(voucherOrder);
        return Result.ok(voucherOrder.getId());
    }
}
