import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const mode = process.argv[2] ?? 'check'
const allowedModes = new Set(['generate', 'check'])
const methods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']
const methodOrder = new Map(methods.map((method, index) => [method, index]))
const expectedOperationCount = 158
const implementedOperations = new Set([
  'GET /hospital',
  'POST /auth/user/register',
  'POST /auth/user/login',
  'POST /auth/wechat/login',
  'POST /auth/web/login',
  'GET /me',
  'PATCH /me',
  'GET /me/menus',
  'GET /policies/current',
  'GET /me/consents',
  'POST /me/consents',
  'GET /admin/users',
  'POST /admin/users',
  'GET /admin/users/{userId}',
  'PATCH /admin/users/{userId}',
  'POST /admin/users/{userId}/disable',
  'POST /admin/users/{userId}/enable',
  'POST /admin/users/{userId}/reset-password',
  'GET /admin/users/{userId}/roles',
  'PUT /admin/users/{userId}/roles',
  'GET /admin/roles',
  'POST /admin/roles',
  'PATCH /admin/roles/{roleId}',
  'GET /admin/roles/{roleId}/menus',
  'PUT /admin/roles/{roleId}/menus',
  'GET /admin/menus',
  'POST /admin/menus',
  'PATCH /admin/menus/{menuId}',
  'POST /files',
  'GET /files/{fileId}',
  'POST /files/{fileId}/download-ticket',
  'POST /files/{fileId}/discard',
  'GET /notices',
  'POST /notices/{noticeId}/read',
  'GET /admin/audit-logs',
  'GET /admin/ai/call-logs',
  'GET /pets',
  'POST /pets',
  'GET /pets/{petId}',
  'PATCH /pets/{petId}',
  'POST /pets/{petId}/archive',
  'GET /pets/{petId}/health-logs',
  'POST /pets/{petId}/health-logs',
  'GET /pets/{petId}/health-logs/{logId}',
  'PATCH /pets/{petId}/health-logs/{logId}',
  'GET /pets/{petId}/timeline',
  'GET /admin/pets',
  'GET /admin/pets/{petId}',
  'PATCH /admin/pets/{petId}',
  'GET /admin/pets/{petId}/health-logs',
  'GET /admin/doctors',
  'POST /admin/doctors',
  'PATCH /admin/doctors/{doctorId}',
  'POST /admin/doctors/{doctorId}/disable',
  'POST /admin/doctors/{doctorId}/enable',
  'GET /staff/doctor-profile',
  'GET /staff/dashboard',
  'GET /staff/pets',
  'GET /staff/pets/{petId}',
  'GET /staff/pets/{petId}/health-logs',
  'POST /appointments',
  'GET /appointments',
  'GET /appointments/{appointmentId}',
  'GET /staff/appointments',
  'GET /staff/appointments/{appointmentId}',
  'GET /staff/appointments/{appointmentId}/status-logs',
  'POST /staff/appointments/{appointmentId}/confirm',
  'POST /staff/appointments/{appointmentId}/reject',
  'POST /appointments/{appointmentId}/cancel',
  'POST /staff/appointments/{appointmentId}/cancel',
  'POST /staff/appointments/{appointmentId}/start',
  'POST /staff/appointments/{appointmentId}/no-show',
  'GET /appointments/{appointmentId}/change-requests',
  'POST /appointments/{appointmentId}/change-requests',
  'GET /staff/appointments/{appointmentId}/change-requests',
  'POST /staff/appointments/{appointmentId}/change-requests',
  'POST /change-requests/{changeRequestId}/accept',
  'POST /change-requests/{changeRequestId}/reject',
  'POST /change-requests/{changeRequestId}/withdraw',
  'GET /staff/appointments/{appointmentId}/medical-draft',
  'PUT /staff/appointments/{appointmentId}/medical-draft',
  'POST /staff/appointments/{appointmentId}/complete',
  'POST /staff/medical-records/{recordId}/revisions',
  'GET /pets/{petId}/medical-records',
  'GET /medical-records/{recordId}',
  'GET /medical-records/{recordId}/versions',
  'GET /staff/pets/{petId}/medical-records',
  'PATCH /staff/doctor-profile',
  'GET /doctors',
  'GET /doctors/{doctorId}',
  'GET /doctors/{doctorId}/slots',
  'GET /staff/slots',
  'POST /staff/slots',
  'PATCH /staff/slots/{slotId}',
  'POST /staff/slots/{slotId}/close',
  'POST /staff/slots/{slotId}/open',
  'GET /staff/health/guides',
  'POST /staff/health/guides',
  'GET /staff/health/guides/{guideId}',
  'PATCH /staff/health/guides/{guideId}',
  'POST /staff/health/guides/{guideId}/publish',
  'POST /staff/health/guides/{guideId}/offline',
  'GET /health/guides',
  'GET /health/guides/{guideId}',
  'GET /staff/health/standards',
  'POST /staff/health/standards',
  'GET /staff/health/standards/{standardId}',
  'PATCH /staff/health/standards/{standardId}',
  'POST /staff/health/standards/{standardId}/publish',
  'POST /staff/health/standards/{standardId}/offline',
  'GET /health/standards',
  'GET /staff/triage-rules',
  'GET /staff/knowledge/documents',
  'POST /staff/knowledge/documents',
  'GET /staff/knowledge/documents/{documentId}',
  'PATCH /staff/knowledge/documents/{documentId}',
  'GET /staff/knowledge/documents/{documentId}/versions',
  'POST /staff/knowledge/documents/{documentId}/versions',
  'GET /staff/knowledge/versions/{knowledgeVersionId}',
  'PATCH /staff/knowledge/versions/{knowledgeVersionId}',
  'POST /staff/knowledge/versions/{knowledgeVersionId}/submit-review',
  'POST /staff/knowledge/versions/{knowledgeVersionId}/review',
  'POST /staff/triage-rules',
  'GET /staff/triage-rules/{ruleId}',
  'PATCH /staff/triage-rules/{ruleId}',
  'POST /staff/triage-rules/{ruleId}/review',
  'POST /staff/triage-rules/{ruleId}/submit-review',
  'POST /staff/triage-rules/{ruleId}/disable',
  'POST /staff/triage-rules/{ruleId}/enable',
  'POST /staff/triage-rules/test',
])

if (!allowedModes.has(mode)) {
  throw new Error('Usage: node tools/openapi-contract.mjs <generate|check>')
}

const readText = (relativePath) => fs.readFileSync(path.join(root, relativePath), 'utf8').replace(/^\uFEFF/, '')
const cleanCell = (value) => value.trim().replace(/^`|`$/g, '')
const operationKey = (method, apiPath) => `${method.toUpperCase()} ${apiPath}`

function parseDesignOperations() {
  const operations = []
  for (const line of readText('docs/03-api-design.md').split(/\r?\n/)) {
    if (!line.startsWith('|')) continue
    const cells = line.split('|').slice(1, -1).map(cleanCell)
    if (cells.length < 4) continue
    const rowMethods = cells[0].split('/')
    if (!rowMethods.every((method) => methods.includes(method)) || !cells[1].startsWith('/')) continue
    for (const method of rowMethods) {
      operations.push({
        method,
        path: cells[1],
        permission: cells[2],
        detail: cells.slice(3).join(' | '),
      })
    }
  }
  return operations
}

