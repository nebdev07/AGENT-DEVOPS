package com.nebdev.devopsagent.saas.organization;

import com.nebdev.devopsagent.saas.shared.SaasProperties;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/public")
@Tag(name = "public")
public class PublicSiteController {

    private final SaasProperties properties;

    public PublicSiteController(SaasProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/site-config")
    public Map<String, Object> siteConfig() {
        return Map.of("signupRequiresApproval", properties.getAuth().isSignupRequiresApproval());
    }
}
