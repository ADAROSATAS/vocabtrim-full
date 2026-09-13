package com.adarosatas.vocabtrim.auth;

import com.adarosatas.vocabtrim.auth.dto.RegisterRequest;
import com.adarosatas.vocabtrim.auth.dto.UserView;
import com.adarosatas.vocabtrim.common.exception.ApiException;
import com.adarosatas.vocabtrim.user.User;
import com.adarosatas.vocabtrim.user.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public AuthService(
        UserMapper userMapper,
        PasswordEncoder passwordEncoder
    ) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    //##注册业务
    @Transactional
    public UserView register(RegisterRequest request) {
        //去掉用户名字符串首尾空白
        String username = request.username().trim();

        //密码不一致
        if (!request.password().equals(request.confirmPassword())) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "PASSWORD_MISMATCH",
                "两次输入的密码不一致"
            );
        }

        //用户名已存在
        if (userMapper.findByUsername(username) != null) {
            throw usernameTaken();
        }

        //新用户作为Java对象
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setEnabled(true);

        //新用户进数据库
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            throw usernameTaken();
        }
        return new UserView(user.getId(), user.getUsername());
    }

    //##用户名重复异常
    private ApiException usernameTaken() {
        return new ApiException(
            HttpStatus.CONFLICT,
            "USERNAME_TAKEN",
            "这个用户名已经被使用"
        );
    }
}
