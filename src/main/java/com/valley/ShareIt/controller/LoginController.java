package com.valley.ShareIt.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * @author dale
 * @since 2024/12/8
 **/
@Controller
public class LoginController {
    @GetMapping("/login")
    public String showLoginForm() {
        return "login";  // 返回 Thymeleaf 模板 login.html
    }
}
