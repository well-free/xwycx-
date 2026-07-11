package org.example.infrastructure.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.infrastructure.mybatis.entity.CustomerOrderItemEntity;

@Mapper
public interface CustomerOrderItemMapper extends BaseMapper<CustomerOrderItemEntity> {
}
