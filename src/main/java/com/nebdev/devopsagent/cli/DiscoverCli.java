package com.nebdev.devopsagent.cli;

import com.nebdev.devopsagent.discovery.DiscoveryService;
import com.nebdev.devopsagent.discovery.DiscoveryService.DiscoveryResult;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(0)
public class DiscoverCli implements ApplicationRunner {

    private final DiscoveryService discoveryService;
    private final ConfigurableApplicationContext context;

    public DiscoverCli(DiscoveryService discoveryService, ConfigurableApplicationContext context) {
        this.discoveryService = discoveryService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!requested(args)) {
            return;
        }
        String fixture = first(args.getOptionValues("fixture"));
        DiscoveryResult result = fixture != null
                ? discoveryService.discoverFixture(fixture)
                : discoveryService.discoverRemote();
        System.out.println(result.report());
        System.out.println("État écrit : " + result.stateFile().toAbsolutePath());
        int code = SpringApplication.exit(context, () -> 0);
        System.exit(code);
    }

    private static boolean requested(ApplicationArguments args) {
        return args.containsOption("discover") || args.getNonOptionArgs().contains("discover");
    }

    private static String first(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }
}