function parseOwnershipOperations() {
  const taskDirectory = path.join(root, 'doc', 'tasks')
  const operations = []
  for (const file of fs.readdirSync(taskDirectory).filter((name) => name.endsWith('.md')).sort()) {
    const text = fs.readFileSync(path.join(taskDirectory, file), 'utf8').replace(/^\uFEFF/, '')
    for (const line of text.split(/\r?\n/)) {
      const match = line.match(/^\|\s*(GET|POST|PUT|PATCH|DELETE)\s*\|\s*`([^`]+)`\s*\|\s*\[([^\]]+)]/)
      if (!match) continue
      operations.push({ method: match[1], path: match[2], ownerTask: match[3], ownerFile: file })
    }
  }
  return operations
}

function assertExactCoverage(design, ownership) {
  const assertUnique = (name, values) => {
    const keys = values.map((entry) => operationKey(entry.method, entry.path))
    const duplicates = keys.filter((key, index) => keys.indexOf(key) !== index)
    assert.equal(values.length, expectedOperationCount, `${name} must contain ${expectedOperationCount} operations`)
    assert.deepEqual([...new Set(duplicates)], [], `${name} contains duplicate method+path entries`)
    return new Set(keys)
  }
  const designKeys = assertUnique('API design', design)
  const ownerKeys = assertUnique('API responsibility registry', ownership)
  assert.deepEqual([...designKeys].sort(), [...ownerKeys].sort(), 'API design and ownership registry differ')
}

const ref = (name) => ({ $ref: `#/components/schemas/${name}` })
const responseRef = (name) => ({ $ref: `#/components/responses/${name}` })
const parameterRef = (name) => ({ $ref: `#/components/parameters/${name}` })

function schemas() {
  const medicalRecordProperties = {
    chiefComplaint: { type: 'string', minLength: 1, maxLength: 10000 },
    examination: { type: 'string', minLength: 1, maxLength: 10000 },
    diagnosis: { type: 'string', minLength: 1, maxLength: 10000 },
    treatment: { type: 'string', minLength: 1, maxLength: 10000 },
    medications: { type: 'array', maxItems: 50, items: ref('MedicalMedication') },
    allergies: { type: 'array', maxItems: 50, items: { type: 'string', minLength: 1, maxLength: 256 } },
    chronicConditions: { type: 'array', maxItems: 50, items: { type: 'string', minLength: 1, maxLength: 256 } },
    followupAdvice: { type: 'string', maxLength: 10000, nullable: true },
    followupAt: { type: 'string', format: 'date-time', nullable: true },
  }
  const strictObject = (properties) => ({
    type: 'object', additionalProperties: false, required: Object.keys(properties), properties,
  })
  const medicalReadContent = strictObject({
    medicalRecord: ref('MedicalRecordView'), attachmentIds: { type: 'array', items: ref('Id') },
  })
  const medicalSummary = {
    id: ref('Id'), appointmentId: ref('Id'), petId: ref('Id'), doctorId: ref('Id'), doctorName: { type: 'string' },
    status: { type: 'string', enum: ['SUBMITTED'] }, submittedAt: ref('DateTime'),
    version: { type: 'integer', minimum: 0 }, revisionNo: { type: 'integer', minimum: 1 },
  }
  const paged = (item) => strictObject({
    items: { type: 'array', items: ref(item) }, page: { type: 'integer', minimum: 1 },
    pageSize: { type: 'integer', minimum: 1, maximum: 100 }, total: { type: 'integer', format: 'int64', minimum: 0 },
  })
  const success = (name) => strictObject({
    code: { type: 'string', enum: ['OK'] }, message: { type: 'string' }, data: ref(name), traceId: { type: 'string' },
  })
  const knowledgeDocumentFields = {
    title: { type: 'string', minLength: 1, maxLength: 255 },
    speciesCode: { type: 'string', pattern: '^[A-Za-z0-9_]{2,32}$' },
    categoryCode: { type: 'string', pattern: '^[A-Za-z0-9_]{2,64}$' },
    tags: { type: 'array', maxItems: 20, items: { type: 'string', minLength: 1, maxLength: 64 } },
  }
  const knowledgeContent = { type: 'string', minLength: 1, maxLength: 100000 }
  const knowledgeVersionFields = {
    content: knowledgeContent,
    sourceTitle: { type: 'string', minLength: 1, maxLength: 255 },
    sourceUrl: { type: 'string', maxLength: 1000, nullable: true, description: '来源 URL 使用 HTTPS。' },
    sourceFileId: { type: 'string', pattern: '^[1-9][0-9]*$', nullable: true },
  }
  const nullableId = { type: 'string', pattern: '^-?[0-9]+$', nullable: true }
  const nullableTime = { type: 'string', format: 'date-time', nullable: true }
  const rowVersion = { type: 'integer', minimum: 0 }
  const knowledgeVersionNumber = { type: 'integer', minimum: 1 }
  return {
    Id: {
      type: 'string',
      pattern: '^-?[0-9]+$',
      description: 'Decimal BIGINT identifier serialized as a string.',
      example: '10001',
    },
    LocalDate: { type: 'string', format: 'date', example: '2026-10-23' },
    DateTime: { type: 'string', format: 'date-time', example: '2026-10-23T09:00:00+08:00' },
    ErrorResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', example: 'SLOT_FULL' },
        message: { type: 'string', example: '该时段已约满，请选择其他时段' },
        data: { type: 'object', nullable: true, additionalProperties: true, example: null },
        traceId: { type: 'string', example: 'trace-generated-by-server' },
      },
      example: {
        code: 'SLOT_FULL',
        message: '该时段已约满，请选择其他时段',
        data: null,
        traceId: 'trace-generated-by-server',
      },
    },
    SuccessResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'], example: 'OK' },
        message: { type: 'string', example: '成功' },
        data: { type: 'object', nullable: true, additionalProperties: true },
        traceId: { type: 'string', example: 'trace-generated-by-server' },
      },
    },
    PageData: {
      type: 'object',
      additionalProperties: false,
      required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', items: { type: 'object', additionalProperties: true } },
        page: { type: 'integer', minimum: 1, example: 1 },
        pageSize: { type: 'integer', minimum: 1, maximum: 100, example: 20 },
        total: { type: 'integer', minimum: 0, example: 0 },
      },
    },
    PageResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: ref('PageData'),
        traceId: { type: 'string' },
      },
    },
    GenericCommand: {
      type: 'object',
      description: 'Fields are constrained by the operation description until its owning business task adds a dedicated DTO.',
      additionalProperties: true,
    },
    RegisterInput: {
      type: 'object',
      additionalProperties: false,
      required: ['username', 'password', 'displayName', 'privacyPolicyVersion'],
      properties: {
        username: { type: 'string', minLength: 4, maxLength: 64, pattern: '^[A-Za-z0-9._-]+$' },
        password: { type: 'string', minLength: 8, maxLength: 72, format: 'password' },
        displayName: { type: 'string', minLength: 1, maxLength: 64 },
        privacyPolicyVersion: { type: 'string', minLength: 1, maxLength: 32 },
      },
      example: {
        username: 'pet_owner_01',
        password: 'example-password',
        displayName: '宠物主人',
        privacyPolicyVersion: '2026-10',
      },
    },
    LoginInput: {
      type: 'object',
      additionalProperties: false,
      required: ['username', 'password'],
      properties: {
        username: { type: 'string', minLength: 1, maxLength: 64 },
        password: { type: 'string', minLength: 1, maxLength: 72, format: 'password' },
      },
      example: { username: 'pet_owner_01', password: 'example-password' },
    },
    WechatLoginInput: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'privacyPolicyVersion'],
      properties: {
        code: { type: 'string', minLength: 1, maxLength: 256 },
        privacyPolicyVersion: { type: 'string', minLength: 1, maxLength: 32 },
      },
      example: { code: 'temporary-code-from-wx-login', privacyPolicyVersion: '2026-10' },
    },
    VersionCommand: {
      type: 'object',
      additionalProperties: true,
      required: ['version'],
      properties: { version: { type: 'integer', minimum: 0, example: 0 } },
      example: { version: 0 },
    },
    ReasonVersionCommand: {
      type: 'object',
      additionalProperties: true,
      required: ['version'],
      properties: {
        version: { type: 'integer', minimum: 0, example: 0 },
        reason: { type: 'string', minLength: 1, maxLength: 500, example: '演示操作原因' },
      },
      example: { version: 0, reason: '演示操作原因' },
    },
    UserRolesCommand: {
      type: 'object',
      additionalProperties: false,
      required: ['roleIds', 'userVersion'],
      properties: {
        roleIds: { type: 'array', uniqueItems: true, items: ref('Id'), example: ['101', '102'] },
        userVersion: { type: 'integer', minimum: 0, example: 0 },
      },
    },
    RoleMenusCommand: {
      type: 'object',
      additionalProperties: false,
      required: ['menuIds', 'roleVersion'],
      properties: {
        menuIds: { type: 'array', uniqueItems: true, items: ref('Id'), example: ['201', '202'] },
        roleVersion: { type: 'integer', minimum: 0, example: 0 },
      },
    },
    HospitalView: {
      type: 'object',
      additionalProperties: false,
      required: ['name', 'address', 'phone', 'businessHours'],
      properties: {
        name: { type: 'string', example: '宠物健康中心' },
        address: { type: 'string', example: '示例市示例路1号' },
        phone: { type: 'string', example: '010-00000000' },
        businessHours: { type: 'string', example: '09:00-18:00' },
      },
    },
    HospitalResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: ref('HospitalView'),
        traceId: { type: 'string' },
      },
      example: {
        code: 'OK',
        message: '成功',
        data: {
          name: '宠物健康中心',
          address: '示例市示例路1号',
          phone: '010-00000000',
          businessHours: '09:00-18:00',
        },
        traceId: 'trace-generated-by-server',
      },
    },
    UserView: {
      type: 'object',
      additionalProperties: false,
      required: ['id', 'username', 'displayName', 'roles'],
      properties: {
        id: ref('Id'),
        username: { type: 'string', nullable: true },
        displayName: { type: 'string' },
        roles: { type: 'array', items: { type: 'string' } },
      },
    },
    TokenBundle: {
      type: 'object',
      additionalProperties: false,
      required: ['accessToken', 'expiresIn', 'sessionId', 'user'],
      properties: {
        accessToken: { type: 'string' },
        refreshToken: { type: 'string' },
        expiresIn: { type: 'integer', minimum: 1 },
        sessionId: ref('Id'),
        user: ref('UserView'),
      },
    },
    UserResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: ref('UserView'),
        traceId: { type: 'string' },
      },
      example: {
        code: 'OK',
        message: '成功',
        data: { id: '10001', username: 'pet_owner_01', displayName: '宠物主人', roles: ['USER'] },
        traceId: 'trace-generated-by-server',
      },
    },
    TokenBundleResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: ref('TokenBundle'),
        traceId: { type: 'string' },
      },
      example: {
        code: 'OK',
        message: '成功',
        data: {
          accessToken: 'signed.rs256.jwt',
          refreshToken: 'mini-refresh-token',
          expiresIn: 1800,
          sessionId: '20001',
          user: { id: '10001', username: 'pet_owner_01', displayName: '宠物主人', roles: ['USER'] },
        },
        traceId: 'trace-generated-by-server',
      },
    },
    ProfilePatchInput: {
      type: 'object',
      additionalProperties: false,
      required: ['version'],
      properties: {
        displayName: { type: 'string', minLength: 1, maxLength: 64 },
        avatarFileId: { type: 'string', pattern: '^[1-9][0-9]*$', example: '10001' },
        phone: { type: 'string', minLength: 7, maxLength: 32 },
        version: { type: 'integer', minimum: 0, example: 0 },
      },
      example: { displayName: '宠物主人', phone: '13800138000', version: 0 },
    },
    UserSelfView: {
      type: 'object',
      additionalProperties: false,
      required: ['id', 'displayName', 'phoneMasked', 'avatarFileId', 'roles', 'permissions', 'version'],
      properties: {
        id: ref('Id'),
        displayName: { type: 'string' },
        phoneMasked: { type: 'string', nullable: true },
        avatarFileId: { ...ref('Id'), nullable: true },
        roles: { type: 'array', items: { type: 'string' } },
        permissions: { type: 'array', items: { type: 'string' } },
        version: { type: 'integer', minimum: 0 },
      },
    },
    UserSelfResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: ref('UserSelfView'),
        traceId: { type: 'string' },
      },
    },
    MenuView: {
      type: 'object',
      additionalProperties: false,
      required: ['id', 'name', 'routePath', 'permissionCode', 'sortNo', 'children'],
      properties: {
        id: ref('Id'),
        name: { type: 'string' },
        routePath: { type: 'string', nullable: true },
        permissionCode: { type: 'string' },
        sortNo: { type: 'integer', minimum: 0 },
        children: { type: 'array', items: ref('MenuView') },
      },
    },
    MenuTreeResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: { type: 'array', items: ref('MenuView') },
        traceId: { type: 'string' },
      },
    },
    AdminUserCreateInput: {
      type: 'object',
      additionalProperties: false,
      required: ['username', 'initialPassword', 'displayName', 'roleCode'],
      properties: {
        username: { type: 'string', minLength: 4, maxLength: 64, pattern: '^[A-Za-z0-9._-]+$' },
        initialPassword: { type: 'string', minLength: 8, maxLength: 72, format: 'password' },
        displayName: { type: 'string', minLength: 1, maxLength: 64 },
        phone: { type: 'string', minLength: 7, maxLength: 32 },
        roleCode: { type: 'string', enum: ['DOCTOR', 'ADMIN'] },
      },
      example: {
        username: 'doctor_01',
        initialPassword: 'temporary-password',
        displayName: '示例医生',
        phone: '13800138000',
        roleCode: 'DOCTOR',
      },
    },
    AdminUserPatchInput: {
      type: 'object',
      additionalProperties: false,
      required: ['version'],
      properties: {
        username: { type: 'string', minLength: 4, maxLength: 64, pattern: '^[A-Za-z0-9._-]+$' },
        displayName: { type: 'string', minLength: 1, maxLength: 64 },
        phone: { type: 'string', minLength: 7, maxLength: 32 },
        version: { type: 'integer', minimum: 0 },
      },
      example: { displayName: '医生新姓名', phone: '13900139000', version: 0 },
    },
    AdminUserView: {
      type: 'object',
      additionalProperties: false,
      required: [
        'id', 'username', 'displayName', 'phoneMasked', 'avatarFileId', 'status',
        'roles', 'version', 'createdAt', 'updatedAt',
      ],
      properties: {
        id: ref('Id'),
        username: { type: 'string', nullable: true },
        displayName: { type: 'string' },
        phoneMasked: { type: 'string', nullable: true },
        avatarFileId: { ...ref('Id'), nullable: true },
        status: { type: 'string', enum: ['ACTIVE', 'DISABLED'] },
        roles: { type: 'array', items: { type: 'string', enum: ['USER', 'DOCTOR', 'ADMIN'] } },
        version: { type: 'integer', minimum: 0 },
        createdAt: ref('DateTime'),
        updatedAt: ref('DateTime'),
      },
    },
    AdminUserPageData: {
      type: 'object',
      additionalProperties: false,
      required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', items: ref('AdminUserView') },
        page: { type: 'integer', minimum: 1 },
        pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    AdminUserResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: ref('AdminUserView'),
        traceId: { type: 'string' },
      },
    },
    AdminUserPageResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: ref('AdminUserPageData'),
        traceId: { type: 'string' },
      },
    },
    AdminUserDisableInput: {
      type: 'object',
      additionalProperties: false,
      required: ['version', 'reason'],
      properties: {
        version: { type: 'integer', minimum: 0 },
        reason: { type: 'string', minLength: 1, maxLength: 500 },
      },
      example: { version: 0, reason: '账号停用审核通过' },
    },
    AdminUserEnableInput: {
      type: 'object',
      additionalProperties: false,
      required: ['version'],
      properties: { version: { type: 'integer', minimum: 0 } },
      example: { version: 1 },
    },
    AdminPasswordResetInput: {
      type: 'object',
      additionalProperties: false,
      required: ['version', 'temporaryPassword', 'reason'],
      properties: {
        version: { type: 'integer', minimum: 0 },
        temporaryPassword: { type: 'string', minLength: 8, maxLength: 72, format: 'password' },
        reason: { type: 'string', minLength: 1, maxLength: 500 },
      },
      example: { version: 0, temporaryPassword: 'temporary-password', reason: '用户申请重置' },
    },
    AdminAccountLifecycleView: {
      type: 'object',
      additionalProperties: false,
      required: ['userId', 'status', 'version'],
      properties: {
        userId: ref('Id'),
        status: { type: 'string', enum: ['ACTIVE', 'DISABLED'] },
        version: { type: 'integer', minimum: 0 },
      },
    },
    AdminAccountLifecycleResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: ref('AdminAccountLifecycleView'),
        traceId: { type: 'string' },
      },
    },
    PolicyDocument: {
      type: 'object',
      additionalProperties: false,
      required: ['purpose', 'policyVersion', 'noticeHash', 'text'],
      properties: {
        purpose: { type: 'string', enum: ['PRIVACY', 'AI_SERVICE'] },
        policyVersion: { type: 'string', minLength: 1, maxLength: 32 },
        noticeHash: { type: 'string', pattern: '^[0-9a-f]{64}$' },
        text: { type: 'string', minLength: 1 },
      },
    },
    PoliciesData: {
      type: 'object',
      additionalProperties: false,
      required: ['items'],
      properties: { items: { type: 'array', minItems: 2, maxItems: 2, items: ref('PolicyDocument') } },
    },
    PoliciesResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('PoliciesData'), traceId: { type: 'string' },
      },
    },
    ConsentCommand: {
      type: 'object',
      additionalProperties: false,
      required: ['purpose', 'policyVersion', 'noticeHash', 'action'],
      properties: {
        purpose: { type: 'string', enum: ['PRIVACY', 'AI_SERVICE'] },
        policyVersion: { type: 'string', minLength: 1, maxLength: 32 },
        noticeHash: { type: 'string', pattern: '^[0-9a-f]{64}$' },
        action: { type: 'string', enum: ['ACCEPT', 'WITHDRAW'] },
      },
      example: {
        purpose: 'AI_SERVICE', policyVersion: '2026-10',
        noticeHash: 'a'.repeat(64), action: 'ACCEPT',
      },
    },
    ConsentStatus: {
      type: 'object',
      additionalProperties: false,
      required: [
        'purpose', 'currentPolicyVersion', 'currentNoticeHash', 'action',
        'policyVersion', 'noticeHash', 'actedAt', 'effective',
      ],
      properties: {
        purpose: { type: 'string', enum: ['PRIVACY', 'AI_SERVICE'] },
        currentPolicyVersion: { type: 'string', minLength: 1, maxLength: 32 },
        currentNoticeHash: { type: 'string', pattern: '^[0-9a-f]{64}$' },
        action: { type: 'string', enum: ['ACCEPT', 'WITHDRAW'], nullable: true },
        policyVersion: { type: 'string', maxLength: 32, nullable: true },
        noticeHash: { type: 'string', pattern: '^[0-9a-f]{64}$', nullable: true },
        actedAt: { allOf: [ref('DateTime')], nullable: true },
        effective: { type: 'boolean' },
      },
    },
    ConsentListData: {
      type: 'object',
      additionalProperties: false,
      required: ['items'],
      properties: { items: { type: 'array', minItems: 2, maxItems: 2, items: ref('ConsentStatus') } },
    },
    ConsentListResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('ConsentListData'), traceId: { type: 'string' },
      },
    },
    ConsentStatusResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('ConsentStatus'), traceId: { type: 'string' },
      },
    },
    RoleCreateInput: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'name'],
      properties: {
        code: { type: 'string', minLength: 2, maxLength: 32, pattern: '^[A-Z][A-Z0-9_]*$' },
        name: { type: 'string', minLength: 1, maxLength: 64 },
      },
      example: { code: 'OPERATIONS', name: '运营管理员' },
    },
    RolePatchInput: {
      type: 'object',
      additionalProperties: false,
      required: ['version'],
      properties: {
        name: { type: 'string', minLength: 1, maxLength: 64 },
        status: { type: 'string', enum: ['ACTIVE', 'DISABLED'] },
        version: { type: 'integer', minimum: 0 },
      },
      example: { name: '运营主管', version: 0 },
    },
    RoleView: {
      type: 'object',
      additionalProperties: false,
      required: ['id', 'code', 'name', 'status', 'version'],
      properties: {
        id: ref('Id'),
        code: { type: 'string' },
        name: { type: 'string' },
        status: { type: 'string', enum: ['ACTIVE', 'DISABLED'] },
        version: { type: 'integer', minimum: 0 },
      },
    },
    RolePageData: {
      type: 'object',
      additionalProperties: false,
      required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', items: ref('RoleView') },
        page: { type: 'integer', minimum: 1 },
        pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    RoleResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('RoleView'), traceId: { type: 'string' },
      },
    },
    RolePageResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('RolePageData'), traceId: { type: 'string' },
      },
    },
    AdminMenuCreateInput: {
      type: 'object',
      additionalProperties: false,
      required: ['name', 'kind', 'permissionCode', 'sortNo'],
      properties: {
        parentId: { type: 'string', pattern: '^[1-9][0-9]*$', nullable: true },
        name: { type: 'string', minLength: 1, maxLength: 64 },
        kind: { type: 'string', enum: ['MENU', 'BUTTON'] },
        routePath: { type: 'string', maxLength: 255, nullable: true },
        permissionCode: { type: 'string', minLength: 1, maxLength: 128 },
        sortNo: { type: 'integer', minimum: 0, maximum: 1000000 },
      },
      example: { name: '账号管理', kind: 'MENU', routePath: '/users', permissionCode: 'admin:users:view', sortNo: 10 },
    },
    AdminMenuPatchInput: {
      type: 'object',
      additionalProperties: false,
      required: ['name', 'kind', 'sortNo', 'status', 'version'],
      properties: {
        parentId: { type: 'string', pattern: '^[1-9][0-9]*$', nullable: true },
        name: { type: 'string', minLength: 1, maxLength: 64 },
        kind: { type: 'string', enum: ['MENU', 'BUTTON'] },
        routePath: { type: 'string', maxLength: 255, nullable: true },
        sortNo: { type: 'integer', minimum: 0, maximum: 1000000 },
        status: { type: 'string', enum: ['ACTIVE', 'DISABLED'] },
        version: { type: 'integer', minimum: 0 },
      },
      example: { parentId: null, name: '账号管理', kind: 'MENU', routePath: '/users', sortNo: 20, status: 'ACTIVE', version: 0 },
    },
    AdminMenuView: {
      type: 'object',
      additionalProperties: false,
      required: ['id', 'parentId', 'name', 'kind', 'routePath', 'permissionCode', 'sortNo', 'status', 'version', 'children'],
      properties: {
        id: ref('Id'), parentId: { type: 'string', pattern: '^[1-9][0-9]*$', nullable: true }, name: { type: 'string' },
        kind: { type: 'string', enum: ['MENU', 'BUTTON'] }, routePath: { type: 'string', nullable: true },
        permissionCode: { type: 'string' }, sortNo: { type: 'integer', minimum: 0 },
        status: { type: 'string', enum: ['ACTIVE', 'DISABLED'] }, version: { type: 'integer', minimum: 0 },
        children: { type: 'array', items: ref('AdminMenuView') },
      },
    },
    AdminMenuResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('AdminMenuView'), traceId: { type: 'string' },
      },
    },
    AdminMenuTreeResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: { type: 'array', items: ref('AdminMenuView') }, traceId: { type: 'string' },
      },
    },
    UserRolesView: {
      type: 'object', additionalProperties: false, required: ['userId', 'roleIds', 'userVersion'],
      properties: {
        userId: ref('Id'), roleIds: { type: 'array', uniqueItems: true, items: ref('Id') },
        userVersion: { type: 'integer', minimum: 0 },
      },
    },
    UserRolesResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('UserRolesView'), traceId: { type: 'string' },
      },
    },
    RoleMenusView: {
      type: 'object', additionalProperties: false, required: ['roleId', 'menuIds', 'roleVersion'],
      properties: {
        roleId: ref('Id'), menuIds: { type: 'array', uniqueItems: true, items: ref('Id') },
        roleVersion: { type: 'integer', minimum: 0 },
      },
    },
    RoleMenusResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('RoleMenusView'), traceId: { type: 'string' },
      },
    },
    PetInput: {
      type: 'object',
      additionalProperties: false,
      required: ['name', 'speciesCode', 'sex', 'neutered'],
      properties: {
        name: { type: 'string', minLength: 1, maxLength: 64 },
        speciesCode: { type: 'string', enum: ['CAT', 'DOG', 'OTHER'] },
        breed: { type: 'string', maxLength: 64 },
        sex: { type: 'string', enum: ['MALE', 'FEMALE', 'UNKNOWN'] },
        birthDate: ref('LocalDate'),
        neutered: { type: 'string', enum: ['YES', 'NO', 'UNKNOWN'] },
        avatarFileId: ref('Id'),
        ownerHealthNotes: { type: 'string', maxLength: 10000 },
      },
    },
    PetView: {
      type: 'object',
      additionalProperties: false,
      required: [
        'id', 'name', 'speciesCode', 'sex', 'neutered', 'status', 'version', 'createdAt', 'updatedAt',
      ],
      properties: {
        id: ref('Id'),
        name: { type: 'string', maxLength: 64 },
        speciesCode: { type: 'string', enum: ['CAT', 'DOG', 'OTHER'] },
        breed: { type: 'string', maxLength: 64, nullable: true },
        sex: { type: 'string', enum: ['MALE', 'FEMALE', 'UNKNOWN'] },
        birthDate: { type: 'string', format: 'date', nullable: true },
        neutered: { type: 'string', enum: ['YES', 'NO', 'UNKNOWN'] },
        avatarFileId: { type: 'string', pattern: '^[1-9][0-9]*$', nullable: true },
        status: { type: 'string', enum: ['ACTIVE', 'ARCHIVED'] },
        version: { type: 'integer', minimum: 0 },
        createdAt: ref('DateTime'),
        updatedAt: ref('DateTime'),
      },
    },
    PetResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('PetView'), traceId: { type: 'string' },
      },
    },
    PetPage: {
      type: 'object', additionalProperties: false, required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', items: ref('PetView') },
        page: { type: 'integer', minimum: 1 },
        pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    PetPageResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('PetPage'), traceId: { type: 'string' },
      },
    },
    PetUpdateInput: {
      type: 'object',
      additionalProperties: false,
      required: ['name', 'speciesCode', 'sex', 'neutered', 'version'],
      properties: {
        name: { type: 'string', minLength: 1, maxLength: 64 },
        speciesCode: { type: 'string', enum: ['CAT', 'DOG', 'OTHER'] },
        breed: { type: 'string', maxLength: 64 },
        sex: { type: 'string', enum: ['MALE', 'FEMALE', 'UNKNOWN'] },
        birthDate: ref('LocalDate'),
        neutered: { type: 'string', enum: ['YES', 'NO', 'UNKNOWN'] },
        avatarFileId: ref('Id'),
        ownerHealthNotes: { type: 'string', maxLength: 10000 },
        version: { type: 'integer', minimum: 0 },
      },
    },
    TimelineType: { type: 'string', enum: ['LOG', 'MEDICAL', 'AI', 'APPOINTMENT'] },
    TimelineItem: {
      type: 'object', additionalProperties: false, required: ['type', 'id', 'occurredAt', 'summary'],
      properties: {
        type: ref('TimelineType'),
        id: ref('Id'),
        occurredAt: { ...ref('DateTime'), description: '日志观察、病历提交、AI会话创建或预约安排时间。' },
        summary: {
          type: 'string', maxLength: 240,
          description: '主人自述节选、已提交病历诊断节选、AI会话标题或预约状态。',
        },
      },
    },
    TimelinePage: {
      type: 'object', additionalProperties: false, required: ['items', 'nextCursor'],
      properties: {
        items: { type: 'array', maxItems: 100, items: ref('TimelineItem') },
        nextCursor: { type: 'string', nullable: true, maxLength: 96, description: '下一页游标，末页为null。' },
      },
    },
    TimelinePageResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('TimelinePage'), traceId: { type: 'string' },
      },
    },
    AdminPetOwner: {
      type: 'object', additionalProperties: false, required: ['id', 'displayNameMasked'],
      properties: {
        id: ref('Id'),
        displayNameMasked: { type: 'string', minLength: 2, maxLength: 64 },
      },
    },
    AdminPetSummary: {
      type: 'object', additionalProperties: false,
      required: [
        'id', 'name', 'speciesCode', 'breed', 'sex', 'birthDate', 'neutered', 'status',
        'version', 'owner', 'createdAt', 'updatedAt',
      ],
      properties: {
        id: ref('Id'), name: { type: 'string', maxLength: 64 },
        speciesCode: { type: 'string', enum: ['CAT', 'DOG', 'OTHER'] },
        breed: { type: 'string', maxLength: 64, nullable: true },
        sex: { type: 'string', enum: ['MALE', 'FEMALE', 'UNKNOWN'] },
        birthDate: { type: 'string', format: 'date', nullable: true },
        neutered: { type: 'string', enum: ['YES', 'NO', 'UNKNOWN'] },
        status: { type: 'string', enum: ['ACTIVE', 'ARCHIVED'] },
        version: { type: 'integer', minimum: 0 }, owner: ref('AdminPetOwner'),
        createdAt: ref('DateTime'), updatedAt: ref('DateTime'),
      },
    },
    AdminPetDetail: {
      type: 'object', additionalProperties: false,
      required: [
        'id', 'name', 'speciesCode', 'breed', 'sex', 'birthDate', 'neutered', 'avatarFileId',
        'status', 'version', 'owner', 'ownerReported', 'createdAt', 'updatedAt',
      ],
      properties: {
        id: ref('Id'), name: { type: 'string', maxLength: 64 },
        speciesCode: { type: 'string', enum: ['CAT', 'DOG', 'OTHER'] },
        breed: { type: 'string', maxLength: 64, nullable: true },
        sex: { type: 'string', enum: ['MALE', 'FEMALE', 'UNKNOWN'] },
        birthDate: { type: 'string', format: 'date', nullable: true },
        neutered: { type: 'string', enum: ['YES', 'NO', 'UNKNOWN'] },
        avatarFileId: { type: 'string', pattern: '^[1-9][0-9]*$', nullable: true },
        status: { type: 'string', enum: ['ACTIVE', 'ARCHIVED'] },
        version: { type: 'integer', minimum: 0 }, owner: ref('AdminPetOwner'),
        ownerReported: ref('PetOwnerReported'), createdAt: ref('DateTime'), updatedAt: ref('DateTime'),
      },
    },
    AdminPetPatchInput: {
      type: 'object', additionalProperties: false,
      required: ['name', 'speciesCode', 'sex', 'neutered', 'version', 'reason'],
      properties: {
        name: { type: 'string', minLength: 1, maxLength: 64 },
        speciesCode: { type: 'string', enum: ['CAT', 'DOG', 'OTHER'] },
        breed: { type: 'string', maxLength: 64, nullable: true },
        sex: { type: 'string', enum: ['MALE', 'FEMALE', 'UNKNOWN'] },
        birthDate: { type: 'string', format: 'date', nullable: true },
        neutered: { type: 'string', enum: ['YES', 'NO', 'UNKNOWN'] },
        ownerHealthNotes: { type: 'string', maxLength: 10000, nullable: true },
        version: { type: 'integer', minimum: 0 },
        reason: { type: 'string', minLength: 1, maxLength: 500 },
      },
    },
    AdminPetPage: {
      type: 'object', additionalProperties: false, required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', items: ref('AdminPetSummary') },
        page: { type: 'integer', minimum: 1 },
        pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    AdminPetResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('AdminPetDetail'), traceId: { type: 'string' },
      },
    },
    AdminPetPageResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('AdminPetPage'), traceId: { type: 'string' },
      },
    },
    PetArchiveInput: {
      type: 'object',
      additionalProperties: false,
      required: ['version'],
      properties: {
        reason: { type: 'string', minLength: 1, maxLength: 500 },
        version: { type: 'integer', minimum: 0 },
      },
    },
    PetArchiveResult: {
      type: 'object',
      additionalProperties: false,
      required: ['petId', 'status', 'version'],
      properties: {
        petId: ref('Id'),
        status: { type: 'string', enum: ['ARCHIVED'] },
        version: { type: 'integer', minimum: 1 },
      },
    },
    PetArchiveResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('PetArchiveResult'), traceId: { type: 'string' },
      },
    },
    HealthLogInput: {
      type: 'object', additionalProperties: false,
      required: ['observedAt', 'attachmentIds'],
      properties: {
        observedAt: ref('DateTime'),
        weightKg: { type: 'number', format: 'double', minimum: 0, exclusiveMinimum: true, nullable: true },
        dietNote: { type: 'string', maxLength: 10000, nullable: true },
        stoolNote: { type: 'string', maxLength: 10000, nullable: true },
        activityNote: { type: 'string', maxLength: 10000, nullable: true },
        symptomNote: { type: 'string', maxLength: 10000, nullable: true },
        attachmentIds: { type: 'array', maxItems: 20, uniqueItems: true, items: ref('Id') },
      },
    },
    HealthLogUpdateInput: {
      type: 'object', additionalProperties: false,
      required: ['observedAt', 'attachmentIds', 'version'],
      properties: {
        observedAt: ref('DateTime'),
        weightKg: { type: 'number', format: 'double', minimum: 0, exclusiveMinimum: true, nullable: true },
        dietNote: { type: 'string', maxLength: 10000, nullable: true },
        stoolNote: { type: 'string', maxLength: 10000, nullable: true },
        activityNote: { type: 'string', maxLength: 10000, nullable: true },
        symptomNote: { type: 'string', maxLength: 10000, nullable: true },
        attachmentIds: { type: 'array', maxItems: 20, uniqueItems: true, items: ref('Id') },
        version: { type: 'integer', minimum: 0 },
      },
    },
    HealthLogView: {
      type: 'object', additionalProperties: false,
      required: [
        'id', 'petId', 'observedAt', 'weightKg', 'dietNote', 'stoolNote', 'activityNote',
        'symptomNote', 'attachmentIds', 'sourceType', 'version', 'createdAt', 'updatedAt',
      ],
      properties: {
        id: ref('Id'), petId: ref('Id'), observedAt: ref('DateTime'),
        weightKg: { type: 'number', format: 'double', nullable: true },
        dietNote: { type: 'string', nullable: true },
        stoolNote: { type: 'string', nullable: true },
        activityNote: { type: 'string', nullable: true },
        symptomNote: { type: 'string', nullable: true },
        attachmentIds: { type: 'array', items: ref('Id') },
        sourceType: { type: 'string', enum: ['OWNER_REPORTED'] },
        version: { type: 'integer', minimum: 0 },
        createdAt: ref('DateTime'), updatedAt: ref('DateTime'),
      },
    },
    HealthLogPage: {
      type: 'object', additionalProperties: false, required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', items: ref('HealthLogView') },
        page: { type: 'integer', minimum: 1 },
        pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    HealthLogResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('HealthLogView'), traceId: { type: 'string' },
      },
    },
    HealthLogPageResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('HealthLogPage'), traceId: { type: 'string' },
      },
    },
    PetOwnerReported: {
      type: 'object', additionalProperties: false, required: ['healthNotes'],
      properties: { healthNotes: { type: 'string', maxLength: 10000, nullable: true } },
    },
    PetWeightSnapshot: {
      type: 'object', additionalProperties: false,
      required: ['weightKg', 'observedAt', 'sourceType'],
      properties: {
        weightKg: { type: 'number', format: 'double', exclusiveMinimum: true, minimum: 0 },
        observedAt: ref('DateTime'),
        sourceType: { type: 'string', enum: ['HEALTH_LOG'] },
      },
    },
    PetMedicationSummary: {
      type: 'object', additionalProperties: false,
      required: ['name', 'dosage', 'frequency', 'duration'],
      properties: {
        name: { type: 'string', nullable: true },
        dosage: { type: 'string', nullable: true },
        frequency: { type: 'string', nullable: true },
        duration: { type: 'string', nullable: true },
      },
    },
    PetSubmittedHistory: {
      type: 'object', additionalProperties: false,
      required: ['allergies', 'chronicConditions', 'medications', 'sourceAt', 'recordId', 'revisionNo'],
      properties: {
        allergies: { type: 'array', items: { type: 'string' } },
        chronicConditions: { type: 'array', items: { type: 'string' } },
        medications: { type: 'array', items: ref('PetMedicationSummary') },
        sourceAt: ref('DateTime'),
        recordId: ref('Id'),
        revisionNo: { type: 'integer', minimum: 1 },
      },
    },
    PetClinicalSummary: {
      type: 'object', additionalProperties: false,
      required: ['latestWeight', 'submittedHistory'],
      properties: {
        latestWeight: { ...ref('PetWeightSnapshot'), nullable: true },
        submittedHistory: { ...ref('PetSubmittedHistory'), nullable: true },
      },
    },
    PetDetail: {
      type: 'object',
      additionalProperties: false,
      required: [
        'id', 'name', 'speciesCode', 'sex', 'neutered', 'status', 'version',
        'createdAt', 'updatedAt', 'ownerReported', 'clinicalSummary',
      ],
      properties: {
        id: ref('Id'),
        name: { type: 'string', maxLength: 64 },
        speciesCode: { type: 'string', enum: ['CAT', 'DOG', 'OTHER'] },
        breed: { type: 'string', maxLength: 64, nullable: true },
        sex: { type: 'string', enum: ['MALE', 'FEMALE', 'UNKNOWN'] },
        birthDate: { type: 'string', format: 'date', nullable: true },
        neutered: { type: 'string', enum: ['YES', 'NO', 'UNKNOWN'] },
        avatarFileId: { type: 'string', pattern: '^[1-9][0-9]*$', nullable: true },
        status: { type: 'string', enum: ['ACTIVE', 'ARCHIVED'] },
        version: { type: 'integer', minimum: 0 },
        createdAt: ref('DateTime'),
        updatedAt: ref('DateTime'),
        ownerReported: ref('PetOwnerReported'),
        clinicalSummary: ref('PetClinicalSummary'),
      },
    },
    PetDetailResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('PetDetail'), traceId: { type: 'string' },
      },
    },
    StaffAppointmentCard: {
      type: 'object', additionalProperties: false,
      required: ['id', 'petId', 'petName', 'status', 'chiefComplaint', 'scheduledStartAt', 'scheduledEndAt'],
      properties: {
        id: ref('Id'),
        petId: { type: 'string', pattern: '^[1-9][0-9]*$', nullable: true, description: '待确认时为null。' },
        petName: { type: 'string', maxLength: 64 },
        status: { type: 'string', enum: ['PENDING', 'CONFIRMED', 'IN_CONSULTATION', 'COMPLETED'] },
        chiefComplaint: { type: 'string', maxLength: 1000 },
        scheduledStartAt: ref('DateTime'),
        scheduledEndAt: ref('DateTime'),
      },
    },
    StaffDashboard: {
      type: 'object', additionalProperties: false,
      required: ['date', 'timezone', 'today', 'pending', 'recentCompleted'],
      properties: {
        date: { type: 'string', format: 'date' },
        timezone: { type: 'string', enum: ['Asia/Shanghai'] },
        today: { type: 'array', maxItems: 20, items: ref('StaffAppointmentCard') },
        pending: { type: 'array', maxItems: 20, items: ref('StaffAppointmentCard') },
        recentCompleted: { type: 'array', maxItems: 20, items: ref('StaffAppointmentCard') },
      },
    },
    StaffDashboardResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('StaffDashboard'), traceId: { type: 'string' },
      },
    },
    StaffPetSummary: {
      type: 'object', additionalProperties: false,
      required: ['id', 'name', 'speciesCode', 'breed', 'sex', 'birthDate', 'neutered', 'status'],
      properties: {
        id: ref('Id'),
        name: { type: 'string', maxLength: 64 },
        speciesCode: { type: 'string', enum: ['CAT', 'DOG', 'OTHER'] },
        breed: { type: 'string', maxLength: 64, nullable: true },
        sex: { type: 'string', enum: ['MALE', 'FEMALE', 'UNKNOWN'] },
        birthDate: { type: 'string', format: 'date', nullable: true },
        neutered: { type: 'string', enum: ['YES', 'NO', 'UNKNOWN'] },
        status: { type: 'string', enum: ['ACTIVE', 'ARCHIVED'] },
      },
    },
    StaffPetDetail: {
      type: 'object', additionalProperties: false, required: ['pet', 'clinicalSummary'],
      properties: { pet: ref('StaffPetSummary'), clinicalSummary: ref('PetClinicalSummary') },
    },
    StaffPetDetailResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('StaffPetDetail'), traceId: { type: 'string' },
      },
    },
    StaffPetPage: {
      type: 'object', additionalProperties: false, required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', maxItems: 100, items: ref('StaffPetSummary') },
        page: { type: 'integer', minimum: 1 },
        pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    StaffPetPageResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('StaffPetPage'), traceId: { type: 'string' },
      },
    },
    AdminDoctorCreateInput: {
      type: 'object', additionalProperties: false, required: ['userId', 'doctorName'],
      properties: {
        userId: ref('Id'), doctorName: { type: 'string', minLength: 1, maxLength: 64 },
        title: { type: 'string', maxLength: 64 }, specialties: { type: 'string', maxLength: 255 },
        introduction: { type: 'string', maxLength: 10000 }, qualificationFileId: ref('Id'),
      },
    },
    AdminDoctorPatchInput: {
      type: 'object', additionalProperties: false, required: ['version'],
      properties: {
        doctorName: { type: 'string', minLength: 1, maxLength: 64 },
        title: { type: 'string', maxLength: 64 }, specialties: { type: 'string', maxLength: 255 },
        introduction: { type: 'string', maxLength: 10000 }, qualificationFileId: ref('Id'),
        version: { type: 'integer', minimum: 0 },
      },
    },
    AdminDoctorDisableInput: {
      type: 'object', additionalProperties: false, required: ['version', 'reason'],
      properties: {
        version: { type: 'integer', minimum: 0 },
        reason: { type: 'string', minLength: 1, maxLength: 500 },
      },
    },
    AdminDoctorEnableInput: {
      type: 'object', additionalProperties: false, required: ['version'],
      properties: { version: { type: 'integer', minimum: 0 } },
    },
    StaffDoctorPatchInput: {
      type: 'object', additionalProperties: false, required: ['version'],
      properties: {
        specialties: { type: 'string', maxLength: 255 },
        introduction: { type: 'string', maxLength: 10000 },
        version: { type: 'integer', minimum: 0 },
      },
    },
    DoctorProfileView: {
      type: 'object', additionalProperties: false,
      required: ['doctorId', 'userId', 'doctorName', 'status', 'version', 'createdAt', 'updatedAt'],
      properties: {
        doctorId: ref('Id'), userId: ref('Id'), doctorName: { type: 'string', maxLength: 64 },
        title: { type: 'string', maxLength: 64, nullable: true },
        specialties: { type: 'string', maxLength: 255, nullable: true },
        introduction: { type: 'string', maxLength: 10000, nullable: true },
        qualificationFileId: { type: 'string', pattern: '^[1-9][0-9]*$', nullable: true },
        status: { type: 'string', enum: ['ACTIVE', 'DISABLED'] },
        version: { type: 'integer', minimum: 0 }, createdAt: ref('DateTime'), updatedAt: ref('DateTime'),
      },
    },
    DoctorProfileResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('DoctorProfileView'), traceId: { type: 'string' },
      },
    },
    DoctorProfilePage: {
      type: 'object', additionalProperties: false, required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', items: ref('DoctorProfileView') },
        page: { type: 'integer', minimum: 1 }, pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    DoctorProfilePageResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('DoctorProfilePage'), traceId: { type: 'string' },
      },
    },
    PublicDoctorSummary: {
      type: 'object', additionalProperties: false,
      required: ['doctorId', 'doctorName'],
      properties: {
        doctorId: ref('Id'), doctorName: { type: 'string', minLength: 1, maxLength: 64 },
        title: { type: 'string', maxLength: 64, nullable: true },
        specialties: { type: 'string', maxLength: 255, nullable: true },
      },
    },
    PublicDoctorDetail: {
      type: 'object', additionalProperties: false,
      required: ['doctorId', 'doctorName'],
      properties: {
        doctorId: ref('Id'), doctorName: { type: 'string', minLength: 1, maxLength: 64 },
        title: { type: 'string', maxLength: 64, nullable: true },
        specialties: { type: 'string', maxLength: 255, nullable: true },
        introduction: { type: 'string', maxLength: 10000, nullable: true },
      },
    },
    PublicDoctorPage: {
      type: 'object', additionalProperties: false, required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', items: ref('PublicDoctorSummary') },
        page: { type: 'integer', minimum: 1 },
        pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    PublicDoctorPageResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('PublicDoctorPage'), traceId: { type: 'string' },
      },
    },
    PublicDoctorResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('PublicDoctorDetail'), traceId: { type: 'string' },
      },
    },
    PublicSlotView: {
      type: 'object', additionalProperties: false,
      required: ['id', 'doctorId', 'startAt', 'endAt', 'capacity', 'availableCount', 'status'],
      properties: {
        id: ref('Id'), doctorId: ref('Id'), startAt: ref('DateTime'), endAt: ref('DateTime'),
        capacity: { type: 'integer', minimum: 1 },
        availableCount: { type: 'integer', minimum: 1 },
        status: { type: 'string', enum: ['OPEN'] },
      },
    },
    PublicSlotList: {
      type: 'object', additionalProperties: false, required: ['items'],
      properties: { items: { type: 'array', items: ref('PublicSlotView') } },
    },
    PublicSlotListResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('PublicSlotList'), traceId: { type: 'string' },
      },
    },
    SlotCreateInput: {
      type: 'object', additionalProperties: false,
      required: ['doctorId', 'date', 'startAt', 'endAt', 'capacity'],
      properties: {
        doctorId: ref('Id'),
        date: { type: 'string', format: 'date' },
        startAt: ref('DateTime'), endAt: ref('DateTime'),
        capacity: { type: 'integer', minimum: 1 },
      },
    },
    SlotCapacityPatchInput: {
      type: 'object', additionalProperties: false, required: ['capacity', 'version'],
      properties: {
        capacity: { type: 'integer', minimum: 1 },
        version: { type: 'integer', minimum: 0 },
      },
    },
    SlotCloseInput: {
      type: 'object', additionalProperties: false, required: ['version', 'reason'],
      properties: {
        version: { type: 'integer', minimum: 0 },
        reason: { type: 'string', minLength: 1, maxLength: 500 },
      },
    },
    SlotOpenInput: {
      type: 'object', additionalProperties: false, required: ['version'],
      properties: { version: { type: 'integer', minimum: 0 } },
    },
    StaffSlotView: {
      type: 'object', additionalProperties: false,
      required: [
        'slotId', 'doctorId', 'slotDate', 'startAt', 'endAt', 'capacity',
        'occupiedCount', 'availableCount', 'status', 'version',
      ],
      properties: {
        slotId: ref('Id'), doctorId: ref('Id'), slotDate: { type: 'string', format: 'date' },
        startAt: ref('DateTime'), endAt: ref('DateTime'),
        capacity: { type: 'integer', minimum: 1 },
        occupiedCount: { type: 'integer', minimum: 0 },
        availableCount: { type: 'integer', minimum: 0 },
        status: { type: 'string', enum: ['OPEN', 'CLOSED'] },
        version: { type: 'integer', minimum: 0 },
      },
    },
    StaffSlotList: {
      type: 'object', additionalProperties: false, required: ['items'],
      properties: { items: { type: 'array', items: ref('StaffSlotView') } },
    },
    StaffSlotListResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('StaffSlotList'), traceId: { type: 'string' },
      },
    },
    StaffSlotResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('StaffSlotView'), traceId: { type: 'string' },
      },
    },
    HealthGuideCreateInput: {
      type: 'object', additionalProperties: false,
      required: ['title', 'speciesCode', 'categoryCode', 'body', 'sourceNote'],
      properties: {
        title: { type: 'string', minLength: 1, maxLength: 255 },
        speciesCode: { type: 'string', pattern: '^[A-Za-z0-9_]{2,32}$' },
        categoryCode: { type: 'string', pattern: '^[A-Za-z0-9_]{2,64}$' },
        body: { type: 'string', minLength: 1, maxLength: 100000 },
        coverFileId: ref('Id'),
        sourceNote: { type: 'string', minLength: 1, maxLength: 1000 },
      },
    },
    HealthGuidePatchInput: {
      type: 'object', additionalProperties: false, required: ['version'],
      properties: {
        title: { type: 'string', minLength: 1, maxLength: 255 },
        speciesCode: { type: 'string', pattern: '^[A-Za-z0-9_]{2,32}$' },
        categoryCode: { type: 'string', pattern: '^[A-Za-z0-9_]{2,64}$' },
        body: { type: 'string', minLength: 1, maxLength: 100000 },
        coverFileId: ref('Id'),
        sourceNote: { type: 'string', minLength: 1, maxLength: 1000 },
        version: { type: 'integer', minimum: 0 },
      },
    },
    HealthGuidePublishInput: {
      type: 'object', additionalProperties: false, required: ['version'],
      properties: { version: { type: 'integer', minimum: 0 } },
    },
    HealthGuideOfflineInput: {
      type: 'object', additionalProperties: false, required: ['version', 'reason'],
      properties: {
        version: { type: 'integer', minimum: 0 },
        reason: { type: 'string', minLength: 1, maxLength: 500 },
      },
    },
    HealthGuideView: {
      type: 'object', additionalProperties: false,
      required: [
        'guideId', 'title', 'speciesCode', 'categoryCode', 'body', 'sourceNote',
        'status', 'editorUserId', 'version', 'createdAt', 'updatedAt',
      ],
      properties: {
        guideId: ref('Id'), title: { type: 'string', maxLength: 255 },
        speciesCode: { type: 'string', maxLength: 32 }, categoryCode: { type: 'string', maxLength: 64 },
        body: { type: 'string', maxLength: 100000 },
        coverFileId: { type: 'string', pattern: '^[1-9][0-9]*$', nullable: true },
        sourceNote: { type: 'string', maxLength: 1000 },
        status: { type: 'string', enum: ['DRAFT', 'PUBLISHED', 'OFFLINE'] },
        editorUserId: ref('Id'), version: { type: 'integer', minimum: 0 },
        publishedAt: { type: 'string', format: 'date-time', nullable: true },
        createdAt: ref('DateTime'), updatedAt: ref('DateTime'),
      },
    },
    HealthGuideResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('HealthGuideView'), traceId: { type: 'string' },
      },
    },
    HealthGuidePage: {
      type: 'object', additionalProperties: false, required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', items: ref('HealthGuideView') },
        page: { type: 'integer', minimum: 1 }, pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    HealthGuidePageResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('HealthGuidePage'), traceId: { type: 'string' },
      },
    },
    PublicGuideSummary: {
      type: 'object', additionalProperties: false,
      required: ['guideId', 'title', 'speciesCode', 'categoryCode', 'sourceNote', 'publishedAt'],
      properties: {
        guideId: ref('Id'), title: { type: 'string', maxLength: 255 },
        speciesCode: { type: 'string', maxLength: 32 },
        categoryCode: { type: 'string', maxLength: 64 },
        coverFileId: { type: 'string', pattern: '^[1-9][0-9]*$', nullable: true },
        sourceNote: { type: 'string', maxLength: 1000 }, publishedAt: ref('DateTime'),
      },
    },
    PublicGuideDetail: {
      type: 'object', additionalProperties: false,
      required: ['guideId', 'title', 'speciesCode', 'categoryCode', 'body', 'sourceNote', 'publishedAt'],
      properties: {
        guideId: ref('Id'), title: { type: 'string', maxLength: 255 },
        speciesCode: { type: 'string', maxLength: 32 },
        categoryCode: { type: 'string', maxLength: 64 }, body: { type: 'string', maxLength: 100000 },
        coverFileId: { type: 'string', pattern: '^[1-9][0-9]*$', nullable: true },
        sourceNote: { type: 'string', maxLength: 1000 }, publishedAt: ref('DateTime'),
      },
    },
    PublicGuidePage: {
      type: 'object', additionalProperties: false, required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', items: ref('PublicGuideSummary') },
        page: { type: 'integer', minimum: 1 }, pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    PublicGuidePageResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('PublicGuidePage'), traceId: { type: 'string' },
      },
    },
    PublicGuideDetailResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('PublicGuideDetail'), traceId: { type: 'string' },
      },
    },
    HealthStandardCreateInput: {
      type: 'object', additionalProperties: false,
      required: ['name', 'speciesCode', 'metricCode', 'description', 'conditionsNote', 'sourceNote'],
      properties: {
        name: { type: 'string', minLength: 1, maxLength: 128 },
        speciesCode: { type: 'string', pattern: '^[A-Za-z0-9_]{2,32}$' },
        ageMinMonths: { type: 'integer', minimum: 0, nullable: true },
        ageMaxMonths: { type: 'integer', minimum: 0, nullable: true },
        metricCode: { type: 'string', pattern: '^[A-Za-z0-9_]{2,64}$' },
        unit: { type: 'string', maxLength: 32, nullable: true },
        referenceMin: { type: 'number', nullable: true },
        referenceMax: { type: 'number', nullable: true },
        description: { type: 'string', minLength: 1, maxLength: 20000 },
        conditionsNote: { type: 'string', minLength: 1, maxLength: 20000 },
        sourceNote: { type: 'string', minLength: 1, maxLength: 1000 },
      },
    },
    HealthStandardPatchInput: {
      type: 'object', additionalProperties: false,
      required: [
        'name', 'speciesCode', 'metricCode', 'description', 'conditionsNote', 'sourceNote', 'version',
      ],
      properties: {
        name: { type: 'string', minLength: 1, maxLength: 128 },
        speciesCode: { type: 'string', pattern: '^[A-Za-z0-9_]{2,32}$' },
        ageMinMonths: { type: 'integer', minimum: 0, nullable: true },
        ageMaxMonths: { type: 'integer', minimum: 0, nullable: true },
        metricCode: { type: 'string', pattern: '^[A-Za-z0-9_]{2,64}$' },
        unit: { type: 'string', maxLength: 32, nullable: true },
        referenceMin: { type: 'number', nullable: true },
        referenceMax: { type: 'number', nullable: true },
        description: { type: 'string', minLength: 1, maxLength: 20000 },
        conditionsNote: { type: 'string', minLength: 1, maxLength: 20000 },
        sourceNote: { type: 'string', minLength: 1, maxLength: 1000 },
        version: { type: 'integer', minimum: 0 },
      },
    },
    HealthStandardView: {
      type: 'object', additionalProperties: false,
      required: [
        'standardId', 'name', 'speciesCode', 'ageMinMonths', 'ageMaxMonths', 'metricCode', 'unit',
        'referenceMin', 'referenceMax', 'description', 'conditionsNote', 'sourceNote', 'status',
        'editorDoctorId', 'version', 'createdAt', 'updatedAt',
      ],
      properties: {
        standardId: ref('Id'), name: { type: 'string', maxLength: 128 },
        speciesCode: { type: 'string', maxLength: 32 },
        ageMinMonths: { type: 'integer', minimum: 0, nullable: true },
        ageMaxMonths: { type: 'integer', minimum: 0, nullable: true },
        metricCode: { type: 'string', maxLength: 64 },
        unit: { type: 'string', maxLength: 32, nullable: true },
        referenceMin: { type: 'number', nullable: true },
        referenceMax: { type: 'number', nullable: true },
        description: { type: 'string', maxLength: 20000 },
        conditionsNote: { type: 'string', maxLength: 20000 },
        sourceNote: { type: 'string', maxLength: 1000 },
        status: { type: 'string', enum: ['DRAFT', 'PUBLISHED', 'OFFLINE'] },
        editorDoctorId: ref('Id'), version: { type: 'integer', minimum: 0 },
        createdAt: ref('DateTime'), updatedAt: ref('DateTime'),
      },
    },
    HealthStandardResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('HealthStandardView'), traceId: { type: 'string' },
      },
    },
    HealthStandardPage: {
      type: 'object', additionalProperties: false, required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', items: ref('HealthStandardView') },
        page: { type: 'integer', minimum: 1 }, pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    HealthStandardPageResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('HealthStandardPage'), traceId: { type: 'string' },
      },
    },
    HealthStandardPublishInput: {
      type: 'object', additionalProperties: false, required: ['version'],
      properties: { version: { type: 'integer', minimum: 0 } },
    },
    HealthStandardOfflineInput: {
      type: 'object', additionalProperties: false, required: ['version', 'reason'],
      properties: {
        version: { type: 'integer', minimum: 0 },
        reason: { type: 'string', minLength: 1, maxLength: 500 },
      },
    },
    PublicHealthStandardView: {
      type: 'object', additionalProperties: false,
      required: [
        'standardId', 'name', 'speciesCode', 'ageMinMonths', 'ageMaxMonths', 'metricCode', 'unit',
        'referenceMin', 'referenceMax', 'description', 'conditionsNote', 'sourceNote',
      ],
      properties: {
        standardId: ref('Id'), name: { type: 'string', maxLength: 128 },
        speciesCode: { type: 'string', maxLength: 32 },
        ageMinMonths: { type: 'integer', minimum: 0, nullable: true },
        ageMaxMonths: { type: 'integer', minimum: 0, nullable: true },
        metricCode: { type: 'string', maxLength: 64 },
        unit: { type: 'string', maxLength: 32, nullable: true },
        referenceMin: { type: 'number', nullable: true },
        referenceMax: { type: 'number', nullable: true },
        description: { type: 'string', maxLength: 20000 },
        conditionsNote: { type: 'string', maxLength: 20000 },
        sourceNote: { type: 'string', maxLength: 1000 },
      },
    },
    PublicHealthStandardListResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: { type: 'array', items: ref('PublicHealthStandardView') }, traceId: { type: 'string' },
      },
    },
    TriagePredicate: {
      type: 'object',
      additionalProperties: true,
      description: '受triage-rule-predicate.schema.json和服务端TriageEvaluator严格校验的封闭规则表达式。',
    },
    TriageRuleCreateInput: {
      type: 'object', additionalProperties: false,
      required: ['ruleCode', 'speciesCode', 'title', 'predicate', 'riskLevel', 'fixedAdvice', 'sourceUrl'],
      properties: {
        ruleCode: { type: 'string', pattern: '^[A-Za-z][A-Za-z0-9_]{0,63}$' },
        speciesCode: { type: 'string', enum: ['CAT', 'DOG'] },
        title: { type: 'string', minLength: 1, maxLength: 128 },
        predicate: ref('TriagePredicate'),
        riskLevel: { type: 'string', enum: ['EMERGENCY', 'URGENT', 'ROUTINE', 'UNKNOWN'] },
        fixedAdvice: { type: 'string', minLength: 1, maxLength: 10000 },
        sourceUrl: { type: 'string', format: 'uri', minLength: 1, maxLength: 1000 },
      },
    },
    TriageRulePatchInput: {
      type: 'object', additionalProperties: false, required: ['version'],
      properties: {
        speciesCode: { type: 'string', enum: ['CAT', 'DOG'] },
        title: { type: 'string', minLength: 1, maxLength: 128 },
        predicate: ref('TriagePredicate'),
        riskLevel: { type: 'string', enum: ['EMERGENCY', 'URGENT', 'ROUTINE', 'UNKNOWN'] },
        fixedAdvice: { type: 'string', minLength: 1, maxLength: 10000 },
        sourceUrl: { type: 'string', format: 'uri', minLength: 1, maxLength: 1000 },
        version: { type: 'integer', minimum: 0 },
      },
    },
    TriageRuleSubmitReviewInput: {
      type: 'object', additionalProperties: false, required: ['version'],
      properties: { version: { type: 'integer', minimum: 0 } },
    },
    TriageRuleReviewInput: {
      type: 'object', additionalProperties: false, required: ['decision', 'version'],
      properties: {
        decision: { type: 'string', enum: ['APPROVE', 'REJECT'] },
        reviewNote: { type: 'string', minLength: 1, maxLength: 500 },
        version: { type: 'integer', minimum: 0 },
      },
    },
    TriageRuleEnableInput: {
      type: 'object', additionalProperties: false, required: ['version'],
      properties: { version: { type: 'integer', minimum: 0 } },
    },
    TriageRuleDisableInput: {
      type: 'object', additionalProperties: false, required: ['reason', 'version'],
      properties: {
        reason: { type: 'string', minLength: 1, maxLength: 500 },
        version: { type: 'integer', minimum: 0 },
      },
    },
    TriageRulePreviewInput: {
      type: 'object', additionalProperties: false,
      required: ['speciesCode', 'content', 'screeningAnswers'],
      properties: {
        speciesCode: { type: 'string', enum: ['CAT', 'DOG'] },
        content: { type: 'string', minLength: 1, maxLength: 4000 },
        screeningAnswers: {
          type: 'object', maxProperties: 50,
          additionalProperties: { type: 'string', minLength: 1, maxLength: 64 },
        },
      },
    },
    TriagePreviewEvidence: {
      type: 'object', additionalProperties: false,
      required: ['fact', 'code', 'operator', 'observedState', 'result', 'excerpt'],
      properties: {
        fact: { type: 'string', enum: ['SYMPTOM', 'SCREENING', 'DURATION_MINUTES'] },
        code: { type: 'string', maxLength: 64 }, operator: { type: 'string', maxLength: 32 },
        observedState: { type: 'string', maxLength: 128 },
        result: { type: 'string', enum: ['TRUE', 'FALSE', 'UNKNOWN'] },
        excerpt: { type: 'string', maxLength: 240 },
      },
    },
    TriagePreviewRuleResult: {
      type: 'object', additionalProperties: false,
      required: [
        'ruleId', 'ruleCode', 'revisionNo', 'riskLevel', 'fixedAdvice', 'outcome', 'truth',
        'coverageLimited', 'evidence',
      ],
      properties: {
        ruleId: ref('Id'), ruleCode: { type: 'string', maxLength: 64 },
        revisionNo: { type: 'integer', minimum: 1 },
        riskLevel: { type: 'string', enum: ['EMERGENCY', 'URGENT', 'ROUTINE', 'UNKNOWN'] },
        fixedAdvice: { type: 'string', maxLength: 10000 },
        outcome: { type: 'string', enum: ['MATCHED', 'NOT_MATCHED', 'UNKNOWN', 'NOT_APPLICABLE'] },
        truth: { type: 'string', enum: ['TRUE', 'FALSE', 'UNKNOWN'] },
        coverageLimited: { type: 'boolean' },
        evidence: { type: 'array', items: ref('TriagePreviewEvidence') },
      },
    },
    TriageRulePreviewView: {
      type: 'object', additionalProperties: false,
      required: ['speciesCode', 'evaluatedCount', 'matchedCount', 'results'],
      properties: {
        speciesCode: { type: 'string', enum: ['CAT', 'DOG'] },
        evaluatedCount: { type: 'integer', minimum: 0 },
        matchedCount: { type: 'integer', minimum: 0 },
        results: { type: 'array', items: ref('TriagePreviewRuleResult') },
      },
    },
    TriageRulePreviewResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('TriageRulePreviewView'), traceId: { type: 'string' },
      },
    },
    TriageRuleView: {
      type: 'object', additionalProperties: false,
      required: [
        'ruleId', 'ruleCode', 'revisionNo', 'speciesCode', 'title', 'predicate', 'riskLevel',
        'fixedAdvice', 'sourceUrl', 'reviewStatus', 'enabled', 'creatorUserId', 'version',
        'createdAt', 'updatedAt',
      ],
      properties: {
        ruleId: ref('Id'), ruleCode: { type: 'string', maxLength: 64 },
        revisionNo: { type: 'integer', minimum: 1 },
        speciesCode: { type: 'string', enum: ['CAT', 'DOG'] },
        title: { type: 'string', maxLength: 128 }, predicate: ref('TriagePredicate'),
        riskLevel: { type: 'string', enum: ['EMERGENCY', 'URGENT', 'ROUTINE', 'UNKNOWN'] },
        fixedAdvice: { type: 'string', maxLength: 10000 },
        sourceUrl: { type: 'string', format: 'uri', maxLength: 1000 },
        reviewStatus: { type: 'string', enum: ['DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED'] },
        enabled: { type: 'boolean' }, creatorUserId: ref('Id'),
        reviewerUserId: { type: 'string', pattern: '^[1-9][0-9]*$', nullable: true },
        reviewNote: { type: 'string', maxLength: 500, nullable: true },
        reviewedAt: { type: 'string', format: 'date-time', nullable: true },
        version: { type: 'integer', minimum: 0 }, createdAt: ref('DateTime'), updatedAt: ref('DateTime'),
      },
    },
    TriageRuleResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('TriageRuleView'), traceId: { type: 'string' },
      },
    },
    TriageRulePage: {
      type: 'object', additionalProperties: false, required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', items: ref('TriageRuleView') },
        page: { type: 'integer', minimum: 1 }, pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    TriageRulePageResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('TriageRulePage'), traceId: { type: 'string' },
      },
    },
    HealthLogInput: {
      type: 'object',
      additionalProperties: false,
      required: ['observedAt', 'attachmentIds'],
      properties: {
        observedAt: ref('DateTime'),
        weightKg: { type: 'number', exclusiveMinimum: 0 },
        dietNote: { type: 'string' },
        stoolNote: { type: 'string' },
        activityNote: { type: 'string' },
        symptomNote: { type: 'string' },
        attachmentIds: { type: 'array', items: ref('Id') },
      },
    },
    CreateAppointmentInput: {
      type: 'object',
      additionalProperties: false,
      required: ['petId', 'doctorId', 'slotId', 'chiefComplaint'],
      properties: {
        petId: ref('Id'),
        doctorId: ref('Id'),
        slotId: ref('Id'),
        chiefComplaint: { type: 'string', minLength: 1, maxLength: 1000 },
        aiSessionId: ref('Id'),
      },
      example: {
        petId: '101',
        doctorId: '201',
        slotId: '301',
        chiefComplaint: '猫咪近两天食欲下降',
        aiSessionId: '401',
      },
    },
    AppointmentView: {
      type: 'object',
      additionalProperties: false,
      required: ['id', 'appointmentNo', 'status', 'scheduledStartAt', 'scheduledEndAt', 'confirmDeadlineAt', 'version'],
      properties: {
        id: ref('Id'),
        appointmentNo: { type: 'string' },
        status: {
          type: 'string',
          enum: ['PENDING', 'CONFIRMED', 'IN_CONSULTATION', 'COMPLETED', 'REJECTED', 'CANCELLED', 'NO_SHOW'],
        },
        scheduledStartAt: ref('DateTime'),
        scheduledEndAt: ref('DateTime'),
        confirmDeadlineAt: ref('DateTime'),
        version: { type: 'integer', minimum: 0 },
      },
    },
    AppointmentResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: ref('AppointmentView'),
        traceId: { type: 'string' },
      },
    },
    AppointmentStatus: {
      type: 'string',
      enum: ['PENDING', 'CONFIRMED', 'IN_CONSULTATION', 'COMPLETED', 'REJECTED', 'CANCELLED', 'NO_SHOW'],
    },
    AppointmentReadSummary: {
      type: 'object', additionalProperties: false,
      required: [
        'id', 'appointmentNo', 'doctorId', 'doctorName', 'petId', 'petName', 'status', 'scheduledStartAt',
        'scheduledEndAt', 'confirmDeadlineAt', 'createdAt', 'version', 'allowedActions',
      ],
      properties: {
        id: ref('Id'),
        appointmentNo: { type: 'string', maxLength: 40 },
        doctorId: ref('Id'),
        doctorName: { type: 'string', maxLength: 64 },
        petId: { type: 'string', pattern: '^[1-9][0-9]*$', nullable: true, description: 'WEB待确认视图隐藏档案链接。' },
        petName: { type: 'string', maxLength: 64 },
        status: ref('AppointmentStatus'),
        scheduledStartAt: ref('DateTime'),
        scheduledEndAt: ref('DateTime'),
        confirmDeadlineAt: ref('DateTime'),
        createdAt: ref('DateTime'),
        version: { type: 'integer', minimum: 0 },
        allowedActions: {
          type: 'array',
          items: { type: 'string', enum: ['CONFIRM', 'REJECT', 'CANCEL', 'START', 'COMPLETE', 'MARK_NO_SHOW'] },
        },
      },
    },
    AppointmentReadPage: {
      type: 'object', additionalProperties: false, required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', maxItems: 100, items: ref('AppointmentReadSummary') },
        page: { type: 'integer', minimum: 1 },
        pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    AppointmentReadPageResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('AppointmentReadPage'), traceId: { type: 'string' },
      },
    },
    AppointmentReadDetail: {
      type: 'object', additionalProperties: false,
      required: [
        'appointment', 'chiefComplaint', 'aiSessionId', 'confirmedAt', 'startedAt', 'completedAt',
        'cancelledAt', 'cancelledBy', 'terminalReason',
      ],
      properties: {
        appointment: ref('AppointmentReadSummary'),
        chiefComplaint: { type: 'string', maxLength: 1000 },
        aiSessionId: { type: 'string', pattern: '^[1-9][0-9]*$', nullable: true },
        confirmedAt: { type: 'string', format: 'date-time', nullable: true },
        startedAt: { type: 'string', format: 'date-time', nullable: true },
        completedAt: { type: 'string', format: 'date-time', nullable: true },
        cancelledAt: { type: 'string', format: 'date-time', nullable: true },
        cancelledBy: { type: 'string', enum: ['USER', 'DOCTOR', 'ADMIN', 'SYSTEM'], nullable: true },
        terminalReason: { type: 'string', maxLength: 500, nullable: true },
      },
    },
    AppointmentReadDetailResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('AppointmentReadDetail'), traceId: { type: 'string' },
      },
    },
    AppointmentStatusEntry: {
      type: 'object', additionalProperties: false,
      required: ['id', 'fromStatus', 'toStatus', 'actorType', 'reason', 'createdAt'],
      properties: {
        id: ref('Id'),
        fromStatus: { type: 'string', enum: ['PENDING', 'CONFIRMED', 'IN_CONSULTATION', 'COMPLETED', 'REJECTED', 'CANCELLED', 'NO_SHOW'], nullable: true },
        toStatus: ref('AppointmentStatus'),
        actorType: { type: 'string', enum: ['USER', 'DOCTOR', 'ADMIN', 'SYSTEM'] },
        reason: { type: 'string', maxLength: 500, nullable: true },
        createdAt: ref('DateTime'),
      },
    },
    AppointmentStatusResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: { type: 'array', items: ref('AppointmentStatusEntry') }, traceId: { type: 'string' },
      },
    },
    AppointmentConfirmInput: {
      type: 'object', additionalProperties: false, required: ['version'],
      properties: { version: { type: 'integer', minimum: 0 } },
    },
    AppointmentRejectInput: {
      type: 'object', additionalProperties: false, required: ['version', 'reason'],
      properties: {
        version: { type: 'integer', minimum: 0 },
        reason: { type: 'string', minLength: 1, maxLength: 500 },
      },
    },
    AppointmentDecisionResult: {
      type: 'object', additionalProperties: false, required: ['id', 'status', 'version'],
      properties: {
        id: ref('Id'), status: { type: 'string', enum: ['CONFIRMED', 'REJECTED'] },
        version: { type: 'integer', minimum: 1 },
      },
    },
    AppointmentDecisionResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('AppointmentDecisionResult'), traceId: { type: 'string' },
      },
    },
    AppointmentCancelInput: {
      type: 'object', additionalProperties: false, required: ['version', 'reason'],
      properties: {
        version: { type: 'integer', minimum: 0 },
        reason: { type: 'string', minLength: 1, maxLength: 500 },
      },
    },
    AppointmentCancellationResult: {
      type: 'object', additionalProperties: false, required: ['id', 'status', 'version'],
      properties: {
        id: ref('Id'), status: { type: 'string', enum: ['CANCELLED'] },
        version: { type: 'integer', minimum: 1 },
      },
    },
    AppointmentCancellationResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('AppointmentCancellationResult'), traceId: { type: 'string' },
      },
    },
    AppointmentStartInput: {
      type: 'object', additionalProperties: false, required: ['version'],
      properties: { version: { type: 'integer', minimum: 0 } },
    },
    AppointmentNoShowInput: {
      type: 'object', additionalProperties: false, required: ['version', 'reason'],
      properties: {
        version: { type: 'integer', minimum: 0 },
        reason: { type: 'string', minLength: 1, maxLength: 500 },
      },
    },
    AppointmentConsultationActionResult: {
      type: 'object', additionalProperties: false, required: ['id', 'status', 'version'],
      properties: {
        id: ref('Id'), status: { type: 'string', enum: ['IN_CONSULTATION', 'NO_SHOW'] },
        version: { type: 'integer', minimum: 1 },
      },
    },
    AppointmentConsultationActionResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('AppointmentConsultationActionResult'), traceId: { type: 'string' },
      },
    },
    AppointmentChangeCreateInput: {
      type: 'object', additionalProperties: false, required: ['version', 'newSlotId', 'reason'],
      properties: {
        version: { type: 'integer', minimum: 0 }, newSlotId: ref('Id'),
        reason: { type: 'string', minLength: 1, maxLength: 500 },
      },
    },
    AppointmentChangeCreated: {
      type: 'object', additionalProperties: false,
      required: ['id', 'appointmentId', 'newSlotId', 'status', 'expiresAt', 'version'],
      properties: {
        id: ref('Id'), appointmentId: ref('Id'), newSlotId: ref('Id'),
        status: { type: 'string', enum: ['OPEN'] }, expiresAt: ref('DateTime'),
        version: { type: 'integer', minimum: 0 },
      },
    },
    AppointmentChangeCreatedResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('AppointmentChangeCreated'), traceId: { type: 'string' },
      },
    },
    AppointmentChangeAcceptInput: {
      type: 'object', additionalProperties: false, required: ['version'],
      properties: { version: { type: 'integer', minimum: 0 } },
    },
    AppointmentChangeRejectInput: {
      type: 'object', additionalProperties: false, required: ['version', 'reason'],
      properties: {
        version: { type: 'integer', minimum: 0 },
        reason: { type: 'string', minLength: 1, maxLength: 500 },
      },
    },
    AppointmentChangeClosed: {
      type: 'object', additionalProperties: false,
      required: ['id', 'appointmentId', 'status', 'respondedAt', 'version'],
      properties: {
        id: ref('Id'), appointmentId: ref('Id'), status: { type: 'string', enum: ['REJECTED', 'WITHDRAWN'] },
        respondedAt: ref('DateTime'), version: { type: 'integer', minimum: 1 },
      },
    },
    AppointmentChangeClosedResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('AppointmentChangeClosed'), traceId: { type: 'string' },
      },
    },
    AppointmentChangeAccepted: {
      type: 'object', additionalProperties: false,
      required: ['id', 'appointmentId', 'status', 'newSlotId', 'scheduledStartAt', 'scheduledEndAt', 'version', 'appointmentVersion'],
      properties: {
        id: ref('Id'), appointmentId: ref('Id'), status: { type: 'string', enum: ['ACCEPTED'] },
        newSlotId: ref('Id'), scheduledStartAt: ref('DateTime'), scheduledEndAt: ref('DateTime'),
        version: { type: 'integer', minimum: 1 }, appointmentVersion: { type: 'integer', minimum: 1 },
      },
    },
    AppointmentChangeAcceptedResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('AppointmentChangeAccepted'), traceId: { type: 'string' },
      },
    },
    AppointmentChangeHistoryItem: {
      type: 'object', additionalProperties: false,
      required: [
        'id', 'appointmentId', 'proposerType', 'oldSlotId', 'newSlotId', 'oldStartAt', 'newStartAt',
        'status', 'reason', 'expiresAt', 'respondedAt', 'responseReason', 'version', 'createdAt',
      ],
      properties: {
        id: ref('Id'), appointmentId: ref('Id'), proposerType: { type: 'string', enum: ['USER', 'DOCTOR'] },
        oldSlotId: ref('Id'), newSlotId: ref('Id'), oldStartAt: ref('DateTime'), newStartAt: ref('DateTime'),
        status: { type: 'string', enum: ['OPEN', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED'] },
        reason: { type: 'string', maxLength: 500 }, expiresAt: ref('DateTime'),
        respondedAt: { type: 'string', format: 'date-time', nullable: true },
        responseReason: { type: 'string', maxLength: 500, nullable: true },
        version: { type: 'integer', minimum: 0 }, createdAt: ref('DateTime'),
      },
    },
    AppointmentChangeHistoryPage: {
      type: 'object', additionalProperties: false, required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', maxItems: 100, items: ref('AppointmentChangeHistoryItem') },
        page: { type: 'integer', minimum: 1 },
        pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    AppointmentChangeHistoryResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('AppointmentChangeHistoryPage'), traceId: { type: 'string' },
      },
    },
    MedicalMedication: {
      type: 'object', additionalProperties: false, required: ['name', 'dosage', 'frequency', 'duration'],
      properties: {
        name: { type: 'string', minLength: 1, maxLength: 128 }, dosage: { type: 'string', minLength: 1, maxLength: 128 },
        frequency: { type: 'string', minLength: 1, maxLength: 128 }, duration: { type: 'string', minLength: 1, maxLength: 128 },
      },
    },
    MedicalRecordInput: {
      type: 'object', additionalProperties: false, required: ['chiefComplaint', 'examination', 'diagnosis', 'treatment'],
      properties: medicalRecordProperties,
    },
    MedicalRecordView: strictObject({
      ...medicalRecordProperties,
      chiefComplaint: { ...medicalRecordProperties.chiefComplaint, nullable: true },
      examination: { ...medicalRecordProperties.examination, nullable: true },
      diagnosis: { ...medicalRecordProperties.diagnosis, nullable: true },
      treatment: { ...medicalRecordProperties.treatment, nullable: true },
    }),
    MedicalReadContent: medicalReadContent,
    MedicalRecordSummary: strictObject(medicalSummary),
    MedicalRecordHeader: strictObject({
      ...medicalSummary,
      status: { type: 'string', enum: ['DRAFT', 'SUBMITTED'] },
      submittedAt: { type: 'string', format: 'date-time', nullable: true },
      revisionNo: { type: 'integer', minimum: 0 },
    }),
    MedicalRecordDetail: strictObject({ record: ref('MedicalRecordHeader'), content: ref('MedicalReadContent') }),
    MedicalRecordList: paged('MedicalRecordSummary'),
    MedicalVersionView: strictObject({
      id: ref('Id'), revisionNo: { type: 'integer', minimum: 1 }, doctorName: { type: 'string' },
      reason: { type: 'string', minLength: 1, maxLength: 500 }, createdAt: ref('DateTime'),
      before: { ...medicalReadContent, nullable: true }, after: ref('MedicalReadContent'),
      changedFields: { type: 'array', items: { type: 'string', enum: [...Object.keys(medicalRecordProperties), 'attachmentIds'] } },
    }),
    MedicalVersionList: paged('MedicalVersionView'),
    MedicalRecordListResponse: success('MedicalRecordList'),
    MedicalRecordDetailResponse: success('MedicalRecordDetail'),
    MedicalVersionListResponse: success('MedicalVersionList'),
    KnowledgeCreateDocumentInput: {
      ...strictObject(knowledgeDocumentFields), required: ['title', 'speciesCode', 'categoryCode'],
    },
    KnowledgePatchDocumentInput: {
      ...strictObject({ ...knowledgeDocumentFields, version: rowVersion }), required: ['version'],
    },
    KnowledgeCreateVersionInput: {
      ...strictObject(knowledgeVersionFields), required: ['content', 'sourceTitle'],
      description: '须提供来源链接或文件。',
    },
    KnowledgePatchVersionInput: {
      ...strictObject({ ...knowledgeVersionFields, version: rowVersion }), required: ['version'],
      description: '提供任一来源字段即替换来源组合，须保留有效链接或文件。',
    },
    KnowledgeSubmitReviewInput: strictObject({ version: rowVersion }),
    KnowledgeReviewInput: {
      ...strictObject({
        version: rowVersion, decision: { type: 'string', enum: ['APPROVE', 'REJECT'] },
        reviewNote: { type: 'string', maxLength: 500, nullable: true },
      }),
      required: ['version', 'decision'],
      description: '驳回时必须填写审核意见。',
    },
    KnowledgeDocument: strictObject({
      documentId: ref('Id'), ...knowledgeDocumentFields, status: { type: 'string', enum: ['DRAFT', 'PUBLISHED', 'OFFLINE'] },
      activeVersionId: nullableId, creatorUserId: ref('Id'), version: rowVersion, createdAt: ref('DateTime'), updatedAt: ref('DateTime'),
    }),
    KnowledgeVersionSummary: strictObject({
      knowledgeVersionId: ref('Id'), documentId: ref('Id'), versionNo: knowledgeVersionNumber,
      sourceTitle: knowledgeVersionFields.sourceTitle, sourceUrl: knowledgeVersionFields.sourceUrl,
      sourceFileId: nullableId, contentHash: { type: 'string', pattern: '^[0-9a-f]{64}$' },
      reviewStatus: { type: 'string', enum: ['DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED'] },
      reviewerUserId: nullableId, reviewNote: { type: 'string', maxLength: 500, nullable: true },
      reviewedAt: nullableTime, publishedAt: nullableTime, version: rowVersion, createdAt: ref('DateTime'), updatedAt: ref('DateTime'),
    }),
    KnowledgeVersionDetail: strictObject({ metadata: ref('KnowledgeVersionSummary'), content: knowledgeContent }),
    KnowledgeCreatedDocument: strictObject({ documentId: ref('Id'), version: rowVersion }),
    KnowledgeCreatedVersion: strictObject({
      documentId: ref('Id'), knowledgeVersionId: ref('Id'), versionNo: knowledgeVersionNumber, version: rowVersion,
    }),
    KnowledgeDocumentPage: paged('KnowledgeDocument'),
    KnowledgeVersionPage: paged('KnowledgeVersionSummary'),
    KnowledgeDocumentResponse: success('KnowledgeDocument'),
    KnowledgeVersionDetailResponse: success('KnowledgeVersionDetail'),
    KnowledgeReviewReceipt: strictObject({
      knowledgeVersionId: ref('Id'), documentId: ref('Id'),
      reviewStatus: { type: 'string', enum: ['IN_REVIEW', 'APPROVED', 'REJECTED'] },
      reviewerUserId: nullableId, reviewedAt: nullableTime, version: rowVersion,
    }),
    KnowledgeReviewResponse: success('KnowledgeReviewReceipt'),
    KnowledgeCreatedDocumentResponse: success('KnowledgeCreatedDocument'),
    KnowledgeCreatedVersionResponse: success('KnowledgeCreatedVersion'),
    KnowledgeDocumentPageResponse: success('KnowledgeDocumentPage'),
    KnowledgeVersionPageResponse: success('KnowledgeVersionPage'),
    MedicalDraftSaveInput: {
      type: 'object', additionalProperties: false, required: ['version', 'medicalRecord', 'attachmentIds'],
      properties: {
        version: { type: 'integer', minimum: 0 }, medicalRecord: ref('MedicalRecordInput'),
        attachmentIds: { type: 'array', maxItems: 20, items: ref('Id') },
      },
    },
    MedicalCompleteInput: {
      type: 'object', additionalProperties: false,
      required: ['version', 'recordVersion', 'medicalRecord', 'attachmentIds'],
      properties: {
        version: { type: 'integer', minimum: 0 }, recordVersion: { type: 'integer', minimum: 0 },
        medicalRecord: ref('MedicalRecordInput'), attachmentIds: { type: 'array', maxItems: 20, items: ref('Id') },
      },
    },
    MedicalCompletedAppointment: {
      type: 'object', additionalProperties: false, required: ['id', 'status', 'version', 'completedAt'],
      properties: {
        id: ref('Id'), status: { type: 'string', enum: ['COMPLETED'] },
        version: { type: 'integer', minimum: 1 }, completedAt: ref('DateTime'),
      },
    },
    MedicalCompleted: {
      type: 'object', additionalProperties: false, required: ['appointment', 'medicalRecordId', 'revisionNo'],
      properties: {
        appointment: ref('MedicalCompletedAppointment'), medicalRecordId: ref('Id'),
        revisionNo: { type: 'integer', enum: [1] },
      },
    },
    MedicalCompleteResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('MedicalCompleted'), traceId: { type: 'string' },
      },
    },
    MedicalRevisionInput: {
      type: 'object', additionalProperties: false, required: ['version', 'reason', 'medicalRecord', 'attachmentIds'],
      properties: {
        version: { type: 'integer', minimum: 0 }, reason: { type: 'string', minLength: 1, maxLength: 500 },
        medicalRecord: ref('MedicalRecordInput'), attachmentIds: { type: 'array', maxItems: 20, items: ref('Id') },
      },
    },
    MedicalRevised: {
      type: 'object', additionalProperties: false, required: ['medicalRecordId', 'revisionId', 'revisionNo', 'version', 'createdAt'],
      properties: {
        medicalRecordId: ref('Id'), revisionId: ref('Id'), revisionNo: { type: 'integer', minimum: 2 },
        version: { type: 'integer', minimum: 1 }, createdAt: ref('DateTime'),
      },
    },
    MedicalRevisionResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('MedicalRevised'), traceId: { type: 'string' },
      },
    },
    MedicalDraftResult: {
      type: 'object', additionalProperties: false, required: ['draft'],
      properties: {
        draft: {
          type: 'object', nullable: true, additionalProperties: false,
          required: ['id', 'appointmentId', 'status', 'version', 'revisionNo', 'medicalRecord', 'attachmentIds'],
          properties: {
            id: ref('Id'), appointmentId: ref('Id'), status: { type: 'string', enum: ['DRAFT'] },
            version: { type: 'integer', minimum: 0 }, revisionNo: { type: 'integer', enum: [0] },
            medicalRecord: ref('MedicalRecordInput'), attachmentIds: { type: 'array', items: ref('Id') },
          },
        },
      },
    },
    MedicalDraftResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('MedicalDraftResult'), traceId: { type: 'string' },
      },
    },
    ScreeningAnswers: {
      type: 'object',
      additionalProperties: true,
      properties: {
        consciousness: { type: 'string' },
        breathingDifficulty: { type: 'string', enum: ['YES', 'NO', 'UNKNOWN'] },
        uncontrolledBleeding: { type: 'string', enum: ['YES', 'NO', 'UNKNOWN'] },
        suspectedPoisoning: { type: 'string', enum: ['YES', 'NO', 'UNKNOWN'] },
      },
    },
    MessageInput: {
      type: 'object',
      additionalProperties: false,
      required: ['clientMessageId', 'content'],
      properties: {
        clientMessageId: { type: 'string', minLength: 1, maxLength: 64 },
        content: { type: 'string', minLength: 1, maxLength: 4000 },
        screeningAnswers: ref('ScreeningAnswers'),
      },
      example: {
        clientMessageId: 'client-generated-unique-id',
        content: '猫咪食欲下降，今天没有呕吐，也没有抽搐。',
        screeningAnswers: {
          consciousness: 'RESPONSIVE',
          breathingDifficulty: 'NO',
          uncontrolledBleeding: 'NO',
          suspectedPoisoning: 'UNKNOWN',
        },
      },
    },
    MessageAcceptedData: {
      type: 'object',
      additionalProperties: false,
      required: ['userMessageId', 'processingStatus', 'generationNo', 'pollAfterMs'],
      properties: {
        userMessageId: ref('Id'),
        processingStatus: { type: 'string', enum: ['QUEUED', 'COMPLETED'] },
        generationNo: { type: 'integer', minimum: 0 },
        pollAfterMs: { type: 'integer', minimum: 0 },
      },
    },
    MessageAcceptedResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: ref('MessageAcceptedData'),
        traceId: { type: 'string' },
      },
    },
    FileView: {
      type: 'object',
      additionalProperties: false,
      required: ['id', 'originalName', 'mimeType', 'sizeBytes', 'purpose', 'status'],
      properties: {
        id: ref('Id'),
        originalName: { type: 'string' },
        mimeType: { type: 'string' },
        sizeBytes: { type: 'string', pattern: '^[0-9]+$', description: 'BIGINT serialized as string.' },
        purpose: { type: 'string' },
        status: { type: 'string' },
      },
    },
    FileUploadForm: {
      type: 'object',
      additionalProperties: false,
      required: ['file', 'purpose'],
      properties: {
        file: { type: 'string', format: 'binary' },
        purpose: {
          type: 'string',
          enum: ['AVATAR', 'PET', 'HEALTH_LOG', 'MEDICAL', 'KNOWLEDGE', 'GUIDE', 'QUALIFICATION'],
        },
      },
    },
    FileResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: ref('FileView'),
        traceId: { type: 'string' },
      },
    },
    FileDownloadTicketInput: {
      type: 'object',
      additionalProperties: false,
      properties: {
        businessType: {
          type: 'string',
          enum: ['PET', 'HEALTH_LOG', 'MEDICAL_DRAFT', 'MEDICAL_VERSION', 'KNOWLEDGE_VERSION', 'GUIDE', 'QUALIFICATION', 'USER_AVATAR'],
        },
        businessId: ref('Id'),
      },
    },
    FileDownloadTicketView: {
      type: 'object',
      additionalProperties: false,
      required: ['url', 'expiresAt'],
      properties: {
        url: { type: 'string', format: 'uri' },
        expiresAt: ref('DateTime'),
      },
    },
    FileDownloadTicketResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: ref('FileDownloadTicketView'),
        traceId: { type: 'string' },
      },
    },
    FileDiscardInput: {
      type: 'object',
      additionalProperties: false,
      required: ['reason'],
      properties: { reason: { type: 'string', minLength: 1, maxLength: 500 } },
    },
    FileDiscardView: {
      type: 'object',
      additionalProperties: false,
      required: ['id', 'status'],
      properties: {
        id: ref('Id'),
        status: { type: 'string', enum: ['DELETED'] },
      },
    },
    FileDiscardResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: ref('FileDiscardView'),
        traceId: { type: 'string' },
      },
    },
    NoticeView: {
      type: 'object',
      additionalProperties: false,
      required: ['id', 'noticeType', 'title', 'body', 'resourceType', 'resourceId', 'createdAt'],
      properties: {
        id: ref('Id'),
        noticeType: {
          type: 'string',
          enum: [
            'APPOINTMENT_CREATED', 'APPOINTMENT_CONFIRMED', 'APPOINTMENT_REJECTED', 'APPOINTMENT_CANCELLED',
            'APPOINTMENT_RESCHEDULED', 'APPOINTMENT_CHANGE_REJECTED', 'APPOINTMENT_CHANGE_WITHDRAWN',
            'APPOINTMENT_CHANGE_EXPIRED', 'MEDICAL_COMPLETED',
          ],
        },
        title: { type: 'string', maxLength: 128 },
        body: { type: 'string', maxLength: 1000 },
        resourceType: { type: 'string', enum: ['APPOINTMENT', 'MEDICAL_RECORD'] },
        resourceId: ref('Id'),
        readAt: { type: 'string', format: 'date-time', nullable: true },
        createdAt: ref('DateTime'),
      },
    },
    NoticePage: {
      type: 'object',
      additionalProperties: false,
      required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', items: ref('NoticeView') },
        page: { type: 'integer', minimum: 1 },
        pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    NoticePageResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: ref('NoticePage'),
        traceId: { type: 'string' },
      },
    },
    NoticeReadView: {
      type: 'object',
      additionalProperties: false,
      required: ['id', 'readAt'],
      properties: {
        id: ref('Id'),
        readAt: ref('DateTime'),
      },
    },
    NoticeReadResponse: {
      type: 'object',
      additionalProperties: false,
      required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] },
        message: { type: 'string' },
        data: ref('NoticeReadView'),
        traceId: { type: 'string' },
      },
    },
    AuditLogView: {
      type: 'object',
      additionalProperties: false,
      required: ['id', 'actorType', 'action', 'resourceType', 'result', 'traceId', 'createdAt'],
      properties: {
        id: ref('Id'),
        actorUserId: { type: 'string', pattern: '^-?[0-9]+$', nullable: true },
        actorType: { type: 'string', enum: ['USER', 'DOCTOR', 'ADMIN', 'SYSTEM'] },
        action: { type: 'string', maxLength: 128 },
        resourceType: { type: 'string', maxLength: 64 },
        resourceId: { type: 'string', pattern: '^-?[0-9]+$', nullable: true },
        result: { type: 'string', enum: ['SUCCESS', 'FAILURE'] },
        traceId: { type: 'string', maxLength: 64 },
        ipMasked: { type: 'string', maxLength: 64, nullable: true },
        createdAt: ref('DateTime'),
      },
    },
    AuditLogPage: {
      type: 'object', additionalProperties: false, required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', items: ref('AuditLogView') },
        page: { type: 'integer', minimum: 1 },
        pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    AuditLogPageResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('AuditLogPage'), traceId: { type: 'string' },
      },
    },
    AiCallLogView: {
      type: 'object',
      additionalProperties: false,
      required: [
        'id', 'callKind', 'businessRef', 'generationNo', 'attemptNo', 'provider', 'model',
        'status', 'createdAt',
      ],
      properties: {
        id: ref('Id'),
        sessionId: { type: 'string', pattern: '^-?[0-9]+$', nullable: true },
        userMessageId: { type: 'string', pattern: '^-?[0-9]+$', nullable: true },
        callKind: { type: 'string', enum: ['GENERATION', 'SUMMARY', 'EMBEDDING'] },
        businessRef: { type: 'string', maxLength: 128 },
        generationNo: { type: 'integer', minimum: 0 },
        attemptNo: { type: 'integer', minimum: 1 },
        provider: { type: 'string', maxLength: 64 },
        model: { type: 'string', maxLength: 128 },
        status: { type: 'string', enum: ['CALLING', 'COMPLETED', 'FAILED', 'FAILED_UNKNOWN'] },
        promptVersion: { type: 'string', maxLength: 32, nullable: true },
        safetyVersion: { type: 'string', maxLength: 32, nullable: true },
        promptTokens: { type: 'integer', minimum: 0, nullable: true },
        completionTokens: { type: 'integer', minimum: 0, nullable: true },
        cachedTokens: { type: 'integer', minimum: 0, nullable: true },
        latencyMs: { type: 'integer', format: 'int64', minimum: 0, nullable: true },
        startedAt: { type: 'string', format: 'date-time', nullable: true },
        finishedAt: { type: 'string', format: 'date-time', nullable: true },
        errorCode: { type: 'string', maxLength: 64, nullable: true },
        inputSnapshot: { type: 'object', additionalProperties: true, nullable: true },
        createdAt: ref('DateTime'),
      },
    },
    AiCallLogPage: {
      type: 'object', additionalProperties: false, required: ['items', 'page', 'pageSize', 'total'],
      properties: {
        items: { type: 'array', items: ref('AiCallLogView') },
        page: { type: 'integer', minimum: 1 },
        pageSize: { type: 'integer', minimum: 1, maximum: 100 },
        total: { type: 'integer', minimum: 0 },
      },
    },
    AiCallLogPageResponse: {
      type: 'object', additionalProperties: false, required: ['code', 'message', 'data', 'traceId'],
      properties: {
        code: { type: 'string', enum: ['OK'] }, message: { type: 'string' },
        data: ref('AiCallLogPage'), traceId: { type: 'string' },
      },
    },
  }
}

