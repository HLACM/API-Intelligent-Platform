package com.yupi.springbootinit.model.dto.userinterface;

import lombok.Data;

import java.io.Serializable;

/**
 *
 */
@Data
public class UpdateUserInterfaceInfoDTO implements Serializable {

    private static final long serialVersionUID = 1472097902521779075L;

    private Long userId;

    private Long interfaceId;

    /**
     * 调用次数
     * 如果是免费获取则前端默认发送100次
     */
    private Long lockNum;
}
