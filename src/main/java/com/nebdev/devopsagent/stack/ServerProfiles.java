package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.state.KnownServerStore;
import com.nebdev.devopsagent.state.KnownServerStore.KnownServer;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Profil du serveur visé (Mes serveurs) ; valeurs par défaut pour un serveur jamais réglé. */
@Component
public class ServerProfiles {

    private final KnownServerStore store;

    public ServerProfiles(KnownServerStore store) {
        this.store = store;
    }

    public ServerProfile of(String host) throws IOException {
        if (host == null || host.isBlank()) {
            return ServerProfile.DEFAULT;
        }
        KnownServer s = store.byHost(host);
        return s == null ? ServerProfile.DEFAULT : new ServerProfile(s.httpOnly(), s.publicPort(), s.sharedServer());
    }
}
