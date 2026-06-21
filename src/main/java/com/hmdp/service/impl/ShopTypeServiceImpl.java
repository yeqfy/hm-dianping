package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result querySort() {
        // 1. 从redis中查询数据
        String typeJson = stringRedisTemplate.opsForValue().get(SHOP_TYPE_KEY);
        // 2. 存在返回数据
        if (StrUtil.isNotBlank(typeJson)) {
            List<ShopType> shopTypes = JSONUtil.toList(typeJson, ShopType.class);
            return Result.ok(shopTypes);
        }
        // 3. 不存在，查数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        // 4. 数据库不存在，报错返回
        if (shopTypes == null) {
            return Result.fail("商品列表错误");
        }
        // 5. 存在，存入redis
        stringRedisTemplate.opsForValue().set(SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopTypes));
        // 6.返回数据
        return Result.ok(shopTypes);
    }
}
