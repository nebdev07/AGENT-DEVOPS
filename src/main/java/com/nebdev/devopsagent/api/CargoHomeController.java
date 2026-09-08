package com.nebdev.devopsagent.api;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Profile("cargo")
public class CargoHomeController {

    @GetMapping({"/", "/index.html"})
    public String home() {
        return "forward:/cargo.html";
    }
}
