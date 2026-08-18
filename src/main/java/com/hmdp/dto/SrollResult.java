package com.hmdp.dto;

import lombok.Data;

import java.util.List;

/**
 * @Package: com.hmdp.dto
 * @Description: 滚动查询
 */

@Data
public class SrollResult {

    public List<?> list;
    public Long max;
    public Integer offset;
}
