package org.example.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    @GetMapping("/")
    public String root() {
        return "redirect:/login.html?redirect=/index.html";
    }
}
