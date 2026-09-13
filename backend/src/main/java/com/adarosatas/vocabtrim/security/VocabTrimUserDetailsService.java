package com.adarosatas.vocabtrim.security;
//SS规范化UserMapper.java

import com.adarosatas.vocabtrim.user.User;
import com.adarosatas.vocabtrim.user.UserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class VocabTrimUserDetailsService implements UserDetailsService {
    //构造器注入UserMapper
    private final UserMapper userMapper;
    public VocabTrimUserDetailsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    //重写SS核心方法loadUserByUsername(...)
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.findByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return VocabTrimPrincipal.from(user);
    }
}
