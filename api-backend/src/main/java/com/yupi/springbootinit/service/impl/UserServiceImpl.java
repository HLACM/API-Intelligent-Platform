package com.yupi.springbootinit.service.impl;


import static com.czq.apicommon.constant.RabbitmqConstant.EXCHANGE_SMS_INFORM;
import static com.czq.apicommon.constant.RabbitmqConstant.ROUTINGKEY_SMS;
import static com.czq.apicommon.constant.RedisConstant.LOGINCODEPRE;
import static com.yupi.springbootinit.constant.UserConstant.USER_LOGIN_STATE;
import static com.yupi.springbootinit.utils.LeakyBucket.loginLeakyBucket;
import static com.yupi.springbootinit.utils.LeakyBucket.registerLeakyBucket;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.captcha.generator.RandomGenerator;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czq.apicommon.common.JwtUtils;
import com.czq.apicommon.entity.SmsMessage;
import com.google.gson.Gson;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.constant.CommonConstant;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.exception.ThrowUtils;
import com.yupi.springbootinit.mapper.UserMapper;
import com.yupi.springbootinit.model.dto.user.UserQueryRequest;
import com.czq.apicommon.entity.User;
import com.yupi.springbootinit.model.dto.user.UserUpdateRequest;
import com.yupi.springbootinit.model.enums.UserRoleEnum;
import com.yupi.springbootinit.model.vo.LoginUserVO;
import com.yupi.springbootinit.model.vo.UserDevKeyVO;
import com.yupi.springbootinit.model.vo.UserVO;
import com.yupi.springbootinit.service.UserService;
import com.yupi.springbootinit.utils.FileUploadUtil;
import com.yupi.springbootinit.utils.LeakyBucket;
import com.yupi.springbootinit.utils.SqlUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户服务实现
 *
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * 盐值，混淆密码
     */
    private static final String SALT = "chen";

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 图片验证码 redis 前缀
     */
    private static final String CAPTCHA_PREFIX = "api:captchaId:";




    @Resource
    private Gson gson;

    //登录和注册的标识，方便切换不同的令牌桶来限制验证码发送
    private static final String LOGIN_SIGN = "login";

    private static final String REGISTER_SIGN="register";

    public static final String USER_LOGIN_EMAIL_CODE ="user:login:email:code:";
    public static final String USER_REGISTER_EMAIL_CODE ="user:register:email:code:";


    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 3) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if (userPassword.length() < 3 || checkPassword.length() < 3) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        // 密码和校验密码相同
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }

