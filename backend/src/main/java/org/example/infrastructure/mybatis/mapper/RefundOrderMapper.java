package org.example.infrastructure.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.infrastructure.mybatis.entity.RefundOrderEntity;

@Mapper
public interface RefundOrderMapper extends BaseMapper<RefundOrderEntity> {
}