function operationId(method, apiPath) {
  const words = apiPath.split('/').filter(Boolean).map((part) => {
    const raw = part.startsWith('{') ? `by-${part.slice(1, -1)}` : part
    return raw.split(/[-_]/).filter(Boolean).map((word) => word[0].toUpperCase() + word.slice(1)).join('')
  })
  return method.toLowerCase() + words.join('')
}

function isPublic(method, apiPath) {
  return new Set([
    'POST /auth/user/register',
    'POST /auth/user/login',
    'POST /auth/wechat/login',
    'POST /auth/web/login',
    'POST /auth/refresh',
    'GET /policies/current',
    'GET /hospital',
  ]).has(operationKey(method, apiPath))
}

function requiresIdempotency(method, apiPath) {
  return method === 'POST' && !new Set([
    '/auth/user/register',
    '/auth/user/login',
    '/auth/wechat/login',
    '/auth/web/login',
    '/auth/refresh',
    '/admin/users',
    '/admin/roles',
    '/admin/menus',
  ]).has(apiPath)
}

function isAppointmentChangeCollection(apiPath) {
  return /^\/(staff\/)?appointments\/\{appointmentId\}\/change-requests$/.test(apiPath)
}

function requestSchema(method, apiPath) {
  if (method === 'POST' && apiPath === '/staff/knowledge/versions/{knowledgeVersionId}/submit-review') {
    return 'KnowledgeSubmitReviewInput'
  }
  if (method === 'POST' && apiPath === '/staff/knowledge/versions/{knowledgeVersionId}/review') return 'KnowledgeReviewInput'
  if (method === 'POST' && apiPath === '/staff/knowledge/documents') return 'KnowledgeCreateDocumentInput'
  if (method === 'PATCH' && apiPath === '/staff/knowledge/documents/{documentId}') return 'KnowledgePatchDocumentInput'
  if (method === 'POST' && apiPath === '/staff/knowledge/documents/{documentId}/versions') return 'KnowledgeCreateVersionInput'
  if (method === 'PATCH' && apiPath === '/staff/knowledge/versions/{knowledgeVersionId}') return 'KnowledgePatchVersionInput'
  if (!['POST', 'PUT', 'PATCH'].includes(method)) return null
  if (method === 'PUT' && apiPath === '/staff/appointments/{appointmentId}/medical-draft') return 'MedicalDraftSaveInput'
  if (method === 'POST' && apiPath === '/staff/appointments/{appointmentId}/complete') return 'MedicalCompleteInput'
  if (method === 'POST' && apiPath === '/staff/medical-records/{recordId}/revisions') return 'MedicalRevisionInput'
  if (apiPath === '/auth/user/register') return 'RegisterInput'
  if (apiPath === '/auth/user/login' || apiPath === '/auth/web/login') return 'LoginInput'
  if (apiPath === '/auth/wechat/login') return 'WechatLoginInput'
  if (method === 'POST' && apiPath === '/pets') return 'PetInput'
  if (method === 'PATCH' && apiPath === '/pets/{petId}') return 'PetUpdateInput'
  if (method === 'POST' && apiPath === '/pets/{petId}/archive') return 'PetArchiveInput'
  if (method === 'POST' && apiPath === '/pets/{petId}/health-logs') return 'HealthLogInput'
  if (method === 'PATCH' && apiPath === '/pets/{petId}/health-logs/{logId}') return 'HealthLogUpdateInput'
  if (method === 'PATCH' && apiPath === '/admin/pets/{petId}') return 'AdminPetPatchInput'
  if (method === 'POST' && apiPath === '/admin/doctors') return 'AdminDoctorCreateInput'
  if (method === 'PATCH' && apiPath === '/admin/doctors/{doctorId}') return 'AdminDoctorPatchInput'
  if (method === 'POST' && apiPath === '/admin/doctors/{doctorId}/disable') return 'AdminDoctorDisableInput'
  if (method === 'POST' && apiPath === '/admin/doctors/{doctorId}/enable') return 'AdminDoctorEnableInput'
  if (method === 'POST' && apiPath === '/staff/appointments/{appointmentId}/confirm') return 'AppointmentConfirmInput'
  if (method === 'POST' && apiPath === '/staff/appointments/{appointmentId}/reject') return 'AppointmentRejectInput'
  if (method === 'POST' && apiPath === '/staff/appointments/{appointmentId}/start') return 'AppointmentStartInput'
  if (method === 'POST' && apiPath === '/staff/appointments/{appointmentId}/no-show') return 'AppointmentNoShowInput'
  if (method === 'POST' && isAppointmentChangeCollection(apiPath)) return 'AppointmentChangeCreateInput'
  if (method === 'POST' && apiPath === '/change-requests/{changeRequestId}/accept') return 'AppointmentChangeAcceptInput'
  if (method === 'POST' && apiPath === '/change-requests/{changeRequestId}/reject') return 'AppointmentChangeRejectInput'
  if (method === 'POST' && apiPath === '/change-requests/{changeRequestId}/withdraw') return 'AppointmentChangeAcceptInput'
  if (method === 'POST' && /^\/(staff\/)?appointments\/\{appointmentId\}\/cancel$/.test(apiPath)) {
    return 'AppointmentCancelInput'
  }
  if (method === 'PATCH' && apiPath === '/staff/doctor-profile') return 'StaffDoctorPatchInput'
  if (method === 'POST' && apiPath === '/staff/slots') return 'SlotCreateInput'
  if (method === 'PATCH' && apiPath === '/staff/slots/{slotId}') return 'SlotCapacityPatchInput'
  if (method === 'POST' && apiPath === '/staff/slots/{slotId}/close') return 'SlotCloseInput'
  if (method === 'POST' && apiPath === '/staff/slots/{slotId}/open') return 'SlotOpenInput'
  if (method === 'POST' && apiPath === '/staff/health/guides') return 'HealthGuideCreateInput'
  if (method === 'PATCH' && apiPath === '/staff/health/guides/{guideId}') return 'HealthGuidePatchInput'
  if (method === 'POST' && apiPath === '/staff/health/guides/{guideId}/publish') return 'HealthGuidePublishInput'
  if (method === 'POST' && apiPath === '/staff/health/guides/{guideId}/offline') return 'HealthGuideOfflineInput'
  if (method === 'POST' && apiPath === '/staff/health/standards') return 'HealthStandardCreateInput'
  if (method === 'PATCH' && apiPath === '/staff/health/standards/{standardId}') return 'HealthStandardPatchInput'
  if (method === 'POST' && apiPath === '/staff/health/standards/{standardId}/publish') return 'HealthStandardPublishInput'
  if (method === 'POST' && apiPath === '/staff/health/standards/{standardId}/offline') return 'HealthStandardOfflineInput'
  if (method === 'POST' && apiPath === '/staff/triage-rules') return 'TriageRuleCreateInput'
  if (method === 'PATCH' && apiPath === '/staff/triage-rules/{ruleId}') return 'TriageRulePatchInput'
  if (method === 'POST' && apiPath === '/staff/triage-rules/{ruleId}/submit-review') {
    return 'TriageRuleSubmitReviewInput'
  }
  if (method === 'POST' && apiPath === '/staff/triage-rules/{ruleId}/review') return 'TriageRuleReviewInput'
  if (method === 'POST' && apiPath === '/staff/triage-rules/{ruleId}/enable') return 'TriageRuleEnableInput'
  if (method === 'POST' && apiPath === '/staff/triage-rules/{ruleId}/disable') return 'TriageRuleDisableInput'
  if (method === 'POST' && apiPath === '/staff/triage-rules/test') return 'TriageRulePreviewInput'
  if (method === 'POST' && apiPath === '/admin/users') return 'AdminUserCreateInput'
  if (method === 'PATCH' && apiPath === '/admin/users/{userId}') return 'AdminUserPatchInput'
  if (method === 'POST' && apiPath === '/admin/users/{userId}/disable') return 'AdminUserDisableInput'
  if (method === 'POST' && apiPath === '/admin/users/{userId}/enable') return 'AdminUserEnableInput'
  if (method === 'POST' && apiPath === '/admin/users/{userId}/reset-password') return 'AdminPasswordResetInput'
  if (method === 'POST' && apiPath === '/admin/roles') return 'RoleCreateInput'
  if (method === 'PATCH' && apiPath === '/admin/roles/{roleId}') return 'RolePatchInput'
  if (method === 'POST' && apiPath === '/admin/menus') return 'AdminMenuCreateInput'
  if (method === 'PATCH' && apiPath === '/admin/menus/{menuId}') return 'AdminMenuPatchInput'
  if (method === 'PATCH' && apiPath === '/me') return 'ProfilePatchInput'
  if (method === 'POST' && apiPath === '/me/consents') return 'ConsentCommand'
  if (method === 'POST' && apiPath === '/files') return 'FileUploadForm'
  if (method === 'POST' && apiPath === '/files/{fileId}/download-ticket') return 'FileDownloadTicketInput'
  if (method === 'POST' && apiPath === '/files/{fileId}/discard') return 'FileDiscardInput'
  if (method === 'POST' && apiPath === '/notices/{noticeId}/read') return null
  if (method === 'PUT' && apiPath.endsWith('/roles')) return 'UserRolesCommand'
  if (method === 'PUT' && apiPath.endsWith('/menus')) return 'RoleMenusCommand'
  if (method === 'PATCH' || method === 'PUT') return 'VersionCommand'
  if (apiPath === '/appointments') return 'CreateAppointmentInput'
  if (/^\/ai\/sessions\/\{sessionId}\/messages$/.test(apiPath)) return 'MessageInput'
  if (/\{[^}]+}/.test(apiPath)) return 'ReasonVersionCommand'
  return 'GenericCommand'
}

