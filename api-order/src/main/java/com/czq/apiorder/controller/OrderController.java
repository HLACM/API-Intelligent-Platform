package com.czq.apiorder.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czq.apicommon.common.BaseResponse;
import com.czq.apicommon.common.ResultUtils;
import com.czq.apicommon.vo.OrderVO;
import com.czq.apiorder.model.dto.OrderAddRequest;

import com.czq.apiorder.model.dto.OrderQueryRequest;
import com.czq.apiorder.service.TOrderService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/")
public class OrderController {

    @Resource
    private TOrderService orderService;


    /**
     * 新建订单
     * @param orderAddRequest
     * @param request
     * @return
     */
    @PostMapping("/addOrder")
    public BaseResponse<OrderVO> interfaceTOrder(@RequestBody OrderAddRequest orderAddRequest,HttpServletRequest request){
        OrderVO order = orderService.addOrder(orderAddRequest,request);
        return ResultUtils.success(order);
    }

    /**
     * 展示我的所有订单
     * @param orderQueryRequest
     * @param request
     * @return
     */
    @GetMapping("/list")
    public BaseResponse<Page<OrderVO>> listPageOrder(OrderQueryRequest orderQueryRequest, HttpServletRequest request){
        Page<OrderVO> orderPage = orderService.listPageOrder(orderQueryRequest, request);
        return ResultUtils.success(orderPage);
    }


}
