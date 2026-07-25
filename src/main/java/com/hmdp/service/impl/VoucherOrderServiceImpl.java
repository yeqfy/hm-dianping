package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
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
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker idWorker;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private RedissonClient redissonClient;

    private IVoucherOrderService proxy;
    // 阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    // 线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();


    // @PostConstruct 当前类初始化完成后，执行
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 线程任务 runnable
     private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            // 1. 获取阻塞队列
            try {
                while (true){
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                }
            } catch (Exception e) {
                log.error("订单异常信息", e);
            }
        }
    }


    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁对象
        boolean isLock = lock.tryLock();

        //加锁失败
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            //获取代理对象(事务)
            //IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 子线程无法在ThreadLocal中取到代理对象，所以提前在主线程获取
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 用户id
        Long userId = UserHolder.getUser().getId();
        // 1. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        // 2. 判断是否为0
        // 2.1 结果不为0 ，代表没有购买资格
        int r = result.intValue(); // 转为int类型数据
        if (r != 0){
            return Result.fail(r == 1 ? "库存不足" : "重复下单");
        }
        // 2.2 结果为0 ，把下单信息保存到阻塞队列
        // 2. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.1. 订单id
        Long orderId = idWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 2.2. 用户id
        //Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 2.3. 优惠券id
        voucherOrder.setVoucherId(voucherId);
        // 2.4 存入阻塞队列
        orderTasks.add(voucherOrder);

        // 3.获取代理对象(事务)
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 4. 返回订单id
        return Result.ok(orderId);
    }


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.实现一人一单
        Long userId = UserHolder.getUser().getId();
        // 5.1. 查询用户id，订单id
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2. 判断是否存在
        if (count>0) {
            log.error("用户已经购买过一次了！");
            return;
        }
        // 6. 扣减库存
        Boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")  // set stock = ?
                .eq("voucher_id", voucherOrder.getVoucherId())  // where voucher_id = ?
                .gt("stock", 0) // CAS 乐观锁 解决超卖问题        and stock > 0
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }

        // 8. 保存订单到数据库
        save(voucherOrder);
    }
}
