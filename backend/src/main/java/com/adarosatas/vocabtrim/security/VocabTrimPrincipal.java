package com.adarosatas.vocabtrim.security;
//SS规范化User.java
import com.adarosatas.vocabtrim.user.User;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

//# 前者接口规范化User.java，后者接口要求擦除密码的方法
public class VocabTrimPrincipal implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    //##字段
    //与User相比：无createdAt、updatedAt。都是final也无需setter。
    private final long id;
    private final String username;
    private String passwordHash; //非final：密码哈希需要在认证成功后清除。
    private final boolean enabled;

    //##构造方法
    public VocabTrimPrincipal(long id, String username, String passwordHash, boolean enabled) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
    }

    //##转化方法
    public static VocabTrimPrincipal from(User user) {
        return new VocabTrimPrincipal(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.isEnabled()
        );
    }

    //##返回字段
    @Override public String getPassword() { return passwordHash; } //名不副实：认证时返回哈希，认证成功后为空
    @Override public String getUsername() { return username; }
    @Override public boolean isEnabled() { return enabled; }
    public long getId() { return id; }

    //##擦除密码
    //SS在认证成功后调用，避免把密码哈希保存进Session。
    @Override public void eraseCredentials() { passwordHash = null; }

    //##不实现：账号过期、账号锁定、密码凭据过期。
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }

    //##权限角色：所有用户统一返回 ROLE_USER
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
