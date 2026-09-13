package com.adarosatas.vocabtrim.security;

import com.adarosatas.vocabtrim.auth.dto.UserView;
import com.adarosatas.vocabtrim.common.api.ApiError;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Configuration
public class SecurityConfig {
    //##注入功能：把Java对象转换成JSON写进 HTTP响应
    private final JsonMapper objectMapper;

    public SecurityConfig(JsonMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    //##工具函数：把Java对象，按照指定 HTTP 状态码，写成JSON响应返回给前端
    private void writeJson(
        HttpServletResponse response,  //响应对象，空白信纸
        int status,                    //HTTP状态码
        Object body                    //待返回的Java对象
    ) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    //##密码哈希算法
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    //##安全过滤器链
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) //参数是由SS提供的配置器
        throws Exception {
        CookieCsrfTokenRepository csrfRepository =
            CookieCsrfTokenRepository.withHttpOnlyFalse(); //创建对象并设置属性：非HttpOnly
        csrfRepository.setCookiePath("/"); //再设置属性访问路径范围：所有

        http
            //###配置：CSRF防护就用刚刚创建的这个东西
            .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))


            //###配置：哪些请求要先登录才能访问
            .authorizeHttpRequests(auth -> auth
            //requestMatchers(...).permitAll()：对于这些HTTP请求，不要求认证

                //公开：/me
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/auth/me"
                ).permitAll()

                //公开：登录注册功能
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/auth/register",
                    "/api/v1/auth/login"
                ).permitAll()

                //其他的都要先认证
                .anyRequest().authenticated()
            )

            //###配置：关掉传统登录跳转缓存，前后端分离 API不需要
            .requestCache(cache -> cache.disable())

            //###配置：登入规则
            .formLogin(form -> form //登录请求不是 JSON
                //登入处理地址
                .loginProcessingUrl("/api/v1/auth/login")

                //登入成功后
                .successHandler((request, response, authentication) -> {
                    //取出用户主体信息
                    VocabTrimPrincipal principal =
                        (VocabTrimPrincipal) authentication.getPrincipal();
                    //返回信息
                    writeJson(
                        response,
                        HttpServletResponse.SC_OK,
                        Map.of(
                            "user",
                            new UserView(principal.getId(), principal.getUsername())
                        )
                    );
                })

                //登入失败后
                .failureHandler((request, response, exception) ->
                    writeJson(
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        ApiError.of("BAD_CREDENTIALS", "用户名或密码错误")
                    )
                )
            )

            //###配置：登出清理规则
            .logout(logout -> logout
                .logoutUrl("/api/v1/auth/logout")
                .invalidateHttpSession(true)                     //清Session
                .clearAuthentication(true)                       //清认证状态
                .deleteCookies("VOCABTRIM_SESSION", "XSRF-TOKEN")//清 Session Cookie 和 CSRF Cookie
                //返回信息
                .logoutSuccessHandler((request, response, authentication) ->
                    writeJson(
                        response,
                        HttpServletResponse.SC_OK,
                        Map.of("ok", true)
                    )
                )
            )

            //###配置：异常处理
            .exceptionHandling(exceptions -> exceptions
                //没登入就访问不该访问的
                .authenticationEntryPoint((request, response, exception) ->
                    writeJson(
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        ApiError.of("UNAUTHORIZED", "请先登录")
                    )
                )
                //拒绝访问
                .accessDeniedHandler((request, response, exception) ->
                    writeJson(
                        response,
                        HttpServletResponse.SC_FORBIDDEN,
                        ApiError.of("FORBIDDEN", "请求被拒绝，请刷新页面后重试")
                    )
                )
            );

        //###配置完成
        return http.build();
    }
}
