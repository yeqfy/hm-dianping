package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
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
    private RedisIdWorker idWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        // 3. 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }
        // 4. 判断库存是否为空
        if (voucher.getStock()<1) {
            return Result.fail("库存不足");
        }

        /**悲观锁实现一人一单
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }*/

        /**
         * 分布式锁实现一人一单
         */
        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        // 获取锁
        Boolean isLock = lock.tryLock(1200L);
        // 判断锁是否存在
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.实现一人一单
        Long userId = UserHolder.getUser().getId();
        // 5.1. 查询用户id，订单id
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2. 判断是否存在
        if (count>0) {
            return Result.fail("用户已经购买过一次了！");
        }
        // 6. 扣减库存
        Boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")  // set stock = ?
                .eq("voucher_id", voucherId)  // where voucher_id = ?
                .gt("stock", 0) // CAS 乐观锁 解决超卖问题        and stock > 0
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
        // 7. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1. 订单id
        Long orderId = idWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2. 用户id
        //Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 7.3. 优惠券id
        voucherOrder.setVoucherId(voucherId);

        // 8. 保存订单到数据库
        save(voucherOrder);
        // 9. 返回订单id
        return Result.ok(orderId);
    }
}
