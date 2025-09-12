package com.valley.ShareIt.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/**
 * @author dale
 * @since 2024/12/7
 **/
@Controller
@RequestMapping("")
@Slf4j
public class MainController {
    @GetMapping("")
    public String homePage() {
        return "main.html";
    }
}
