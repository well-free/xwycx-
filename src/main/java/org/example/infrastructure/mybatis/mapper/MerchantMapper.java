package org.example.infrastructure.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.infrastructure.mybatis.entity.MerchantEntity;

@Mapper
public interface MerchantMapper extends BaseMapper<MerchantEntity> {
}