function responseSchema(method, apiPath, detail) {
  if (method === 'POST' && /^\/staff\/knowledge\/versions\/\{knowledgeVersionId\}\/(submit-review|review)$/.test(apiPath)) {
    return 'KnowledgeReviewResponse'
  }
  if (apiPath === '/staff/knowledge/documents') return method === 'GET' ? 'KnowledgeDocumentPageResponse' : 'KnowledgeCreatedDocumentResponse'
  if (apiPath === '/staff/knowledge/documents/{documentId}') return 'KnowledgeDocumentResponse'
  if (apiPath === '/staff/knowledge/documents/{documentId}/versions') {
    return method === 'GET' ? 'KnowledgeVersionPageResponse' : 'KnowledgeCreatedVersionResponse'
  }
  if (apiPath === '/staff/knowledge/versions/{knowledgeVersionId}') return 'KnowledgeVersionDetailResponse'
  if (method === 'GET' && /^\/(staff\/)?pets\/\{petId\}\/medical-records$/.test(apiPath)) return 'MedicalRecordListResponse'
  if (method === 'GET' && apiPath === '/medical-records/{recordId}') return 'MedicalRecordDetailResponse'
  if (method === 'GET' && apiPath === '/medical-records/{recordId}/versions') return 'MedicalVersionListResponse'
  if (apiPath === '/staff/appointments/{appointmentId}/medical-draft') return 'MedicalDraftResponse'
  if (method === 'POST' && apiPath === '/staff/appointments/{appointmentId}/complete') return 'MedicalCompleteResponse'
  if (method === 'POST' && apiPath === '/staff/medical-records/{recordId}/revisions') return 'MedicalRevisionResponse'
  if (method === 'GET' && apiPath === '/hospital') return 'HospitalResponse'
  if (method === 'POST' && apiPath === '/auth/user/register') return 'UserResponse'
  if (method === 'POST' && (
    apiPath === '/auth/user/login' || apiPath === '/auth/wechat/login' || apiPath === '/auth/web/login'
  )) {
    return 'TokenBundleResponse'
  }
  if ((method === 'GET' || method === 'PATCH') && apiPath === '/me') return 'UserSelfResponse'
  if (method === 'GET' && apiPath === '/me/menus') return 'MenuTreeResponse'
  if (method === 'GET' && apiPath === '/policies/current') return 'PoliciesResponse'
  if (method === 'GET' && apiPath === '/me/consents') return 'ConsentListResponse'
  if (method === 'POST' && apiPath === '/me/consents') return 'ConsentStatusResponse'
  if (apiPath === '/pets/{petId}' && (method === 'GET' || method === 'PATCH')) {
    return 'PetDetailResponse'
  }
  if (method === 'POST' && apiPath === '/pets/{petId}/archive') return 'PetArchiveResponse'
  if (method === 'GET' && apiPath === '/pets/{petId}/health-logs') return 'HealthLogPageResponse'
  if (method === 'GET' && apiPath === '/pets/{petId}/timeline') return 'TimelinePageResponse'
  if (method === 'GET' && apiPath === '/staff/dashboard') return 'StaffDashboardResponse'
  if (method === 'GET' && apiPath === '/staff/pets') return 'StaffPetPageResponse'
  if (method === 'GET' && apiPath === '/staff/pets/{petId}') return 'StaffPetDetailResponse'
  if (method === 'GET' && apiPath === '/staff/pets/{petId}/health-logs') return 'HealthLogPageResponse'
  if (method === 'POST' && apiPath === '/pets/{petId}/health-logs') return 'HealthLogResponse'
  if ((method === 'GET' || method === 'PATCH') && apiPath === '/pets/{petId}/health-logs/{logId}') {
    return 'HealthLogResponse'
  }
  if (method === 'GET' && apiPath === '/admin/pets') return 'AdminPetPageResponse'
  if ((method === 'GET' || method === 'PATCH') && apiPath === '/admin/pets/{petId}') {
    return 'AdminPetResponse'
  }
  if (method === 'GET' && apiPath === '/admin/pets/{petId}/health-logs') return 'HealthLogPageResponse'
  if (method === 'GET' && apiPath === '/admin/users') return 'AdminUserPageResponse'
  if (apiPath === '/admin/users/{userId}' || (method === 'POST' && apiPath === '/admin/users')) {
    return 'AdminUserResponse'
  }
  if (method === 'POST' && /^\/admin\/users\/\{userId}\/((disable)|(enable)|(reset-password))$/.test(apiPath)) {
    return 'AdminAccountLifecycleResponse'
  }
  if (method === 'GET' && apiPath === '/admin/roles') return 'RolePageResponse'
  if (apiPath === '/admin/roles/{roleId}' || (method === 'POST' && apiPath === '/admin/roles')) return 'RoleResponse'
  if (method === 'GET' && apiPath === '/admin/menus') return 'AdminMenuTreeResponse'
  if (apiPath === '/admin/menus/{menuId}' || (method === 'POST' && apiPath === '/admin/menus')) {
    return 'AdminMenuResponse'
  }
  if (apiPath === '/admin/users/{userId}/roles') return 'UserRolesResponse'
  if (apiPath === '/admin/roles/{roleId}/menus') return 'RoleMenusResponse'
  if (apiPath === '/files' || apiPath === '/files/{fileId}') return 'FileResponse'
  if (method === 'POST' && apiPath === '/files/{fileId}/download-ticket') return 'FileDownloadTicketResponse'
  if (method === 'POST' && apiPath === '/files/{fileId}/discard') return 'FileDiscardResponse'
  if (method === 'GET' && apiPath === '/notices') return 'NoticePageResponse'
  if (method === 'POST' && apiPath === '/notices/{noticeId}/read') return 'NoticeReadResponse'
  if (method === 'GET' && apiPath === '/admin/audit-logs') return 'AuditLogPageResponse'
  if (method === 'GET' && apiPath === '/admin/ai/call-logs') return 'AiCallLogPageResponse'
  if (method === 'GET' && apiPath === '/pets') return 'PetPageResponse'
  if (method === 'POST' && apiPath === '/pets') return 'PetResponse'
  if (method === 'GET' && apiPath === '/admin/doctors') return 'DoctorProfilePageResponse'
  if (apiPath === '/admin/doctors' || apiPath === '/admin/doctors/{doctorId}'
    || apiPath === '/staff/doctor-profile') return 'DoctorProfileResponse'
  if (apiPath === '/admin/doctors/{doctorId}/disable'
    || apiPath === '/admin/doctors/{doctorId}/enable') return 'DoctorProfileResponse'
  if (method === 'GET' && apiPath === '/doctors') return 'PublicDoctorPageResponse'
  if (method === 'GET' && apiPath === '/doctors/{doctorId}') return 'PublicDoctorResponse'
  if (method === 'GET' && apiPath === '/doctors/{doctorId}/slots') return 'PublicSlotListResponse'
  if (method === 'GET' && apiPath === '/staff/slots') return 'StaffSlotListResponse'
  if (method === 'POST' && apiPath === '/staff/slots') return 'StaffSlotResponse'
  if (apiPath === '/staff/slots/{slotId}'
    || apiPath === '/staff/slots/{slotId}/close'
    || apiPath === '/staff/slots/{slotId}/open') return 'StaffSlotResponse'
  if (method === 'GET' && apiPath === '/staff/health/guides') return 'HealthGuidePageResponse'
  if (apiPath === '/staff/health/guides' || apiPath === '/staff/health/guides/{guideId}') {
    return 'HealthGuideResponse'
  }
  if (apiPath === '/staff/health/guides/{guideId}/publish'
    || apiPath === '/staff/health/guides/{guideId}/offline') return 'HealthGuideResponse'
  if (method === 'GET' && apiPath === '/health/guides') return 'PublicGuidePageResponse'
  if (method === 'GET' && apiPath === '/health/guides/{guideId}') return 'PublicGuideDetailResponse'
  if (method === 'GET' && apiPath === '/staff/health/standards') return 'HealthStandardPageResponse'
  if (apiPath === '/staff/health/standards' || apiPath === '/staff/health/standards/{standardId}') {
    return 'HealthStandardResponse'
  }
  if (apiPath === '/staff/health/standards/{standardId}/publish'
    || apiPath === '/staff/health/standards/{standardId}/offline') return 'HealthStandardResponse'
  if (method === 'GET' && apiPath === '/health/standards') return 'PublicHealthStandardListResponse'
  if (method === 'GET' && apiPath === '/staff/triage-rules') return 'TriageRulePageResponse'
  if (apiPath === '/staff/triage-rules' || apiPath === '/staff/triage-rules/{ruleId}') {
    return 'TriageRuleResponse'
  }
  if (apiPath === '/staff/triage-rules/{ruleId}/submit-review'
    || apiPath === '/staff/triage-rules/{ruleId}/review') return 'TriageRuleResponse'
  if (apiPath === '/staff/triage-rules/{ruleId}/enable'
    || apiPath === '/staff/triage-rules/{ruleId}/disable') return 'TriageRuleResponse'
  if (apiPath === '/staff/triage-rules/test') return 'TriageRulePreviewResponse'
  if (method === 'POST' && apiPath === '/appointments') return 'AppointmentResponse'
  if (method === 'GET' && ['/appointments', '/staff/appointments'].includes(apiPath)) return 'AppointmentReadPageResponse'
  if (method === 'GET' && ['/appointments/{appointmentId}', '/staff/appointments/{appointmentId}'].includes(apiPath)) {
    return 'AppointmentReadDetailResponse'
  }
  if (method === 'GET' && apiPath === '/staff/appointments/{appointmentId}/status-logs') return 'AppointmentStatusResponse'
  if (method === 'POST' && apiPath === '/change-requests/{changeRequestId}/accept') return 'AppointmentChangeAcceptedResponse'
  if (method === 'POST' && /^\/change-requests\/\{changeRequestId\}\/(reject|withdraw)$/.test(apiPath)) {
    return 'AppointmentChangeClosedResponse'
  }
  if (isAppointmentChangeCollection(apiPath)) {
    if (method === 'GET') return 'AppointmentChangeHistoryResponse'
    if (method === 'POST') return 'AppointmentChangeCreatedResponse'
  }
  if (method === 'POST' && /^\/staff\/appointments\/\{appointmentId\}\/(confirm|reject)$/.test(apiPath)) {
    return 'AppointmentDecisionResponse'
  }
  if (method === 'POST' && /^\/staff\/appointments\/\{appointmentId\}\/(start|no-show)$/.test(apiPath)) {
    return 'AppointmentConsultationActionResponse'
  }
  if (method === 'POST' && /^\/(staff\/)?appointments\/\{appointmentId\}\/cancel$/.test(apiPath)) {
    return 'AppointmentCancellationResponse'
  }
  if (method === 'POST' && /^\/ai\/sessions\/\{sessionId}\/messages$/.test(apiPath)) {
    return 'MessageAcceptedResponse'
  }
  if (method === 'GET' && /(分页|列表|历史|日志)/.test(detail)) return 'PageResponse'
  return 'SuccessResponse'
}

