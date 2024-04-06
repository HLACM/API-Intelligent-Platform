package com.czq.apiinterface.controller;


import com.czq.apiclientsdk.model.User;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/name")
public class NameController {

    /**
     * 鉴权的这部分本来是写在模拟接口中的，引入API网关后进行统一的鉴权
     * @param user
     * @return
     */
    @PostMapping("/user")
    public String getName(@RequestBody User user){
        return "你的名字是"+user.getName();
    }
}
