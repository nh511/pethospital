# 数据库33表关系与查询索引字典

版本：M02-07验收基线；日期：2026-10-29。字段级定义仍以`docs/02-database-design.md`和Flyway V1至V5为准；本文件集中说明表归属、跨表组合校验及主要查询的索引入口。

## ID语义与校验边界

- `doctor_id`、`editor_doctor_id`均引用`doctor_profile.id`，不是`sys_user.id`；医生对应账号通过`doctor_profile.user_id`取得。
- `user_id`、`owner_user_id`、`author_user_id`、`actor_user_id`、`created_by`等账号语义字段引用`sys_user.id`。
- 普通FK负责证明单个目标存在并使用`RESTRICT`保护历史；同一聚合内多个ID是否彼此匹配，由`CrossTableRelationGuard`在业务事务中、完成相关行锁之后、写入之前验证。
- `file_binding.owner_user_id`只用于归属检索和一致性检查，不能单独授予下载权限。文件服务仍须同时校验角色、业务资源归属、文件状态及临床/版本授权。
- 索引列按最左前缀使用。M0207IT从`information_schema.statistics`核对下表33张表均存在代表性主查询索引及准确列顺序。

## 跨表组合关系

| 写入场景 | 数据库FK保证 | 服务级组合校验 |
|---|---|---|
| 用户健康日志 | pet、author账号分别存在 | `pet.owner_user_id = author_user_id` |
| 创建/改期预约 | user、pet、doctor_profile、slot分别存在 | `pet.owner_user_id = user_id`且`doctor_slot.doctor_id = doctor_id` |
| 创建或修订病历 | appointment、pet、doctor_profile分别存在 | `appointment.pet_id/doctor_id = medical_record.pet_id/doctor_id` |
| 创建AI会话 | user、pet分别存在 | `pet.owner_user_id = ai_session.user_id` |
| 切换知识发布版本 | document、version分别存在 | `knowledge_version.document_id = knowledge_document.id` |
| 新增多态附件绑定 | file、owner、creator账号存在，business_type在白名单 | business_id必须命中该类型固定目标，并与按目标解析出的owner一致 |

附件目标固定映射如下，禁止根据请求字符串拼接表名：

| business_type | business_id目标 | 一致owner来源 |
|---|---|---|
| PET | pet.id | pet.owner_user_id |
| HEALTH_LOG | pet_health_log.id | pet_health_log→pet.owner_user_id |
| MEDICAL_DRAFT | medical_record.id | medical_record→appointment.user_id |
| MEDICAL_VERSION | medical_record_version.id | version→record→appointment.user_id |
| KNOWLEDGE_VERSION | knowledge_version.id | version→document.creator_user_id |
| GUIDE | health_guide.id | health_guide.editor_user_id |
| QUALIFICATION | doctor_profile.id | doctor_profile.user_id |
| USER_AVATAR | sys_user.id | 同一sys_user.id |

## 33表字典与主要查询