function successStatus(method, apiPath) {
  if (method === 'POST' && apiPath === '/me/consents') return '200'
  if (method === 'POST' && apiPath === '/pets/{petId}/health-logs') return '201'
  if (method === 'POST' && apiPath === '/staff/knowledge/documents/{documentId}/versions') return '201'
  if (method === 'POST' && isAppointmentChangeCollection(apiPath)) return '201'
  if (method === 'POST' && (/\/messages$/.test(apiPath) || /\/index-jobs$/.test(apiPath))) return '202'
  if (method === 'POST' && !/\{[^}]+}/.test(apiPath) && !/(login|refresh|logout|test|preview)$/.test(apiPath)) return '201'
  return '200'
}

function audience(method, apiPath) {
  if (isPublic(method, apiPath)) return 'PUBLIC'
  if (method === 'GET' && apiPath === '/me/menus') return 'WEB'
  if (method === 'GET' && apiPath.startsWith('/medical-records/')) return 'AUTHENTICATED'
  if (method === 'POST' && /^\/change-requests\/\{changeRequestId\}\/(accept|reject|withdraw)$/.test(apiPath)) {
    return 'AUTHENTICATED'
  }
  if (apiPath.startsWith('/staff/') || apiPath.startsWith('/admin/') || apiPath === '/auth/web/login') return 'WEB'
  if (apiPath.startsWith('/me') || apiPath.startsWith('/files') || apiPath.startsWith('/notices')) return 'AUTHENTICATED'
  return 'MINI'
}

