package org.example.infrastructure.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.infrastructure.mybatis.entity.SmsCodeEntity;

@Mapper
public interface SmsCodeMapper extends BaseMapper<SmsCodeEntity> {
}
