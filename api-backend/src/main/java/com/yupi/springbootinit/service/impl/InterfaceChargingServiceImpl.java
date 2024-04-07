package com.yupi.springbootinit.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czq.apicommon.entity.InterfaceCharging;
import com.yupi.springbootinit.service.InterfaceChargingService;
import com.yupi.springbootinit.mapper.InterfaceChargingMapper;
import org.springframework.stereotype.Service;

/**
 * 付费接口业务层
 */
@Service
public class InterfaceChargingServiceImpl extends ServiceImpl<InterfaceChargingMapper, InterfaceCharging>
    implements InterfaceChargingService{

}