function buildDocument(design, ownership) {
  const ownerByKey = new Map(ownership.map((entry) => [operationKey(entry.method, entry.path), entry]))
  const sorted = [...design].sort((left, right) =>
    left.path.localeCompare(right.path) || methodOrder.get(left.method) - methodOrder.get(right.method))
  const paths = {}
  for (const entry of sorted) {
    const owner = ownerByKey.get(operationKey(entry.method, entry.path))
    assert.ok(owner, `Missing owner for ${entry.method} ${entry.path}`)
    const parameters = []
    for (const name of [...entry.path.matchAll(/\{([^}]+)}/g)].map((match) => match[1])) {
      parameters.push({ name, in: 'path', required: true, schema: ref('Id'), example: '10001' })
    }
    if (requiresIdempotency(entry.method, entry.path)) parameters.push(parameterRef('IdempotencyKey'))
    if (entry.method === 'GET' && (/(分页|列表|历史|日志)/.test(entry.detail) || isAppointmentChangeCollection(entry.path)
        || /\/medical-records(?:\/\{recordId\}\/versions)?$/.test(entry.path))) {
      parameters.push(parameterRef('Page'), parameterRef('PageSize'))
    }
    if (entry.method === 'GET' && entry.path === '/notices') parameters.push(parameterRef('Unread'))
    if (entry.method === 'GET' && entry.path === '/pets') parameters.push(parameterRef('PetStatus'))
    if (entry.method === 'GET' && entry.path === '/pets/{petId}/timeline') {
      parameters.push(parameterRef('TimelineType'), parameterRef('TimelineCursor'), parameterRef('PageSize'))
    }
    if (entry.method === 'GET' && (
      entry.path === '/pets/{petId}/health-logs' || entry.path === '/admin/pets/{petId}/health-logs'
      || entry.path === '/staff/pets/{petId}/health-logs'
    )) {
      parameters.push(parameterRef('HealthLogFrom'), parameterRef('HealthLogTo'))
    }
    if (entry.method === 'GET' && entry.path === '/doctors') {
      if (!parameters.some((parameter) => parameter.$ref === '#/components/parameters/Page')) {
        parameters.push(parameterRef('Page'), parameterRef('PageSize'))
      }
      parameters.push(parameterRef('Specialty'))
    }
    if (entry.method === 'GET' && entry.path === '/staff/slots') {
      parameters.push(parameterRef('DoctorId'), parameterRef('FromDate'), parameterRef('ToDate'))
    }
    if (entry.method === 'GET' && entry.path === '/staff/appointments') {
      parameters.push(parameterRef('AppointmentStatusFilter'), parameterRef('AppointmentDate'), parameterRef('AppointmentPetId'))
    }
    if (entry.method === 'GET' && entry.path === '/doctors/{doctorId}/slots') {
      parameters.push(parameterRef('FromDate'), parameterRef('ToDate'))
    }
    if (entry.method === 'GET' && entry.path === '/admin/audit-logs') {
      parameters.push(
        parameterRef('ActorUserId'), parameterRef('ResourceType'), parameterRef('ResourceId'),
        parameterRef('AuditAction'), parameterRef('From'), parameterRef('To'))
    }
    if (entry.method === 'GET' && entry.path === '/admin/ai/call-logs') {
      parameters.push(
        parameterRef('CallKind'), parameterRef('Provider'), parameterRef('Model'),
        parameterRef('AiCallStatus'), parameterRef('IncludeContext'), parameterRef('From'), parameterRef('To'))
    }
    const bodySchema = requestSchema(entry.method, entry.path)
    const outputSchema = responseSchema(entry.method, entry.path, entry.detail)
    const status = successStatus(entry.method, entry.path)
    const operation = {
      operationId: operationId(entry.method, entry.path),
      tags: [owner.ownerTask.split('-')[0]],
      summary: entry.detail.slice(0, 120),
      description: `权限：${entry.permission}。${entry.detail}`,
      security: isPublic(entry.method, entry.path) ? [] : [{ BearerAuth: [] }],
      'x-owner-task': owner.ownerTask,
      'x-audience': audience(entry.method, entry.path),
      'x-implementation-status': implementedOperations.has(operationKey(entry.method, entry.path)) ? 'IMPLEMENTED' : 'PLANNED',
      'x-request-schema': bodySchema,
      'x-response-schema': outputSchema,
      responses: {
        [status]: {
          description: status === '202' ? '已接受异步处理' : '成功',
          content: { 'application/json': { schema: ref(outputSchema) } },
        },
        '400': responseRef('ValidationError'),
        '401': responseRef('Unauthenticated'),
        '403': responseRef('Forbidden'),
        '404': responseRef('NotFound'),
        '409': responseRef('Conflict'),
        '422': responseRef('Unprocessable'),
        '429': responseRef('RateLimited'),
        '503': responseRef('DependencyUnavailable'),
        default: responseRef('Error'),
      },
    }
    if (parameters.length) operation.parameters = parameters
    if (bodySchema) {
      operation.requestBody = {
        required: true,
        content: entry.path === '/files'
          ? {
              'multipart/form-data': {
                schema: {
                  type: 'object',
                  required: ['file', 'purpose'],
                  properties: {
                    file: { type: 'string', format: 'binary' },
                    purpose: { type: 'string' },
                  },
                },
              },
            }
          : { 'application/json': { schema: ref(bodySchema) } },
      }
    }
    paths[entry.path] ??= {}
    paths[entry.path][entry.method.toLowerCase()] = operation
  }

  const errorResponse = (description) => ({
    description,
    content: { 'application/json': { schema: ref('ErrorResponse') } },
  })
  return {
    openapi: '3.0.3',
    info: {
      title: 'AI 问诊宠物医院系统 API',
      version: '1.0.0',
      description: '冻结的设计契约。PLANNED 操作不表示控制器已实现；实现状态由 x-implementation-status 标注。',
    },
    servers: [{ url: '/api/v1', description: '统一业务基路径' }],
    tags: [...new Set(ownership.map((entry) => entry.ownerTask.split('-')[0]))].sort().map((name) => ({ name })),
    paths,
    components: {
      securitySchemes: {
        BearerAuth: { type: 'http', scheme: 'bearer', bearerFormat: 'JWT' },
      },
      parameters: {
        IdempotencyKey: {
          name: 'Idempotency-Key',
          in: 'header',
          required: true,
          schema: { type: 'string', minLength: 16, maxLength: 64 },
          description: '客户端随机幂等键；同一用户不能跨操作复用。',
        },
        Page: { name: 'page', in: 'query', schema: { type: 'integer', minimum: 1, default: 1 } },
        PageSize: { name: 'pageSize', in: 'query', schema: { type: 'integer', minimum: 1, maximum: 100, default: 20 } },
        Unread: { name: 'unread', in: 'query', schema: { type: 'boolean' }, description: 'true仅未读，false仅已读；省略则全部。' },
        PetStatus: { name: 'status', in: 'query', schema: { type: 'string', enum: ['ACTIVE', 'ARCHIVED'] } },
        TimelineType: { name: 'type', in: 'query', schema: ref('TimelineType'), description: '省略时返回全部类别。' },
        TimelineCursor: {
          name: 'cursor', in: 'query', schema: { type: 'string', minLength: 1, maxLength: 96 },
          description: '沿用上一页nextCursor和相同分类。按业务时间、数值ID、类别降序。',
        },
        Specialty: { name: 'specialty', in: 'query', schema: { type: 'string', maxLength: 64 } },
        DoctorId: { name: 'doctorId', in: 'query', schema: ref('Id') },
        AppointmentStatusFilter: { name: 'status', in: 'query', schema: ref('AppointmentStatus') },
        AppointmentDate: { name: 'date', in: 'query', schema: { type: 'string', format: 'date' }, description: '预约安排日期，按医院 Asia/Shanghai 自然日计算。' },
        AppointmentPetId: { name: 'petId', in: 'query', schema: ref('Id') },
        FromDate: { name: 'fromDate', in: 'query', required: true, schema: { type: 'string', format: 'date' } },
        ToDate: { name: 'toDate', in: 'query', required: true, schema: { type: 'string', format: 'date' } },
        ActorUserId: { name: 'actorUserId', in: 'query', schema: ref('Id') },
        ResourceType: { name: 'resourceType', in: 'query', schema: { type: 'string', maxLength: 64 } },
        ResourceId: { name: 'resourceId', in: 'query', schema: ref('Id') },
        AuditAction: { name: 'action', in: 'query', schema: { type: 'string', maxLength: 128 } },
        From: { name: 'from', in: 'query', schema: ref('DateTime'), description: '带时区ISO-8601，开始含；与to的跨度不超过31天。' },
        To: { name: 'to', in: 'query', schema: ref('DateTime'), description: '带时区ISO-8601，结束不含；省略时使用当前时间。' },
        HealthLogFrom: { name: 'from', in: 'query', schema: ref('DateTime'), description: '观察时间开始（含），带时区ISO-8601；可省略。' },
        HealthLogTo: { name: 'to', in: 'query', schema: ref('DateTime'), description: '观察时间结束（不含），带时区ISO-8601；可省略。' },
        CallKind: { name: 'callKind', in: 'query', schema: { type: 'string', enum: ['GENERATION', 'SUMMARY', 'EMBEDDING'] } },
        Provider: { name: 'provider', in: 'query', schema: { type: 'string', maxLength: 64 } },
        Model: { name: 'model', in: 'query', schema: { type: 'string', maxLength: 128 } },
        AiCallStatus: { name: 'status', in: 'query', schema: { type: 'string', enum: ['CALLING', 'COMPLETED', 'FAILED', 'FAILED_UNKNOWN'] } },
        IncludeContext: { name: 'includeContext', in: 'query', schema: { type: 'boolean', default: false }, description: '需要额外ai-consult:audit权限；默认不返回输入快照。' },
      },
      responses: {
        ValidationError: errorResponse('请求参数不合法'),
        Unauthenticated: errorResponse('未登录或会话失效'),
        Forbidden: errorResponse('无权限或未授权'),
        NotFound: errorResponse('资源不存在或不可见'),
        Conflict: errorResponse('版本、状态、名额或幂等冲突'),
        Unprocessable: errorResponse('业务前置条件不满足'),
        RateLimited: errorResponse('请求超过频率限制'),
        DependencyUnavailable: errorResponse('同步依赖暂时不可用'),
        Error: errorResponse('受控错误响应'),
      },
      schemas: schemas(),
    },
  }
}