//        //手机号格式
//        if (!Pattern.matches("^1[3-9]\\d{9}$", phone)) {
//            throw new BusinessException(ErrorCode.PARAMS_ERROR, "手机号错误!");
//        }

        synchronized (userAccount.intern()) {
            // 账户不能重复
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("userAccount", userAccount);
            long count = this.baseMapper.selectCount(queryWrapper);
            if (count > 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
            }
            // 2. 加密
            String encryptPassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());

            //给用户分配调用接口的公钥和私钥ak,sk，保证复杂的同时要保证唯一
            String accessKey = DigestUtil.md5Hex(SALT+userAccount+ RandomUtil.randomNumbers(5));
            String secretKey = DigestUtil.md5Hex(SALT+userAccount+ RandomUtil.randomNumbers(8));

            // 3. 插入数据
            User user = new User();
            user.setAccessKey(accessKey);
            user.setSecretKey(secretKey);
            user.setUserAccount(userAccount);
            user.setUserName(userAccount);
            user.setUserPassword(encryptPassword);
            boolean saveResult = this.save(user);
            if (!saveResult) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
            }
            return user.getId();
        }
    }

    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request, HttpServletResponse response) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 3) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号错误");
        }
        if (userPassword.length() < 3) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码错误");
        }
        // 2. 加密
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
        // 查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        queryWrapper.eq("userPassword", encryptPassword);
        User user = this.baseMapper.selectOne(queryWrapper);
        // 用户不存在
        if (user == null) {
            log.info("user login failed, userAccount cannot match userPassword");
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        // 3. 记录用户的登录态
        return setLoginUser(response, user);
    }



    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 先判断是否已登录
        Long userId = JwtUtils.getUserIdByToken(request);
        if (userId == null){
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        String userJson = stringRedisTemplate.opsForValue().get(USER_LOGIN_STATE+userId);
        User user = gson.fromJson(userJson, User.class);
        if (user == null){
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return user;
    }


    /**
     * 是否为管理员
     *
     * @param request
     * @return
     */
    @Override
    public boolean isAdmin(HttpServletRequest request) {
        // 仅管理员可查询
        User user = getLoginUser(request);
        return isAdmin(user);
    }

    @Override
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }

    /**
     * 用户注销
     *
     * @param request
     */
    @Override
    public boolean userLogout(HttpServletRequest request,HttpServletResponse response) {
        Cookie[] cookies = request.getCookies();
        for (Cookie cookie : cookies) {
            if (cookie.getName().equals("token")){
                Long userId = JwtUtils.getUserIdByToken(request);
                stringRedisTemplate.delete(USER_LOGIN_STATE+userId);
                Cookie timeOutCookie = new Cookie(cookie.getName(),cookie.getValue());
                timeOutCookie.setMaxAge(0);
                response.addCookie(timeOutCookie);
                return true;
            }
        }

        throw new BusinessException(ErrorCode.OPERATION_ERROR, "未登录");
    }

    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtils.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public List<UserVO> getUserVO(List<User> userList) {
        if (CollectionUtils.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String unionId = userQueryRequest.getUnionId();
        String mpOpenId = userQueryRequest.getMpOpenId();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(id != null, "id", id);
        queryWrapper.eq(StringUtils.isNotBlank(unionId), "unionId", unionId);
        queryWrapper.eq(StringUtils.isNotBlank(mpOpenId), "mpOpenId", mpOpenId);
        queryWrapper.eq(StringUtils.isNotBlank(userRole), "userRole", userRole);
        queryWrapper.like(StringUtils.isNotBlank(userProfile), "userProfile", userProfile);
        queryWrapper.like(StringUtils.isNotBlank(userName), "userName", userName);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }


    @Override
    public void sendCode(String email, String captchaType) {


        if (StringUtils.isBlank(captchaType)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"验证码类型为空!!!");
        }

        //令牌桶算法实现短信接口的限流，因为手机号码重复发送短信，要进行流量控制
        //解决同一个手机号的并发问题，锁的粒度非常小，不影响性能。只是为了防止用户第一次发送短信时的恶意调用
        synchronized (email.intern()) {
            Boolean exist = stringRedisTemplate.hasKey(USER_LOGIN_EMAIL_CODE +email);
            if (exist!=null && exist) {
                //1.令牌桶算法对手机短信接口进行限流 具体限流规则为同一个手机号，60s只能发送一次
                long lastTime=0L;
                LeakyBucket leakyBucket = null;
                if (captchaType.equals(REGISTER_SIGN)){
                    String strLastTime = stringRedisTemplate.opsForValue().get(USER_REGISTER_EMAIL_CODE + email);
                    if (strLastTime!=null){
                        lastTime = Long.parseLong(strLastTime);
                    }
                    leakyBucket = registerLeakyBucket;
                }else{
                    String strLastTime = stringRedisTemplate.opsForValue().get(USER_LOGIN_EMAIL_CODE + email);
                    if (strLastTime!=null){
                        lastTime = Long.parseLong(strLastTime);
                    }
                    leakyBucket = loginLeakyBucket;
                }

                if (!leakyBucket.control(lastTime)) {
                    log.info("邮箱发送太频繁了");
                    throw new BusinessException(ErrorCode.PARAMS_ERROR,"邮箱送太频繁了");
                }
            }

            //2.符合限流规则则生成手机短信
            String code = RandomUtil.randomNumbers(4);
            SmsMessage smsMessage = new SmsMessage(email, code);


            //消息队列异步发送短信，提高短信的吞吐量
            rabbitTemplate.convertAndSend(EXCHANGE_SMS_INFORM,ROUTINGKEY_SMS,smsMessage);

            log.info("邮箱对象："+smsMessage.toString());
            //更新手机号发送短信的时间
            if (captchaType.equals(REGISTER_SIGN)){
                stringRedisTemplate.opsForValue().set(USER_REGISTER_EMAIL_CODE +email,""+System.currentTimeMillis()/1000);
            }else {
                stringRedisTemplate.opsForValue().set(USER_LOGIN_EMAIL_CODE +email,""+System.currentTimeMillis()/1000);
            }

        }

    }

    @Override
    public void getCaptcha(HttpServletRequest request, HttpServletResponse response) {
        //前端必须传一个 signature 来作为唯一标识
        String signature = request.getHeader("signature");
        if (StringUtils.isEmpty(signature)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        try {
            // 自定义纯数字的验证码（随机4位数字，可重复）
            RandomGenerator randomGenerator = new RandomGenerator("0123456789", 4);
            LineCaptcha lineCaptcha = CaptchaUtil.createLineCaptcha(100, 30);
            lineCaptcha.setGenerator(randomGenerator);
            //设置响应头
            response.setContentType("image/jpeg");
            response.setHeader("Pragma", "No-cache");
            // 输出到页面
            lineCaptcha.write(response.getOutputStream());
            // 打印日志
            log.info("captchaId：{} ----生成的验证码:{}", signature, lineCaptcha.getCode());
            // 将验证码设置到Redis中,2分钟过期
            stringRedisTemplate.opsForValue().set(CAPTCHA_PREFIX + signature, lineCaptcha.getCode(), 2, TimeUnit.MINUTES);
            // 关闭流
            response.getOutputStream().close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public UserDevKeyVO genkey(HttpServletRequest request) {
        User loginUser = getLoginUser(request);
        if(loginUser == null){
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }
        UserDevKeyVO userDevKeyVO = genKey(loginUser.getUserAccount());
        UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("userAccount",loginUser.getUserAccount());
        updateWrapper.eq("id",loginUser.getId());
        updateWrapper.set("accessKey",userDevKeyVO.getAccessKey());
        updateWrapper.set("secretKey",userDevKeyVO.getSecretKey());
        this.update(updateWrapper);
        loginUser.setAccessKey(userDevKeyVO.getAccessKey());
        loginUser.setSecretKey(userDevKeyVO.getSecretKey());

        //重置登录用户的ak,sk信息
        String userJson = gson.toJson(loginUser);
        stringRedisTemplate.opsForValue().set(USER_LOGIN_STATE+loginUser.getId(),userJson,JwtUtils.EXPIRE, TimeUnit.MILLISECONDS);
        return userDevKeyVO;
    }

    @Override
    public LoginUserVO userLoginBySms(String emailNum, String emailCode, HttpServletRequest request, HttpServletResponse response) {

        //1.校验邮箱验证码是否正确
        if (!emailCodeValid(emailNum, emailCode)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"邮箱验证码错误!!!");
        }

        //2.校验邮箱是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("email",emailNum);
        User user = this.getOne(queryWrapper);

        if(user == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"用户不存在！");
        }

        return setLoginUser(response, user);
    }


    @Override
    public long userEmailRegister(String emailNum, String emailCaptcha) {
        if (!emailCodeValid(emailNum, emailCaptcha)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"邮箱格式或邮箱验证码错误!!!");
        }

        //2.校验邮箱是否已经注册过
        synchronized (emailNum.intern()) {
            // 账户不能重复
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("email",emailNum);
            long count = this.baseMapper.selectCount(queryWrapper);
            if (count > 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,"邮箱已经注册过了！！！账号重复");
            }

            //给用户分配调用接口的公钥和私钥ak,sk，保证复杂的同时要保证唯一
            String accessKey = DigestUtil.md5Hex(SALT+emailNum+ RandomUtil.randomNumbers(5));
            String secretKey = DigestUtil.md5Hex(SALT+emailNum+ RandomUtil.randomNumbers(8));

            // 3. 插入数据
            User user = new User();
            user.setAccessKey(accessKey);
            user.setSecretKey(secretKey);
            user.setUserName(emailNum);
            user.setEmail(emailNum);
            boolean saveResult = this.save(user);
            if (!saveResult) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
            }
            return user.getId();
        }
    }

    @Override
    public boolean uploadFileAvatar(MultipartFile file, HttpServletRequest request) {
        User loginUser = this.getLoginUser(request);

        //更新持久层用户头像信息
        User updateUser = new User();
        updateUser.setId(loginUser.getId());
        String url = FileUploadUtil.uploadFileAvatar(file);
        updateUser.setUserAvatar(url);
        boolean result = this.updateById(updateUser);

        //更新用户缓存
        loginUser.setUserAvatar(url);
        String userJson = gson.toJson(loginUser);
        stringRedisTemplate.opsForValue().set(USER_LOGIN_STATE + loginUser.getId(), userJson, JwtUtils.EXPIRE, TimeUnit.MILLISECONDS);
        return result;
    }

    @Override
    public boolean updateUser(UserUpdateRequest userUpdateRequest, HttpServletRequest request) {

        //允许用户修改自己的信息，但拒绝用户修改别人的信息；但管理员可以修改别人的信息
        User loginUser = this.getLoginUser(request);
        Long id = userUpdateRequest.getId();
        if (!loginUser.getId().equals(id)){
            if (!loginUser.getUserRole().equals(UserRoleEnum.ADMIN.getValue())){
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        }

        User user = new User();
        BeanUtils.copyProperties(userUpdateRequest, user);
        boolean result = this.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);

        //修改完要更新用户缓存
        loginUser.setUserName(userUpdateRequest.getUserName());
        loginUser.setGender(userUpdateRequest.getGender());
        String userJson = gson.toJson(loginUser);
        stringRedisTemplate.opsForValue().set(USER_LOGIN_STATE + loginUser.getId(), userJson, JwtUtils.EXPIRE, TimeUnit.MILLISECONDS);

        return true;
    }

    /**
     * 记录用户的登录态，并返回脱敏后的登录用户
     * @param response
     * @param user
     * @return
     */
    private LoginUserVO setLoginUser(HttpServletResponse response, User user) {
        String token = JwtUtils.getJwtToken(user.getId(), user.getUserName());
        Cookie cookie = new Cookie("token", token);
        cookie.setPath("/");
        response.addCookie(cookie);
        String userJson = gson.toJson(user);
        stringRedisTemplate.opsForValue().set(USER_LOGIN_STATE + user.getId(), userJson, JwtUtils.EXPIRE, TimeUnit.MILLISECONDS);
        return this.getLoginUserVO(user);
    }


    private UserDevKeyVO genKey(String userAccount){
        String accessKey = DigestUtil.md5Hex(SALT + userAccount + RandomUtil.randomNumbers(5));
        String secretKey = DigestUtil.md5Hex(SALT + userAccount + RandomUtil.randomNumbers(8));
        UserDevKeyVO userDevKeyVO = new UserDevKeyVO();
        userDevKeyVO.setAccessKey(accessKey);
        userDevKeyVO.setSecretKey(secretKey);
        return userDevKeyVO;
    }

    /**
     * 邮箱验证码校验
     * @param emailNum
     * @param emailCode
     * @return
     */
    private boolean emailCodeValid(String emailNum, String emailCode) {
        String code = stringRedisTemplate.opsForValue().get(LOGINCODEPRE + emailNum);
        if (StringUtils.isBlank(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱格式或邮箱验证码错误!!!");
        }

        if (!emailCode.equals(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱格式或邮箱验证码错误!!!");
        }

        return true;
    }
}
