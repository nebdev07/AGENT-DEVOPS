package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.platform.AccessControl;
import com.nebdev.devopsagent.platform.AccountStore;
import com.nebdev.devopsagent.platform.GrantStore;
import com.nebdev.devopsagent.platform.Permission;
import com.nebdev.devopsagent.platform.PlatformAccessException;
import com.nebdev.devopsagent.platform.PlatformModels;
import com.nebdev.devopsagent.platform.RoleStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/platform")
public class PlatformAdminController {

    private final AccessControl access;
    private final AccountStore accounts;
    private final RoleStore roles;
    private final GrantStore grants;

    public PlatformAdminController(AccessControl access, AccountStore accounts, RoleStore roles, GrantStore grants) {
        this.access = access;
        this.accounts = accounts;
        this.roles = roles;
        this.grants = grants;
    }

    @GetMapping("/permissions")
    public Map<String, Object> permissions() {
        access.requireLogin();
        return Map.of("permissions", Permission.allCodes());
    }

    @GetMapping("/accounts")
    public List<PlatformModels.Account> listAccounts() throws IOException {
        access.require(Permission.ACCOUNT_MANAGE);
        return accounts.list();
    }

    @PostMapping("/accounts")
    public PlatformModels.Account createAccount(@RequestBody AccountRequest body) throws IOException {
        access.require(Permission.ACCOUNT_MANAGE);
        if (body == null) {
            throw new IllegalArgumentException("Corps requis.");
        }
        String roleId = body.roleId() == null || body.roleId().isBlank() ? RoleStore.OPERATOR : body.roleId();
        roles.require(roleId);
        return accounts.create(body.email(), body.displayName(), roleId);
    }

    @PutMapping("/accounts/{id}/role")
    public PlatformModels.Account setRole(@PathVariable("id") String id, @RequestBody RoleBody body) throws IOException {
        access.require(Permission.ACCOUNT_MANAGE);
        roles.require(body.roleId());
        return accounts.setRole(id, body.roleId());
    }

    @PutMapping("/accounts/{id}/active")
    public PlatformModels.Account setActive(@PathVariable("id") String id, @RequestBody ActiveBody body)
            throws IOException {
        access.require(Permission.ACCOUNT_MANAGE);
        return accounts.setActive(id, body.active());
    }

    @GetMapping("/roles")
    public List<PlatformModels.Role> listRoles() throws IOException {
        access.require(Permission.ROLE_MANAGE);
        return roles.list();
    }

    @PutMapping("/roles/{id}/permissions")
    public PlatformModels.Role updateRole(@PathVariable("id") String id, @RequestBody PermissionsBody body)
            throws IOException {
        access.require(Permission.ROLE_MANAGE);
        return roles.updatePermissions(id, body.permissions());
    }

    @GetMapping("/grants")
    public List<PlatformModels.ServerGrant> listGrants() throws IOException {
        access.require(Permission.SERVER_ASSIGN);
        return grants.list();
    }

    @GetMapping("/grants/mine")
    public List<PlatformModels.ServerGrant> myGrants() throws IOException {
        return access.visibleGrants();
    }

    @PostMapping("/grants")
    public PlatformModels.ServerGrant assign(@RequestBody GrantRequest body) throws IOException {
        access.require(Permission.SERVER_ASSIGN);
        accounts.require(body.accountId());
        return grants.assign(body.accountId(), body.host(), body.port() == null ? 22 : body.port(),
                body.user(), body.label());
    }

    @DeleteMapping("/grants/{id}")
    public Map<String, String> revoke(@PathVariable("id") String id) throws IOException {
        access.require(Permission.SERVER_ASSIGN);
        grants.revoke(id);
        return Map.of("status", "ok");
    }

    public record AccountRequest(String email, String displayName, String roleId) {}
    public record RoleBody(String roleId) {}
    public record ActiveBody(boolean active) {}
    public record PermissionsBody(List<String> permissions) {}
    public record GrantRequest(String accountId, String host, Integer port, String user, String label) {}

    @ExceptionHandler(PlatformAccessException.class)
    public ResponseEntity<Map<String, String>> access(PlatformAccessException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> io(IOException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", ex.getMessage()));
    }
}
