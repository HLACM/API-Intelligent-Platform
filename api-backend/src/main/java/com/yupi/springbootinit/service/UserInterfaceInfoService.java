package com.yupi.springbootinit.service;

import com.czq.apicommon.entity.UserInterfaceInfo;
import com.baomidou.mybatisplus.extension.service.IService;
import com.yupi.springbootinit.model.dto.userinterface.UpdateUserInterfaceInfoDTO;
import com.yupi.springbootinit.model.vo.InterfaceInfoVo;
import com.yupi.springbootinit.model.vo.UserInterfaceInfoVO;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 *
 */
public interface UserInterfaceInfoService extends IService<UserInterfaceInfo> {


    void validUserInterfaceInfo(UserInterfaceInfo userInterfaceInfo, boolean add);


    /**
     * 统计接口调用次数
     * @param userId
     * @param interfaceInfoId
     * @return
     */
    boolean invokeCount(long userId,long interfaceInfoId);


    /**
     * 回滚接口调用次数
     * @param userId
     * @param interfaceInfoId
     * @return
     */
    boolean recoverInvokeCount(long userId, long interfaceInfoId);

    /**
     * 获取接口的剩余调用次数
     * @param userId
     * @param interfaceInfoId
     * @return
     */
    int getLeftInvokeCount(long userId, long interfaceInfoId);


    /**
     *更新用户接口信息
     * @param updateUserInterfaceInfoDTO
     * @return
     */
    boolean updateUserInterfaceInfo(UpdateUserInterfaceInfoDTO updateUserInterfaceInfoDTO);

    /**
     * 获取用户所拥有的接口列表
     * @param userId
     * @param request
     * @return
     */
    List<UserInterfaceInfoVO> getInterfaceInfoByUserId(Long userId, HttpServletRequest request);

    /**
     * 获取调用次数前limit的接口信息
     * @param limit
     * @return
     */
    List<InterfaceInfoVo> interfaceInvokeTopAnalysis(int limit);
}