| # | 表 | 主归属/父关系 | 主要查询 | 索引入口 |
|---:|---|---|---|---|
| 1 | sys_user | 账号根实体 | 用户名登录、状态分页 | `uk_sys_user_username(username)`、`ix_sys_user_status_created(status,created_at)` |
| 2 | sys_role | 角色根实体 | 按角色编码查询 | `uk_sys_role_code(code)` |
| 3 | sys_user_role | user→role | 用户角色集合、角色成员 | `uk_sys_user_role(user_id,role_id)`、`ix_sys_user_role_role(role_id)` |
| 4 | sys_menu | 自引用parent | 菜单树和排序 | `ix_sys_menu_parent_sort(parent_id,sort_no)` |
| 5 | sys_role_menu | role→menu | 角色权限、菜单授权角色 | `uk_sys_role_menu(role_id,menu_id)`、`ix_sys_role_menu_menu(menu_id)` |
| 6 | auth_session | sys_user | 用户有效会话、过期/活跃清理 | `ix_auth_session_user_revoked(user_id,revoked_at)`、`ix_auth_session_expires(expires_at)`、`ix_auth_session_last_seen(last_seen_at)` |
| 7 | doctor_profile | sys_user一对一 | 医生账号、启用医生列表 | `uk_doctor_profile_user(user_id)`、`ix_doctor_profile_status(status)` |
| 8 | file_object | uploader sys_user | 上传者文件、待处理状态扫描 | `ix_file_object_uploader_created(uploader_user_id,created_at)`、`ix_file_object_status_created(status,created_at)` |
| 9 | file_binding | 多态业务目标 | 资源附件、owner附件 | `ix_file_binding_business(business_type,business_id)`、`ix_file_binding_owner(owner_user_id)` |
| 10 | audit_log | actor及多态resource | 资源审计、操作者/动作审计 | `ix_audit_resource_created(resource_type,resource_id,created_at)`、`ix_audit_actor_created(actor_user_id,created_at)`、`ix_audit_action_created_actor(action,created_at,actor_user_id)` |
| 11 | user_notice | recipient sys_user | 用户未读通知 | `ix_user_notice_unread_created(recipient_user_id,read_at,created_at)` |
| 12 | consent_record | sys_user | 用户某用途最新授权事件 | `ix_consent_user_purpose_created(user_id,purpose,created_at,id)` |
| 13 | pet | owner sys_user | 主人宠物列表、物种年龄筛选 | `ix_pet_owner_status(owner_user_id,status)`、`ix_pet_species_birth(species_code,birth_date)` |
| 14 | pet_health_log | pet；author须为owner | 宠物日志时间线 | `ix_pet_health_log_pet_observed(pet_id,observed_at,id)` |
| 15 | doctor_slot | doctor_profile | 医生开放时段、自然日时段 | `ix_doctor_slot_status_start(doctor_id,status,start_at)`、`ix_doctor_slot_date(slot_date)` |
| 16 | appointment | user/pet/doctor/slot组合 | 用户列表、医生工作台、宠物冲突、超时确认 | `ix_appointment_user_created(user_id,created_at,id)`、`ix_appointment_doctor_status_start(doctor_id,status,scheduled_start_at)`、`ix_appointment_pet_status_start(pet_id,status,scheduled_start_at)`、`ix_appointment_status_deadline(status,confirm_deadline_at)` |
| 17 | appointment_status_log | appointment | 状态时间线 | `ix_appointment_status_log_created(appointment_id,created_at,id)` |
| 18 | appointment_change_request | appointment及old/new slot | 单个OPEN申请、过期扫描 | `uk_change_request_open_guard(appointment_id,open_guard)`、`ix_change_request_status_expires(status,expires_at)` |
| 19 | medical_record | appointment一对一；pet/doctor组合一致 | 按预约、宠物、医生查询 | `uk_medical_record_appointment(appointment_id)`、`ix_medical_record_pet_submitted(pet_id,submitted_at)`、`ix_medical_record_doctor_submitted(doctor_id,submitted_at)` |
| 20 | medical_record_version | medical_record | 版本历史、幂等提交 | `uk_medical_record_version_revision(record_id,revision_no)`、`uk_medical_record_version_idempotency(record_id,idempotency_key)` |
| 21 | ai_session | user与其pet | 用户最近会话、宠物会话 | `ix_ai_session_user_last_message(user_id,last_message_at,id)`、`ix_ai_session_pet_created(pet_id,created_at)` |
| 22 | ai_message | ai_session；reply自引用 | 会话序列、待处理lease、角色消息 | `uk_ai_message_session_seq(session_id,seq_no)`、`ix_ai_message_processing_lease(processing_status,lease_until)`、`ix_ai_message_session_role_created(session_id,role,created_at)` |
| 23 | ai_call_log | session/user_message | 调用状态扫描、模型统计 | `ix_ai_call_status_started(status,started_at)`、`ix_ai_call_model_started(model,started_at)`、`ix_ai_call_user_message(user_message_id)` |
| 24 | ai_reference | assistant message及知识版本/分块 | 回答引用顺序、分块反查 | `uk_ai_reference_message_rank(assistant_message_id,rank_no)`、`ix_ai_reference_chunk(chunk_id)` |
| 25 | triage_rule | creator/reviewer sys_user | 启用且已审核的物种规则 | `ix_triage_rule_enabled_review_species(enabled,review_status,species_code)` |
| 26 | triage_event | user message/rule | 消息某代分诊、最终风险统计 | `ix_triage_event_message_generation(user_message_id,generation_no)`、`ix_triage_event_final_risk_created(final_risk_level,created_at)` |
| 27 | knowledge_document | creator；active_version组合归属 | 已发布物种/类别入口、active版本反查 | `ix_knowledge_document_status_species_category(status,species_code,category_code)`、`ix_knowledge_document_active_version(active_version_id)` |
| 28 | knowledge_version | knowledge_document | 文档版本序列 | `uk_knowledge_version_number(document_id,version_no)` |
| 29 | knowledge_chunk | knowledge_version | 版本分块顺序 | `uk_knowledge_chunk_ordinal(knowledge_version_id,ordinal)` |
| 30 | knowledge_index_job | knowledge_version/creator | 待领取lease、版本profile任务 | `ix_knowledge_index_status_lease(status,lease_until)`、`uk_knowledge_index_active(knowledge_version_id,embedding_profile,active_guard)` |
| 31 | health_guide | editor sys_user | 发布列表、物种类别 | `ix_health_guide_status_published(status,published_at)`、`ix_health_guide_species_category(species_code,category_code)` |
| 32 | health_standard | editor doctor_profile | 发布的物种指标标准 | `ix_health_standard_status_species_metric(status,species_code,metric_code)` |
| 33 | guide_view_event | guide/user | 日期统计、用户日去重 | `ix_guide_view_date_guide(view_date,guide_id)`、`uk_guide_view_daily(guide_id,user_id,view_date)` |

## 使用要求

1. 业务服务先按统一锁顺序锁定聚合，再调用关系守卫，最后在同一事务写入；不能先校验、事务外等待后再写入。
2. 新增business_type时必须同时更新DDL白名单、`FileBusinessType`固定SQL、本文映射和真实MySQL反例测试。
3. 新增列表筛选或排序前先核对现有索引最左前缀；需要新索引时只新增前向Flyway迁移，禁止修改已应用V2至V5。
4. 归属校验不代替角色授权。尤其医疗附件、知识来源文件和医生资质必须同时检查角色、业务状态与资源归属。