function resolveRef(document, reference) {
  assert.ok(reference.startsWith('#/'), `Only local refs are allowed: ${reference}`)
  let value = document
  for (const token of reference.slice(2).split('/').map((part) => part.replaceAll('~1', '/').replaceAll('~0', '~'))) {
    assert.ok(value && Object.hasOwn(value, token), `Unresolved ref: ${reference}`)
    value = value[token]
  }
  return value
}

function walk(value, visit) {
  if (Array.isArray(value)) {
    value.forEach((item) => walk(item, visit))
  } else if (value && typeof value === 'object') {
    visit(value)
    Object.values(value).forEach((item) => walk(item, visit))
  }
}

function validateExample(document, schema, example, location) {
  if (schema.$ref) return validateExample(document, resolveRef(document, schema.$ref), example, location)
  if (example === null) {
    assert.equal(schema.nullable, true, `${location} is null but schema is not nullable`)
    return
  }
  if (schema.enum) assert.ok(schema.enum.includes(example), `${location} is outside enum`)
  if (schema.type === 'string') {
    assert.equal(typeof example, 'string', `${location} must be string`)
    if (schema.pattern) assert.match(example, new RegExp(schema.pattern), `${location} does not match pattern`)
  } else if (schema.type === 'integer') {
    assert.ok(Number.isInteger(example), `${location} must be integer`)
  } else if (schema.type === 'number') {
    assert.equal(typeof example, 'number', `${location} must be number`)
  } else if (schema.type === 'boolean') {
    assert.equal(typeof example, 'boolean', `${location} must be boolean`)
  } else if (schema.type === 'array') {
    assert.ok(Array.isArray(example), `${location} must be array`)
    example.forEach((item, index) => validateExample(document, schema.items, item, `${location}[${index}]`))
  } else if (schema.type === 'object') {
    assert.ok(example && typeof example === 'object' && !Array.isArray(example), `${location} must be object`)
    for (const required of schema.required ?? []) {
      assert.ok(Object.hasOwn(example, required), `${location} misses required property ${required}`)
    }
    for (const [name, value] of Object.entries(example)) {
      if (schema.properties?.[name]) validateExample(document, schema.properties[name], value, `${location}.${name}`)
      else assert.notEqual(schema.additionalProperties, false, `${location}.${name} is not allowed`)
    }
  }
}

