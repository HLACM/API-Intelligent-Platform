package com.yupi.springbootinit.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czq.apicommon.entity.InterfaceInfo;
import com.czq.apicommon.entity.User;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.constant.UserConstant;
import com.yupi.springbootinit.exception.BusinessException;
import com.czq.apicommon.entity.UserInterfaceInfo;
import com.yupi.springbootinit.model.dto.userinterface.UpdateUserInterfaceInfoDTO;
import com.yupi.springbootinit.model.vo.InterfaceInfoVo;
import com.yupi.springbootinit.model.vo.UserInterfaceInfoVO;
import com.yupi.springbootinit.service.InterfaceInfoService;
import com.yupi.springbootinit.service.UserInterfaceInfoService;
import com.yupi.springbootinit.mapper.UserInterfaceInfoMapper;
import com.yupi.springbootinit.service.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户接口信息服务实现类
 */
@Service
public class UserInterfaceInfoServiceImpl extends ServiceImpl<UserInterfaceInfoMapper, UserInterfaceInfo>
    implements UserInterfaceInfoService{

    @Resource
    private UserInterfaceInfoMapper userInterfaceInfoMapper;

    @Resource
    private UserService userService;

    @Resource
    private InterfaceInfoService interfaceInfoService;


    /**
     * 判断新增用户接口数据时参数是否合法
     * @param userInterfaceInfo
     * @param add
     */
    @Override
    public void validUserInterfaceInfo(UserInterfaceInfo userInterfaceInfo, boolean add) {
        if (userInterfaceInfo == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        // 创建时，参数不能为空
        if (add) {
            if (userInterfaceInfo.getUserId() == null || userInterfaceInfo.getInterfaceInfoId() == null){
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "接口或用户不存在");
            }

            if (userInterfaceInfo.getTotalNum()<0 || userInterfaceInfo.getLeftNum()<0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "接口分配次数或者剩余次数不能小于o");
            }

        }

    }

    /**
     * 调用接口统计，用户每次调用接口成功，次数+1
     * 将判断接口是否还有调用次数和统计接口调用两个操作放到一个方法中，并成为一个事务，解决二者数据一致性问题
     * @param interfaceInfoId
     * @param userId
     * @return
     */
    @Transactional
    @Override
    public boolean invokeCount(long userId, long interfaceInfoId) {

        //校验用户id，接口id是否合理
        if (userId<0 || interfaceInfoId<0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"用户或接口不存在");
        }

        //查询调用接口详情，包括剩余次数和调用乐观锁版本号，校验用户的接口剩余调用次数是否充足
        QueryWrapper<UserInterfaceInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userId",userId);
        queryWrapper.eq("interfaceInfoId",interfaceInfoId);
        UserInterfaceInfo userInterfaceInfo = userInterfaceInfoMapper.selectOne(queryWrapper);
        Integer version = userInterfaceInfo.getVersion();

        Integer leftNum = userInterfaceInfo.getLeftNum();
        if (leftNum<=0){
            log.error("接口剩余调用次数不足");
            return false;
        }

        //接口总调用次数+1，剩余调用次数-1
        //考虑计数的并发安全问题如何解决
        UpdateWrapper<UserInterfaceInfo> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("userId",userId);
        updateWrapper.eq("interfaceInfoId",interfaceInfoId);
        updateWrapper.eq("version",version);
        //再次检查leftNum防止接口调用次数越界（乐观锁思想解决技术的并发安全问题）
        updateWrapper.gt("leftNum",0);
        updateWrapper.setSql("totalNum = totalNum +1,leftNum = leftNum-1,version = version+1");
        return this.update(updateWrapper);

    }


    /**
     * 接口调用失败，需要对数据进行回滚
     * @param userId
     * @param interfaceInfoId
     * @return
     */
    @Override
    public boolean recoverInvokeCount(long userId, long interfaceInfoId) {
        if (userId<0 || interfaceInfoId<0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"用户或接口不存在");
        }
        UpdateWrapper<UserInterfaceInfo> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("userId",userId);
        updateWrapper.eq("interfaceInfoId",interfaceInfoId);
        updateWrapper.gt("leftNum",0);
        updateWrapper.setSql("totalNum = totalNum -1,leftNum = leftNum+1,version = version+1");
        return this.update(updateWrapper);
    }

    /**
     * 获取用户所拥有的接口剩余调用次数
     * @param userId
     * @param interfaceInfoId
     * @return
     */
    @Override
    public int getLeftInvokeCount(long userId, long interfaceInfoId) {
        //1.根据用户id和接口id获取用户接口关系详情对象
        QueryWrapper<UserInterfaceInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userId",userId);
        queryWrapper.eq("interfaceInfoId",interfaceInfoId);
        UserInterfaceInfo userInterfaceInfo = userInterfaceInfoMapper.selectOne(queryWrapper);
        //2.从用户接口关系详情对象中获取剩余调用次数
        return userInterfaceInfo.getLeftNum();
    }


    /**
     * 更新表中用户剩余调用次数
     * @param updateUserInterfaceInfoDTO
     * @return
     */
    @Override
    public boolean updateUserInterfaceInfo(UpdateUserInterfaceInfoDTO updateUserInterfaceInfoDTO) {
        Long userId = updateUserInterfaceInfoDTO.getUserId();
        Long interfaceId = updateUserInterfaceInfoDTO.getInterfaceId();
        Long lockNum = updateUserInterfaceInfoDTO.getLockNum();

        if(interfaceId == null || userId == null || lockNum == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        UserInterfaceInfo one = this.getOne(
                new QueryWrapper<UserInterfaceInfo>()
                        .eq("userId", userId)
                        .eq("interfaceInfoId", interfaceId)
        );

        if (one != null) {
            // 说明是增加调用次数
            return this.update(
                    new UpdateWrapper<UserInterfaceInfo>()
                            .eq("userId", userId)
                            .eq("interfaceInfoId", interfaceId)
                            .setSql("leftNum = leftNum + " + lockNum)
            );
        } else {
            // 说明是第一次购买接口
            UserInterfaceInfo userInterfaceInfo = new UserInterfaceInfo();
            userInterfaceInfo.setUserId(userId);
            userInterfaceInfo.setInterfaceInfoId(interfaceId);
            userInterfaceInfo.setLeftNum(Math.toIntExact(lockNum));
            return this.save(userInterfaceInfo);
        }

    }

    /**
     * 获取我的接口
     * @param userId
     * @param request
     * @return
     */
    @Override
    public List<UserInterfaceInfoVO> getInterfaceInfoByUserId(Long userId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        // 判断用户是否有权限
        if(!loginUser.getId().equals(userId) && !loginUser.getUserRole().equals(UserConstant.ADMIN_ROLE)){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }

        // 获取用户可调用接口列表
        QueryWrapper<UserInterfaceInfo> userInterfaceInfoQueryWrapper= new QueryWrapper<>();
        userInterfaceInfoQueryWrapper.eq("userId",loginUser.getId());
        List<UserInterfaceInfo> userInterfaceInfoList = this.list(userInterfaceInfoQueryWrapper);
        //将用户接口信息列表按照 interfaceInfoId 进行分组，得到一个Map集合，其中键是接口信息ID，值是该接口信息ID对应的用户接口信息列表。
        Map<Long, List<UserInterfaceInfo>> interfaceIdUserInterfaceInfoMap = userInterfaceInfoList.stream().
                collect(Collectors.groupingBy(UserInterfaceInfo::getInterfaceInfoId));
        //返回所有接口信息ID集合
        Set<Long> interfaceIds = interfaceIdUserInterfaceInfoMap.keySet();
        QueryWrapper<InterfaceInfo> interfaceInfoQueryWrapper = new QueryWrapper<>();
        if(CollectionUtil.isEmpty(interfaceIds)){
            return new ArrayList<>();
        }
        interfaceInfoQueryWrapper.in("id",interfaceIds);
        //查询符合条件的对应的接口的信息
        List<InterfaceInfo> interfaceInfoList = interfaceInfoService.list(interfaceInfoQueryWrapper);
        //将接口信息列表转换为用户接口信息视图对象 UserInterfaceInfoVO 的列表。
        List<UserInterfaceInfoVO> userInterfaceInfoVOList = interfaceInfoList.stream().map(interfaceInfo -> {
            UserInterfaceInfoVO userInterfaceInfoVO = new UserInterfaceInfoVO();
            //通过 BeanUtils.copyProperties() 方法将接口信息的属性复制到 UserInterfaceInfoVO 中
            BeanUtils.copyProperties(interfaceInfo, userInterfaceInfoVO);
            userInterfaceInfoVO.setInterfaceStatus(Integer.valueOf(interfaceInfo.getStatus()));

            // 从之前分组的用户接口信息列表中取出第一个用户接口信息，并将其属性复制到 UserInterfaceInfoVO 中。
            List<UserInterfaceInfo> userInterfaceInfos = interfaceIdUserInterfaceInfoMap.get(interfaceInfo.getId());
            UserInterfaceInfo userInterfaceInfo = userInterfaceInfos.get(0);
            BeanUtils.copyProperties(userInterfaceInfo, userInterfaceInfoVO);
            return userInterfaceInfoVO;
        }).collect(Collectors.toList());
        return userInterfaceInfoVOList;
    }

    @Override
    public List<InterfaceInfoVo> interfaceInvokeTopAnalysis(int limit) {
        List<UserInterfaceInfo> userInterfaceInfoList = userInterfaceInfoMapper.listTopInvokeInterfaceInfo(limit);
        Map<Long, List<UserInterfaceInfo>> interfaceInfoIdObjMap = userInterfaceInfoList.stream()
                .collect(Collectors.groupingBy(UserInterfaceInfo::getInterfaceInfoId));
        QueryWrapper<InterfaceInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("id", interfaceInfoIdObjMap.keySet());
        List<InterfaceInfo> list = interfaceInfoService.list(queryWrapper);
        if (CollectionUtils.isEmpty(list)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }

        List<InterfaceInfoVo> interfaceInfoVOList = list.stream().map(interfaceInfo -> {
            InterfaceInfoVo interfaceInfoVO = new InterfaceInfoVo();
            BeanUtils.copyProperties(interfaceInfo, interfaceInfoVO);
            int totalNum = interfaceInfoIdObjMap.get(interfaceInfo.getId()).get(0).getTotalNum();
            interfaceInfoVO.setTotalNum(totalNum);
            return interfaceInfoVO;
        }).collect(Collectors.toList());
        return interfaceInfoVOList;
    }
}




