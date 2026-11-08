package com.nebdev.devopsagent.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;

/** Outil one-shot : remet le MDP défaut du fondateur (mustChange=true). */
public final class ResetFounderPassword {
    public static void main(String[] args) throws Exception {
        String stateDir = args.length > 0 ? args[0] : "./state";
        String email = args.length > 1 ? args[1] : "nebdev07@gmail.com";
        String raw = args.length > 2 ? args[2] : "VpsAutopilot!ChangeMe";
        DevopsProperties props = new DevopsProperties();
        props.setStateDir(stateDir);
        AccountStore accounts = new AccountStore(props, new ObjectMapper());
        var found = accounts.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Compte introuvable: " + email));
        accounts.setPassword(found.id(), raw, true);
        System.out.println("OK " + email + " mustChangePassword=true");
    }
}