function schemaHasVersion(document, schema) {
  const resolved = schema.$ref ? resolveRef(document, schema.$ref) : schema
  return ['version', 'userVersion', 'roleVersion'].some((name) => resolved.properties?.[name])
}

function validateDocument(document) {
  assert.equal(document.openapi, '3.0.3')
  const operationEntries = []
  for (const [apiPath, pathItem] of Object.entries(document.paths)) {
    for (const [method, operation] of Object.entries(pathItem)) {
      if (!methods.includes(method.toUpperCase())) continue
      operationEntries.push({ method: method.toUpperCase(), path: apiPath, operation })
    }
  }
  assert.equal(operationEntries.length, expectedOperationCount, 'OpenAPI operation count mismatch')
  assert.equal(new Set(operationEntries.map((entry) => operationKey(entry.method, entry.path))).size, expectedOperationCount)
  assert.equal(new Set(operationEntries.map((entry) => entry.operation.operationId)).size, expectedOperationCount)
  assert.equal(
    operationEntries.filter((entry) => entry.operation['x-implementation-status'] === 'IMPLEMENTED').length,
    implementedOperations.size,
  )
  for (const key of implementedOperations) {
    const [method, apiPath] = key.split(' ')
    assert.equal(document.paths[apiPath][method.toLowerCase()]['x-implementation-status'], 'IMPLEMENTED')
  }

  walk(document, (node) => {
    if (node.$ref) resolveRef(document, node.$ref)
  })

  for (const [name, schema] of Object.entries(document.components.schemas)) {
    if (Object.hasOwn(schema, 'example')) validateExample(document, schema, schema.example, `components.schemas.${name}.example`)
    for (const [propertyName, propertySchema] of Object.entries(schema.properties ?? {})) {
      if (!/(^id$|Id$|Ids$)/.test(propertyName)) continue
      const resolved = propertySchema.$ref ? resolveRef(document, propertySchema.$ref) : propertySchema
      if (resolved.type === 'array') {
        const item = resolved.items.$ref ? resolveRef(document, resolved.items.$ref) : resolved.items
        assert.equal(item.type, 'string', `${name}.${propertyName} array items must be string IDs`)
      } else {
        assert.equal(resolved.type, 'string', `${name}.${propertyName} must be a string ID`)
      }
    }
  }

  for (const entry of operationEntries) {
    const key = operationKey(entry.method, entry.path)
    const pathNames = [...entry.path.matchAll(/\{([^}]+)}/g)].map((match) => match[1])
    for (const name of pathNames) {
      const parameter = (entry.operation.parameters ?? []).find((item) => item.name === name && item.in === 'path')
      assert.ok(parameter, `${key} misses path parameter ${name}`)
      assert.equal(resolveRef(document, parameter.schema.$ref).type, 'string', `${key} path ID must be string`)
    }
    if (requiresIdempotency(entry.method, entry.path)) {
      assert.ok((entry.operation.parameters ?? []).some((item) => item.$ref === '#/components/parameters/IdempotencyKey'), `${key} misses Idempotency-Key`)
    }
    if (entry.method === 'PUT' || entry.method === 'PATCH') {
      const schema = entry.operation.requestBody?.content?.['application/json']?.schema
      assert.ok(schema && schemaHasVersion(document, schema), `${key} must carry a version field`)
    }
    assert.ok(entry.operation.responses.default?.$ref === '#/components/responses/Error', `${key} misses ErrorResponse`)
  }
  return operationEntries
}

function typeScriptType(schema) {
  if (schema.$ref) return schema.$ref.split('/').at(-1)
  if (schema.enum) return schema.enum.map((value) => JSON.stringify(value)).join(' | ')
  if (schema.type === 'string') return 'string'
  if (schema.type === 'integer' || schema.type === 'number') return 'number'
  if (schema.type === 'boolean') return 'boolean'
  if (schema.type === 'array') return `Array<${typeScriptType(schema.items)}>`
  if (schema.type === 'object') {
    if (!schema.properties) return 'Record<string, unknown>'
    const required = new Set(schema.required ?? [])
    const fields = Object.entries(schema.properties).map(([name, value]) => `${JSON.stringify(name)}${required.has(name) ? '' : '?'}: ${typeScriptType(value)}`)
    return `{ ${fields.join('; ')} }`
  }
  return 'unknown'
}

function generateTypes(document, sourceHash) {
  const lines = [
    '/* eslint-disable */',
    '// Generated by tools/openapi-contract.mjs. Do not edit by hand.',
    `// Source SHA-256: ${sourceHash}`,
    '',
  ]
  for (const [name, schema] of Object.entries(document.components.schemas)) {
    if (schema.type === 'object' && schema.properties) {
      lines.push(`export interface ${name} {`)
      const required = new Set(schema.required ?? [])
      for (const [propertyName, propertySchema] of Object.entries(schema.properties)) {
        const nullable = propertySchema.nullable ? ' | null' : ''
        lines.push(`  ${JSON.stringify(propertyName)}${required.has(propertyName) ? '' : '?'}: ${typeScriptType(propertySchema)}${nullable}`)
      }
      if (schema.additionalProperties === true) lines.push('  [key: string]: unknown')
      lines.push('}', '')
    } else {
      lines.push(`export type ${name} = ${typeScriptType(schema)}`, '')
    }
  }
  lines.push('export interface ApiOperations {')
  for (const [apiPath, pathItem] of Object.entries(document.paths)) {
    for (const [method, operation] of Object.entries(pathItem)) {
      const request = operation['x-request-schema'] ?? 'never'
      const response = operation['x-response-schema']
      lines.push(`  ${JSON.stringify(operation.operationId)}: {`)
      lines.push(`    method: ${JSON.stringify(method.toUpperCase())}`)
      lines.push(`    path: ${JSON.stringify(apiPath)}`)
      lines.push(`    request: ${request}`)
      lines.push(`    response: ${response}`)
      lines.push(`    ownerTask: ${JSON.stringify(operation['x-owner-task'])}`)
      lines.push('  }')
    }
  }
  lines.push('}', '', 'export interface ApiPaths {')
  for (const [apiPath, pathItem] of Object.entries(document.paths)) {
    lines.push(`  ${JSON.stringify(apiPath)}: {`)
    for (const [method, operation] of Object.entries(pathItem)) {
      lines.push(`    ${JSON.stringify(method)}: ApiOperations[${JSON.stringify(operation.operationId)}]`)
    }
    lines.push('  }')
  }
  lines.push('}', '')
  const output = `${lines.join('\n')}\n`
  assert.doesNotMatch(output, /(?:\bid\b|Id|Ids)["']?\??:\s*number\b/, 'Generated TS contains a numeric ID')
  return output
}

function generateCoverage(document) {
  const rows = ['# API 覆盖登记表', '', `由\`tools/openapi-contract.mjs\`生成。共${expectedOperationCount}个唯一方法+路径；PLANNED只表示设计已冻结，不表示控制器已实现。`, '', '| # | 方法 | 路径 | 主责任任务 | 实现状态 |', '|---:|---|---|---|---|']
  let index = 0
  for (const [apiPath, pathItem] of Object.entries(document.paths)) {
    for (const [method, operation] of Object.entries(pathItem)) {
      index += 1
      rows.push(`| ${index} | ${method.toUpperCase()} | \`${apiPath}\` | ${operation['x-owner-task']} | ${operation['x-implementation-status']} |`)
    }
  }
  return `${rows.join('\n')}\n`
}

function writeOrCheck(relativePath, content) {
  const target = path.join(root, relativePath)
  if (mode === 'generate') {
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, content, 'utf8')
    return
  }
  assert.ok(fs.existsSync(target), `${relativePath} is missing; run generate`)
  assert.equal(fs.readFileSync(target, 'utf8'), content, `${relativePath} is stale; run generate`)
}

const design = parseDesignOperations()
const ownership = parseOwnershipOperations()
assertExactCoverage(design, ownership)
const document = buildDocument(design, ownership)
const operations = validateDocument(document)
const openApiText = `${JSON.stringify(document, null, 2)}\n`
const sourceHash = crypto.createHash('sha256').update(openApiText).digest('hex')
const generatedTypes = generateTypes(document, sourceHash)
const coverage = generateCoverage(document)

writeOrCheck('doc/openapi.yaml', openApiText)
writeOrCheck('doc/api-coverage.md', coverage)
writeOrCheck('web/src/generated/api-types.ts', generatedTypes)
writeOrCheck('miniapp/generated/api-types.ts', generatedTypes)

if (mode === 'check') {
  const parsed = JSON.parse(readText('doc/openapi.yaml'))
  validateDocument(parsed)
}

console.log(`OpenAPI ${mode}: ${operations.length} unique operations, ${Object.keys(document.components.schemas).length} schemas, refs/examples/IDs valid, source=${sourceHash.slice(0, 12)}`)
