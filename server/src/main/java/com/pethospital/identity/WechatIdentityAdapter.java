package com.pethospital.identity;

public interface WechatIdentityAdapter {
    WechatIdentity exchange(String code);

    record WechatIdentity(String openId) {
        public WechatIdentity {
            if (openId == null || openId.isBlank() || openId.length() > 128) {
                throw new IllegalArgumentException("微信OpenID为空或超长");
            }
            openId = openId.trim();
        }
    }
}
