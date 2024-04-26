package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    @Resource
    private RedissonClient redissonClient;

//    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);

    //一步下单，开启独立线程
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    String queueName="stream.orders";
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    //1.获取消息队列中的秒杀订单  XREADGROUP group g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息获取是否成功
                    if (records==null||records.isEmpty()){
                        //2.1如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //3.进行数据的处理
                    MapRecord<String, Object, Object> record = records.get(0);//因为Count指定为1，所以只会获取到一条数据
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //3.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //4.下单之后进行ACK确认 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    //出现异常，需要从pending-list中进行处理
                    handlePendingList();
                }
            }
        }
    }

    /*private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    //1.获取消息队列中的秒杀订单
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }  */

    /**
     * 处理pending-list
     */
    private void handlePendingList() {
        while (true){
            try {
                //1.获取消息队列中的秒杀订单  XREADGROUP group g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                //2.判断消息获取是否成功
                if (records==null||records.isEmpty()){
                    //2.1如果获取失败，说明pending-list没有异常消息，直接跳出循环
                    break;
                }
                //3.进行数据的处理
                MapRecord<String, Object, Object> record = records.get(0);//因为Count指定为1，所以只会获取到一条数据
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                //3.如果获取成功，可以下单
                handleVoucherOrder(voucherOrder);
                //4.下单之后进行ACK确认 XACK stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
            } catch (Exception e) {
                log.error("处理订单异常",e);
                //出现异常，继续下一次循环即可
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }

    }
    /**
     * 处理订单信息
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock(); //
        //获取失败，返回失败信息
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        //成功，执行事务
        try {
            proxy.creatVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            //simpleRedisLock.unlock();
            lock.unlock();
        }
    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private IVoucherOrderService proxy;

    /**
     * 使用lua脚本实现优惠券秒杀功能
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本，判断下单资格
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(),String.valueOf(orderId));
        //2.判断结果是否为0
        //2.1结果不为0
        if (result!=0){
            return result==1?Result.fail("库存不足！"):Result.fail("用户已下单");
        }
        //2.2结果为0，有购买资格，且订单信息已经发送到了阻塞队列
        //从主线程获取代理对象
        proxy=(IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }
    /*public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本，判断下单资格
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
        //2.判断结果是否为0
        //2.1结果不为0
        if (result!=0){
            return result==1?Result.fail("库存不足！"):Result.fail("用户已下单");
        }
        //2.2结果为0，有购买资格，将订单信息添加到阻塞队列
        //TODO 保存到阻塞队列
        Long orderId = redisIdWorker.nextId("order");
        //生成优惠券秒杀订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //设置订单编号
        voucherOrder.setId(orderId);
        //设置用户编号
        voucherOrder.setUserId(userId);
        //设置优惠券id
        voucherOrder.setVoucherId(voucherId);
        //将优惠券加入阻塞队列
        orderTasks.add(voucherOrder);
        //从主线程获取代理对象
        proxy=(IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }*/

    /**
     * 实现优惠券秒杀功能
     *
     * @param voucherOrder
     * @return
     */
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //根据id查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        //优惠券是否还未开始抢购
//        LocalDateTime beginTime = seckillVoucher.getBeginTime();
//        if (beginTime.isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始");
//        }
//        //优惠券是否过期
//        LocalDateTime endTime = seckillVoucher.getEndTime();
//        if (endTime.isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已结束");
//        }
//        //库存是否充足
//        Integer stock = seckillVoucher.getStock();
//        if (stock<1){
//            return Result.fail("库存不足！");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
////      SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
//        RLock lock = redissonClient.getLock("order:" + userId);
//        //获取锁
//        boolean isLock = lock.tryLock(); //
//
//        //获取失败，返回失败信息
//        if (!isLock) {
//            return Result.fail("不允许重复下单！");
//        }
//        //成功，执行事务
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.creatVoucherOrder(voucherId);
//        } finally {
//            //释放锁
////            simpleRedisLock.unlock();
//            lock.unlock();
//        }
//
//        /*//对用户id值进行加锁
//        //需要对userId的值进行加锁，因为每次调用这个方法的时候，都会得到一个新的对象，不管值是否相同，所以，应该对对象的值进行加锁 ，将其转换为String类型
//        //但是toString()方法底层是创建一个新的String对象，并不能保证对象是相同的
//        //添加intern()方法从字符串常量池中找到和当前值相同的值的地址并返回，这样可以确保对同一个用户的id值进行加锁
//        Long userId = UserHolder.getUser().getId();
//        synchronized (userId.toString().intern()) {
//            //获取代理对象(事务)
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.creatVoucherOrder(voucherId);*/
//    }


/*    @Transactional
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
    }*/

    @Transactional
    public void creatVoucherOrder(VoucherOrder voucherOrder){
        //5.一人一单
        //根据用户的id进行商品订单的查询
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //根据userId进行下单数量查询
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("用户已经购买过一次！");
        }
        //6.扣减库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)  //优化改进乐观锁
//                .eq("stock",stock)
                .update();
        //扣减库存
        if (!success) {
            log.error("库存不足！");
        }
        //保存秒杀订单
        save(voucherOrder);
    }




}
