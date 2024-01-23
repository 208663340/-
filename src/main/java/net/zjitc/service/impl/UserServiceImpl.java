package net.zjitc.service.impl;


import cn.hutool.core.lang.Pair;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.zjitc.common.ErrorCode;
import net.zjitc.constants.UserConstants;
import net.zjitc.exception.BusinessException;
import net.zjitc.mapper.UserMapper;
import net.zjitc.model.domain.Follow;
import net.zjitc.model.domain.User;
import net.zjitc.model.request.UserRegisterRequest;
import net.zjitc.model.request.UserUpdateRequest;
import net.zjitc.model.vo.UserVO;
import net.zjitc.service.FollowService;
import net.zjitc.service.UserService;
import net.zjitc.utils.AlgorithmUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DigestUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static net.zjitc.constants.RedisConstants.*;
import static net.zjitc.constants.SystemConstants.DEFAULT_CACHE_PAGE;
import static net.zjitc.constants.SystemConstants.PAGE_SIZE;
import static net.zjitc.constants.UserConstants.ADMIN_ROLE;
import static net.zjitc.constants.UserConstants.USER_LOGIN_STATE;

/**
 * @author OchiaMalu
 * @description 针对表【user】的数据库操作Service实现
 * @createDate 2023-05-07 19:56:01
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final String[] avatarUrls = {
            "http://niu.ochiamalu.xyz/12d4949b4009d089eaf071aef0f1f40.jpg",
            "http://niu.ochiamalu.xyz/1bff61de34bdc7bf40c6278b2848fbcf.jpg",
            "http://niu.ochiamalu.xyz/22fe8428428c93a565e181782e97654.jpg",
            "http://niu.ochiamalu.xyz/75e31415779979ae40c4c0238aa4c34.jpg",
            "http://niu.ochiamalu.xyz/905731909dfdafd0b53b3c4117438d3.jpg",
            "http://niu.ochiamalu.xyz/a84b1306e46061c0d664e6067417e5b.jpg",
            "http://niu.ochiamalu.xyz/b93d640cc856cb7035a851029aec190.jpg",
            "http://niu.ochiamalu.xyz/c11ae3862b3ca45b0a6cdff1e1bf841.jpg",
            "http://niu.ochiamalu.xyz/cccfb0995f5d103414bd8a8bd742c34.jpg",
            "http://niu.ochiamalu.xyz/f870176b1a628623fa7fe9918b862d7.jpg"
    };
    @Resource
    private UserMapper userMapper;

    @Resource
    private FollowService followService;


    /**
     * 盐值，混淆密码
     */
    private static final String SALT = "ochiamalu";

    @Resource
    private StringRedisTemplate stringRedisTemplate;



    @Override
    public String userRegister(UserRegisterRequest userRegisterRequest, HttpServletRequest request) {
        String phone = userRegisterRequest.getPhone();
        String code = userRegisterRequest.getCode();
        String account = userRegisterRequest.getUserAccount();
        String password = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        checkRegisterRequest(phone, code, account, password, checkPassword);
        checkAccountValid(account);
        checkAccountRepetition(account);
        checkHasRegistered(phone);
        String key = REGISTER_CODE_KEY + phone;
        checkCode(code, key);
        checkPassword(password, checkPassword);
        long userId = insetUser(phone, account, password);
        return afterInsertUser(key, userId, request);
    }

    @Override
    public Long adminRegister(UserRegisterRequest userRegisterRequest, HttpServletRequest request) {
        User loginUser = getLoginUser(request);
        if (loginUser==null){
            throw new BusinessException(ErrorCode.NOT_LOGIN, "未登录");
        }
        Integer role = loginUser.getRole();
        if (!role.equals(ADMIN_ROLE)){
            throw new BusinessException(ErrorCode.NO_AUTH, "无权限");
        }
        String phone = userRegisterRequest.getPhone();
        String account = userRegisterRequest.getUserAccount();
        String password = userRegisterRequest.getUserPassword();
        checkAccountValid(account);
        checkAccountRepetition(account);
        return insetUser(phone, account, password);
    }

    @Override
    public void changeUserStatus(Long id) {
        User user = this.getById(id);
        LambdaUpdateWrapper<User> userLambdaUpdateWrapper = new LambdaUpdateWrapper<>();
        if (user.getStatus().equals(0)){
            userLambdaUpdateWrapper.eq(User::getId,id).set(User::getStatus,1);
        }else {
            userLambdaUpdateWrapper.eq(User::getId,id).set(User::getStatus,0);
        }
        try {
            this.update(userLambdaUpdateWrapper);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"系统错误");
        }
    }

    @Override
    public String userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            return null;
        }
        if (userAccount.length() < 4) {
            return null;
        }
        if (userPassword.length() < 8) {
            return null;
        }
        // 账户不能包含特殊字符
        String validPattern = "[`~!@#$%^&*()+=|{}':;',\\\\[\\\\].<>/?~！@#￥%……&*（）——+|{}【】‘；：”“’。，、？]";
        Matcher matcher = Pattern.compile(validPattern).matcher(userAccount);
        if (matcher.find()) {
            return null;
        }
        // 2. 加密
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
        // 查询用户是否存在
        LambdaQueryWrapper<User> userLambdaQueryWrapper = new LambdaQueryWrapper<>();
        userLambdaQueryWrapper.eq(User::getUserAccount, userAccount);
        User userInDatabase = this.getOne(userLambdaQueryWrapper);
        if (userInDatabase == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在");
        }
        if (!userInDatabase.getPassword().equals(encryptPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码错误");
        }
        if (!userInDatabase.getStatus().equals(0)){
            throw new BusinessException(ErrorCode.FORBIDDEN,"该用户已被封禁");
        }
        // 3. 用户脱敏
        User safetyUser = getSafetyUser(userInDatabase);
        // 4. 记录用户的登录态
        request.getSession().setAttribute(USER_LOGIN_STATE, safetyUser);
        request.getSession().setMaxInactiveInterval(900);
        String token = UUID.randomUUID().toString(true);
        Gson gson = new Gson();
        String userStr = gson.toJson(safetyUser);
        stringRedisTemplate.opsForValue().set(LOGIN_USER_KEY + token, userStr);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, Duration.ofMinutes(15));
        return token;
    }

    @Override
    public String adminLogin(String userAccount, String userPassword, HttpServletRequest request) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            return null;
        }
        if (userAccount.length() < 4) {
            return null;
        }
        if (userPassword.length() < 8) {
            return null;
        }
        // 账户不能包含特殊字符
        String validPattern = "[`~!@#$%^&*()+=|{}':;',\\\\[\\\\].<>/?~！@#￥%……&*（）——+|{}【】‘；：”“’。，、？]";
        Matcher matcher = Pattern.compile(validPattern).matcher(userAccount);
        if (matcher.find()) {
            return null;
        }
        // 2. 加密
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
        // 查询用户是否存在
        LambdaQueryWrapper<User> userLambdaQueryWrapper = new LambdaQueryWrapper<>();
        userLambdaQueryWrapper.eq(User::getUserAccount, userAccount);
        User userInDatabase = this.getOne(userLambdaQueryWrapper);
        if (userInDatabase == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在");
        }
        if (!userInDatabase.getPassword().equals(encryptPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码错误");
        }
        if (!userInDatabase.getStatus().equals(0)){
            throw new BusinessException(ErrorCode.FORBIDDEN,"该用户已被封禁");
        }
        if (!userInDatabase.getRole().equals(1)){
            throw new BusinessException(ErrorCode.NO_AUTH,"非管理员禁止登录");
        }
        // 3. 用户脱敏
        User safetyUser = getSafetyUser(userInDatabase);
        // 4. 记录用户的登录态
        request.getSession().setAttribute(USER_LOGIN_STATE, safetyUser);
        request.getSession().setMaxInactiveInterval(900);
        String token = UUID.randomUUID().toString(true);
        Gson gson = new Gson();
        String userStr = gson.toJson(safetyUser);
        stringRedisTemplate.opsForValue().set(LOGIN_USER_KEY + token, userStr);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, Duration.ofMinutes(15));
        return token;
    }

    /**
     * 用户脱敏
     *
     * @param originUser
     * @return
     */
    @Override
    public User getSafetyUser(User originUser) {
        if (originUser == null) {
            return null;
        }
        User safetyUser = new User();
        safetyUser.setId(originUser.getId());
        safetyUser.setUsername(originUser.getUsername());
        safetyUser.setUserAccount(originUser.getUserAccount());
        safetyUser.setAvatarUrl(originUser.getAvatarUrl());
        safetyUser.setGender(originUser.getGender());
        safetyUser.setPhone(originUser.getPhone());
        safetyUser.setEmail(originUser.getEmail());
        safetyUser.setRole(originUser.getRole());
        safetyUser.setStatus(originUser.getStatus());
        safetyUser.setCreateTime(originUser.getCreateTime());
        safetyUser.setTags(originUser.getTags());
        safetyUser.setProfile(originUser.getProfile());
        return safetyUser;
    }

    /**
     * 用户注销
     *
     * @param request
     */
    @Override
    public int userLogout(HttpServletRequest request) {
        // 移除登录态
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return 1;
    }

    /**
     * 根据标签搜索用户（内存过滤）
     *
     * @param tagNameList 用户要拥有的标签
     * @return
     */
    @Override
    public Page<User> searchUsersByTags(List<String> tagNameList, long currentPage) {
        if (CollectionUtils.isEmpty(tagNameList)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        LambdaQueryWrapper<User> userLambdaQueryWrapper = new LambdaQueryWrapper<>();
        for (String tagName : tagNameList) {
            userLambdaQueryWrapper = userLambdaQueryWrapper.or().like(Strings.isNotEmpty(tagName), User::getTags, tagName);
        }
        return page(new Page<>(currentPage, PAGE_SIZE), userLambdaQueryWrapper);
    }

    /**
     * 是否为管理员
     *
     * @param loginUser
     * @return
     */
    @Override
    public boolean isAdmin(User loginUser) {
        return loginUser != null && loginUser.getRole() == ADMIN_ROLE;
    }


    @Override
    public boolean updateUser(User user, HttpServletRequest request) {
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = (User) request.getSession().getAttribute(USER_LOGIN_STATE);
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        user.setId(loginUser.getId());
        if (!(isAdmin(loginUser) || loginUser.getId().equals(user.getId()))) {
            throw new BusinessException(ErrorCode.NO_AUTH);
        }
        return updateById(user);
    }

    @Override
    public Page<UserVO> userPage(long currentPage) {
        Page<User> page = this.page(new Page<>(currentPage, PAGE_SIZE));
        Page<UserVO> userVOPage = new Page<>();
        BeanUtils.copyProperties(page, userVOPage);
        return userVOPage;
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return null;
        }
        String userStr = stringRedisTemplate.opsForValue().get(LOGIN_USER_KEY + token);
        if (StrUtil.isBlank(userStr)) {
            return null;
        }
        Gson gson = new Gson();
        User user = gson.fromJson(userStr, User.class);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        request.getSession().setAttribute(USER_LOGIN_STATE, user);
        request.getSession().setMaxInactiveInterval(900);
        return user;
    }

    @Override
    public Boolean isLogin(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        if (userObj == null) {
            return false;
        }
        return true;
    }

