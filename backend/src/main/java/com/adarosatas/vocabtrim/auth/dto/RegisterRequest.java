package com.adarosatas.vocabtrim.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$", message = "用户名需为 3-32 位字母、数字或下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 72, message = "密码需为 8-72 位")
        String password,

        @NotBlank(message = "请再次输入密码")
        @Size(min = 8, max = 72, message = "确认密码需为 8-72 位")
        String confirmPassword
) {
}
