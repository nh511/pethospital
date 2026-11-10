package com.pethospital.identity;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.pethospital.identity.AuthDtos.LoginResult;
import com.pethospital.shared.BusinessException;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WechatAuthService {
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final WechatIdentityAdapter identityAdapter;
    private final WechatOpenIdProtector openIdProtector;
    private final PasswordAuthService passwordAuthService;
    private final PolicyCatalog policies;

    public WechatAuthService(
        ObjectProvider<DataSource> dataSourceProvider,
        WechatIdentityAdapter identityAdapter,
        WechatOpenIdProtector openIdProtector,
        PasswordAuthService passwordAuthService,
        PolicyCatalog policies
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identityAdapter = identityAdapter;
        this.openIdProtector = openIdProtector;
        this.passwordAuthService = passwordAuthService;
        this.policies = policies;
    }

    @Transactional
    public LoginResult login(String code, String privacyPolicyVersion) {
        var policy = policies.current(ConsentPurpose.PRIVACY);
        if (!policy.policyVersion().equals(privacyPolicyVersion)) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "POLICY_VERSION_OUTDATED",
                "隐私政策版本已更新，请重新阅读"
            );
        }
        String openId = identityAdapter.exchange(code).openId();
        String openIdHash = openIdProtector.hash(openId);
        byte[] openIdCipher = openIdProtector.encrypt(openId);
        JdbcTemplate jdbc = jdbc();
        Long roleId = jdbc.query(
            "SELECT id FROM sys_role WHERE code='USER' AND status='ACTIVE'",
            result -> result.next() ? result.getLong(1) : null
        );
        if (roleId == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_CONFIGURATION_ERROR",
                "用户角色尚未初始化"
            );
        }

        long proposedUserId = IdWorker.getId();
        jdbc.update("""
            INSERT INTO sys_user(
                id,username,password_hash,wechat_openid_hash,wechat_openid_cipher,
                display_name,status,token_version,version
            ) VALUES (?,NULL,NULL,?,?,'微信用户','ACTIVE',0,0)
            ON DUPLICATE KEY UPDATE id=sys_user.id
            """, proposedUserId, openIdHash, openIdCipher);
        WechatAccount account = jdbc.query("""
            SELECT id,username,display_name,status,token_version
            FROM sys_user WHERE wechat_openid_hash=? FOR UPDATE
            """, result -> result.next()
                ? new WechatAccount(
                    result.getLong("id"),
                    result.getString("username"),
                    result.getString("display_name"),
                    result.getString("status"),
                    result.getInt("token_version")
                )
                : null,
            openIdHash);
        if (account == null) {
            throw new IllegalStateException("微信账号写入后无法读取");
        }
        if (!"ACTIVE".equals(account.status())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已停用");
        }

        jdbc.update("""
            INSERT IGNORE INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)
            """, IdWorker.getId(), account.id(), roleId);
        if (account.id() == proposedUserId) {
            jdbc.update("""
                INSERT INTO consent_record(
                    id,user_id,purpose,policy_version,action,notice_hash,idempotency_key
                ) VALUES (?,?,'PRIVACY',?,'ACCEPT',?,?)
                """,
                IdWorker.getId(),
                account.id(),
                policy.policyVersion(),
                policy.noticeHash(),
                "wechat-privacy:" + account.id()
            );
        }
        return passwordAuthService.issueMiniSession(
            account.id(),
            account.username(),
            account.displayName(),
            account.tokenVersion()
        );
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_DEPENDENCY_UNAVAILABLE",
                "认证数据源不可用"
            );
        }
        return new JdbcTemplate(dataSource);
    }

    private record WechatAccount(
        long id,
        String username,
        String displayName,
        String status,
        int tokenVersion
    ) {
    }
}