//    @Override
//    public List<User> matchUsers(long num, User loginUser) {
//        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
//        queryWrapper.select("id", "tags");
//        queryWrapper.isNotNull("tags");
//        List<User> userList = this.list(queryWrapper);
//        String tags = loginUser.getTags();
//        Gson gson = new Gson();
//        List<String> tagList = gson.fromJson(tags, new TypeToken<List<String>>() {
//        }.getType());
//        // 用户列表的下标 => 相似度
//        List<Pair<User, Long>> list = new ArrayList<>();
//        // 依次计算所有用户和当前用户的相似度
//        for (int i = 0; i < userList.size(); i++) {
//            User user = userList.get(i);
//            String userTags = user.getTags();
//            // 无标签或者为当前用户自己
//            if (StringUtils.isBlank(userTags) || Objects.equals(user.getId(), loginUser.getId())) {
//                continue;
//            }
//            List<String> userTagList = gson.fromJson(userTags, new TypeToken<List<String>>() {
//            }.getType());
//            // 计算分数
//            long distance = AlgorithmUtil.minDistance(tagList, userTagList);
//            list.add(new Pair<>(user, distance));
//        }
//        // 按编辑距离由小到大排序
//        List<Pair<User, Long>> topUserPairList = list.stream()
//                .sorted((a, b) -> (int) (a.getValue() - b.getValue()))
//                .limit(num)
//                .collect(Collectors.toList());
//        List<Long> userIdList = topUserPairList.stream().map(pair -> pair.getKey().getId()).collect(Collectors.toList());
//        String idStr = StringUtils.join(userIdList, ",");
//        QueryWrapper<User> userQueryWrapper = new QueryWrapper<>();
//        userQueryWrapper.in("id", userIdList).last("ORDER BY FIELD(id," + idStr + ")");
//        return this.list(userQueryWrapper)
//                .stream()
//                .map(this::getSafetyUser)
//                .collect(Collectors.toList());
//    }

    @Override
    public Page<UserVO> matchUser(long currentPage, User loginUser) {
        String tags = loginUser.getTags();
        if (tags == null) {
            return this.userPage(currentPage);
        }
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("id", "tags");
        List<User> userList = this.list(queryWrapper);
        Gson gson = new Gson();
        List<String> tagList = gson.fromJson(tags, new TypeToken<List<String>>() {
        }.getType());
        // 用户列表的下标 => 相似度
        List<Pair<User, Long>> list = new ArrayList<>();
        // 依次计算所有用户和当前用户的相似度
        for (int i = 0; i < userList.size(); i++) {
            User user = userList.get(i);
            String userTags = user.getTags();
            // 无标签或者为当前用户自己
            if (StringUtils.isBlank(userTags) || Objects.equals(user.getId(), loginUser.getId())) {
                continue;
            }
            List<String> userTagList = gson.fromJson(userTags, new TypeToken<List<String>>() {
            }.getType());
            // 计算分数
            long distance = AlgorithmUtil.minDistance(tagList, userTagList);
            list.add(new Pair<>(user, distance));
        }
        // 按编辑距离由小到大排序
        List<Pair<User, Long>> topUserPairList = list.stream()
                .sorted((a, b) -> (int) (a.getValue() - b.getValue()))
                .collect(Collectors.toList());
        //截取currentPage所需的List
        ArrayList<Pair<User, Long>> finalUserPairList = new ArrayList<>();
        int begin = (int) ((currentPage - 1) * PAGE_SIZE);
        int end = (int) (((currentPage - 1) * PAGE_SIZE) + PAGE_SIZE) - 1;
        if (topUserPairList.size() < end) {
            //剩余数量
            int temp = (int) (topUserPairList.size() - begin);
            if (temp <= 0) {
                return new Page<>();
            }
            for (int i = begin; i <= begin + temp - 1; i++) {
                finalUserPairList.add(topUserPairList.get(i));
            }
        } else {
            for (int i = begin; i < end; i++) {
                finalUserPairList.add(topUserPairList.get(i));
            }
        }
        //获取排列后的UserId
        List<Long> userIdList = finalUserPairList.stream().map(pair -> pair.getKey().getId()).collect(Collectors.toList());
        String idStr = StringUtils.join(userIdList, ",");
        QueryWrapper<User> userQueryWrapper = new QueryWrapper<>();
        userQueryWrapper.in("id", userIdList).last("ORDER BY FIELD(id," + idStr + ")");
        List<UserVO> userVOList = this.list(userQueryWrapper)
                .stream()
                .map((user) -> {
                    UserVO userVO = new UserVO();
                    BeanUtils.copyProperties(user, userVO);
                    LambdaQueryWrapper<Follow> followLambdaQueryWrapper = new LambdaQueryWrapper<>();
                    followLambdaQueryWrapper.eq(Follow::getUserId, loginUser.getId()).eq(Follow::getFollowUserId, userVO.getId());
                    long count = followService.count(followLambdaQueryWrapper);
                    userVO.setIsFollow(count > 0);
                    return userVO;
                })
                .collect(Collectors.toList());
        Page<UserVO> userVOPage = new Page<>();
        userVOPage.setRecords(userVOList);
        userVOPage.setCurrent(currentPage);
        userVOPage.setSize(userVOList.size());
        userVOPage.setTotal(userVOList.size());
        return userVOPage;
    }

    @Override
    public UserVO getUserById(Long userId, Long loginUserId) {
        User user = this.getById(userId);
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        LambdaQueryWrapper<Follow> followLambdaQueryWrapper = new LambdaQueryWrapper<>();
        followLambdaQueryWrapper.eq(Follow::getUserId, loginUserId).eq(Follow::getFollowUserId, userId);
        long count = followService.count(followLambdaQueryWrapper);
        userVO.setIsFollow(count > 0);
        return userVO;
    }

    @Override
    public List<String> getUserTags(Long id) {
        User user = this.getById(id);
        String userTags = user.getTags();
        Gson gson = new Gson();
        return gson.fromJson(userTags, new TypeToken<List<String>>() {
        }.getType());
    }

    @Override
    public void updateTags(List<String> tags, Long userId) {
        User user = new User();
        Gson gson = new Gson();
        String tagsJson = gson.toJson(tags);
        user.setId(userId);
        user.setTags(tagsJson);
        this.updateById(user);
    }

    @Override
    public void updateUserWithCode(UserUpdateRequest updateRequest, Long userId) {
        String key;
        boolean isPhone = false;
        if (StringUtils.isNotBlank(updateRequest.getPhone())) {
            key = USER_UPDATE_PHONE_KEY + updateRequest.getPhone();
            isPhone = true;
        } else {
            key = USER_UPDATE_EMAIL_KEY + updateRequest.getEmail();
        }
        String correctCode = stringRedisTemplate.opsForValue().get(key);
        if (correctCode == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请先发送验证码");
        }
        if (!correctCode.equals(updateRequest.getCode())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误");
        }
        if (isPhone) {
            LambdaQueryWrapper<User> userLambdaQueryWrapper = new LambdaQueryWrapper<>();
            userLambdaQueryWrapper.eq(User::getPhone, updateRequest.getPhone());
            User user = this.getOne(userLambdaQueryWrapper);
            if (user != null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "该手机号已被绑定");
            }
        } else {
            LambdaQueryWrapper<User> userLambdaQueryWrapper = new LambdaQueryWrapper<>();
            userLambdaQueryWrapper.eq(User::getEmail, updateRequest.getEmail());
            User user = this.getOne(userLambdaQueryWrapper);
            if (user != null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "该邮箱已被绑定");
            }
        }
        User user = new User();
        BeanUtils.copyProperties(updateRequest, user);
        user.setId(userId);
        this.updateById(user);
    }

    @Override
    public Page<UserVO> getRandomUser() {
        List<User> randomUser = userMapper.getRandomUser();
        List<UserVO> userVOList = randomUser.stream().map((item) -> {
            UserVO userVO = new UserVO();
            BeanUtils.copyProperties(item, userVO);
            return userVO;
        }).collect(Collectors.toList());
        Page<UserVO> userVOPage = new Page<>();
        userVOPage.setRecords(userVOList);
        return userVOPage;
    }

    @Override
    public void updatePassword(String phone, String code, String password, String confirmPassword) {
        if (!password.equals(confirmPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
        String key = USER_FORGET_PASSWORD_KEY + phone;
        String correctCode = stringRedisTemplate.opsForValue().get(key);
        if (correctCode == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请先获取验证码");
        }
        if (!correctCode.equals(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误");
        }
        LambdaQueryWrapper<User> userLambdaQueryWrapper = new LambdaQueryWrapper<>();
        userLambdaQueryWrapper.eq(User::getPhone, phone);
        User user = this.getOne(userLambdaQueryWrapper);
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + password).getBytes());
        user.setPassword(encryptPassword);
        this.updateById(user);
        stringRedisTemplate.delete(key);
    }

    @Override
    public Page<UserVO> preMatchUser(long currentPage, String username, User loginUser) {
        Gson gson = new Gson();
        if (loginUser != null) {
            String key = USER_RECOMMEND_KEY + loginUser.getId() + ":" + currentPage;
            if (StringUtils.isNotBlank(username)) {
                LambdaQueryWrapper<User> userLambdaQueryWrapper = new LambdaQueryWrapper<>();
                userLambdaQueryWrapper.like(User::getUsername, username);
                Page<User> userPage = this.page(new Page<>(currentPage, PAGE_SIZE), userLambdaQueryWrapper);
                Page<UserVO> userVOPage = new Page<>();
                BeanUtils.copyProperties(userPage, userVOPage);
                List<UserVO> userVOList = userPage.getRecords().stream().map((user) -> this.getUserById(user.getId(), loginUser.getId())).collect(Collectors.toList());
                userVOPage.setRecords(userVOList);
                return userVOPage;
            }
            if (currentPage <= DEFAULT_CACHE_PAGE) {
                Boolean hasKey = stringRedisTemplate.hasKey(key);
                if (Boolean.TRUE.equals(hasKey)) {
                    String userVOPageStr = stringRedisTemplate.opsForValue().get(key);
                    return gson.fromJson(userVOPageStr, new TypeToken<Page<UserVO>>() {
                    }.getType());
                } else {
                    Page<UserVO> userVOPage = this.matchUser(currentPage, loginUser);
                    String userVOPageStr = gson.toJson(userVOPage);
                    stringRedisTemplate.opsForValue().set(key, userVOPageStr);
                    return userVOPage;
                }
            } else {
                Page<UserVO> userVOPage = this.matchUser(currentPage, loginUser);
                String userVOPageStr = gson.toJson(userVOPage);
                stringRedisTemplate.opsForValue().set(key, userVOPageStr);
                return userVOPage;
            }
        } else {
            if (StringUtils.isNotBlank(username)) {
                throw new BusinessException(ErrorCode.NOT_LOGIN);
            }
            long userNum = this.count();
            if (userNum <= 10) {
                Page<User> userPage = this.page(new Page<>(currentPage, PAGE_SIZE));
                List<UserVO> userVOList = userPage.getRecords().stream().map((user) -> {
                    UserVO userVO = new UserVO();
                    BeanUtils.copyProperties(user, userVO);
                    return userVO;
                }).collect(Collectors.toList());
                Page<UserVO> userVOPage = new Page<>();
                userVOPage.setRecords(userVOList);
                return userVOPage;
            }
            return this.getRandomUser();
        }
    }

    private void checkRegisterRequest(String phone, String code, String account, String password, String checkPassword) {
        if (StringUtils.isAnyBlank(phone, code, account, password, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "信息不全");
        }
        if (StringUtils.isAnyBlank(phone, code, account, password, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (account.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if (password.length() < 8 || checkPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
    }

    private void checkHasRegistered(String phone) {
        LambdaQueryWrapper<User> userLambdaQueryWrapper = new LambdaQueryWrapper<>();
        userLambdaQueryWrapper.eq(User::getPhone, phone);
        long phoneNum = this.count(userLambdaQueryWrapper);
        if (phoneNum >= 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该手机号已注册");
        }
    }

    private void checkCode(String code, String key) {
        Boolean hasKey = stringRedisTemplate.hasKey(key);
        if (Boolean.FALSE.equals(hasKey)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "请先获取验证码");
        }
        String correctCode = stringRedisTemplate.opsForValue().get(key);
        if (correctCode == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
        if (!correctCode.equals(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误");
        }
    }

    private void checkAccountValid(String account) {
        String validPattern = "[`~!@#$%^&*()+=|{}':;',\\\\[\\\\].<>/?~！@#￥%……&*（）——+|{}【】‘；：”“’。，、？]";
        Matcher matcher = Pattern.compile(validPattern).matcher(account);
        if (matcher.find()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户名包含特殊字符");
        }
    }

    private void checkPassword(String password, String checkPassword) {
        if (!password.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
    }

    private void checkAccountRepetition(String account) {
        LambdaQueryWrapper<User> userLambdaQueryWrapper = new LambdaQueryWrapper<>();
        userLambdaQueryWrapper.eq(User::getUserAccount, account);
        long count = this.count(userLambdaQueryWrapper);
        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
        }
    }

    private long insetUser(String phone, String account, String password) {
        // 2. 加密
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + password).getBytes());
        // 3. 插入数据
        User user = new User();
        Random random = new Random();
        user.setAvatarUrl(avatarUrls[random.nextInt(avatarUrls.length)]);
        user.setPhone(phone);
        user.setUsername(account);
        user.setUserAccount(account);
        user.setPassword(encryptPassword);
        ArrayList<String> tag = new ArrayList<>();
        Gson gson = new Gson();
        String jsonTag = gson.toJson(tag);
        user.setTags(jsonTag);
        boolean saveResult = this.save(user);
        if (!saveResult) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
        return user.getId();
    }

    @Override
    public String afterInsertUser(String key, long userId, HttpServletRequest request) {
        stringRedisTemplate.delete(key);
        User userInDatabase = this.getById(userId);
        User safetyUser = this.getSafetyUser(userInDatabase);
        String token = UUID.randomUUID().toString(true);
        Gson gson = new Gson();
        String userStr = gson.toJson(safetyUser);
        request.getSession().setAttribute(USER_LOGIN_STATE, safetyUser);
        request.getSession().setMaxInactiveInterval(900);
        stringRedisTemplate.opsForValue().set(LOGIN_USER_KEY + token, userStr);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, Duration.ofMinutes(15));
        return token;
    }



    @Deprecated
    private List<User> searchByMemory(List<String> tagNameList) {
        List<User> userList = userMapper.selectList(null);
        Gson gson = new Gson();
        return userList.stream().filter(user -> {
            String tags = user.getTags();
            Set<String> tempTagNameSet = gson.fromJson(tags, new TypeToken<Set<String>>() {
            }.getType());
            tempTagNameSet = Optional.ofNullable(tempTagNameSet).orElse(new HashSet<>());
            for (String tagName : tagNameList) {
                if (!tempTagNameSet.contains(tagName)) {
                    return false;
                }
            }
            return true;
        }).map(this::getSafetyUser).collect(Collectors.toList());
    }
}




