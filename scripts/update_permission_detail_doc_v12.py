from datetime import datetime
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.shared import Pt

from update_permission_detail_doc import (
    LIGHT_GRAY,
    LIGHT_BLUE,
    NAVY,
    OUT as _V11_OUT,
    add_box,
    add_bullet,
    add_heading,
    add_table,
    add_text,
    find_table,
    move_before,
    set_run_font,
)


ROOT = Path(r"G:\Projects\jsd-aird")
SOURCE = ROOT / "docs" / "杰事达材料研发系统_系统权限管理详细设计说明书_V1.1.docx"
OUT = ROOT / "docs" / "杰事达材料研发系统_系统权限管理详细设计说明书_V1.2.docx"


def add_number(doc, text):
    p = doc.add_paragraph(style="List Number")
    p.paragraph_format.space_after = Pt(3)
    r = p.add_run(text)
    set_run_font(r, size=10.0)
    return p


def update_version_and_remove_sso(doc):
    metadata = doc.tables[0]
    for row in metadata.rows:
        if row.cells[0].text.strip() == "版本":
            row.cells[1].text = "V1.2"
        if row.cells[0].text.strip() == "状态":
            row.cells[1].text = "设计补充"

    history = doc.tables[1]
    row = history.add_row()
    for cell, value in zip(row.cells, [
        "V1.2",
        "2026-08-19",
        "进一步补充 Spring Security 认证、数据库会话、CSRF、过滤链、错误处理和测试设计；明确本期不实现 SSO、LDAP、OAuth2 或外部身份源。",
        "设计补充",
    ]):
        cell.text = value

    # Remove all external identity wording from existing paragraphs and cells.
    for para in doc.paragraphs:
        if "SSO" in para.text or "LDAP" in para.text or "身份源" in para.text:
            para.text = para.text.replace("SSO", "外部身份接入").replace("LDAP", "外部目录").replace("身份源", "外部身份源")
    for table in doc.tables:
        for row in table.rows:
            for cell in row.cells:
                if "SSO" in cell.text or "LDAP" in cell.text or "身份源" in cell.text:
                    cell.text = cell.text.replace("本地账号 + 会话；预留SSO适配器", "本地账号 + 会话（本期唯一认证方式）")
                    cell.text = cell.text.replace("SSO", "外部身份接入").replace("LDAP", "外部目录")

    decisions = find_table(doc, "授权主模型")
    if decisions:
        for row in decisions.rows:
            if row.cells[0].text.strip() == "登录方式":
                row.cells[1].text = "本地账号 + 会话（本期唯一认证方式）"
                row.cells[2].text = "一期只支持本地账号密码登录；使用 Spring Security + 数据库会话，不实现 SSO、LDAP、OAuth2 或其他外部身份源。"

    doc.core_properties.title = "杰事达材料研发系统_系统权限管理详细设计说明书_V1.2"
    doc.core_properties.subject = "统一权限管理详细设计与 Spring Security 实现方案"
    doc.core_properties.comments = "V1.2 补充 Spring Security 认证链路，并明确本期不实现外部身份接入"
    doc.core_properties.modified = datetime.now()


def insert_spring_security_design(doc):
    anchor = next((p for p in doc.paragraphs if p.text.strip() == "9. 权限缓存、审计与可观测性"), None)
    if anchor is None:
        raise RuntimeError("未找到第 9 章标题，无法插入 Spring Security 详细设计")

    blocks = []
    blocks.append(add_heading(doc, "8.1. Spring Security 组件职责", 2))
    blocks.append(add_text(doc, "本期采用 Spring Security 作为 Web 认证和通用安全基础设施，但不使用 Spring Security 的默认角色模型替代 IAM 业务权限。Spring Security 负责确认请求来自哪个已登录用户以及请求是否满足通用 Web 安全要求；iam::api 的 AuthorizationService 负责权限动作、数据范围、资源对象和 AI 外发判断。"))
    blocks.append(add_box(doc, "边界原则", "Spring Security 解决“请求是否有可信身份”和“请求是否满足通用安全策略”；AuthorizationService 解决“这个用户是否能对这个资源执行这个动作，以及能看到哪些数据”。两者不能互相替代。"))
    blocks.append(add_table(doc, ["组件", "技术实现", "职责", "禁止做法"], [
        ["SecurityFilterChain", "Spring Security FilterChain；API JSON EntryPoint/AccessDeniedHandler；CSRF；安全响应头；STATELESS", "统一拦截、建立请求安全上下文、输出 401/403 JSON", "不在 URL matcher 中写完整业务权限矩阵，不用隐藏路由替代后端判权"],
        ["IamAuthenticationProvider", "AuthenticationProvider + UserDetails/Principal 适配；Argon2PasswordEncoder", "校验本地用户名/密码、账号状态和登录失败策略", "不把角色权限一次性复制为完整 GrantedAuthority"],
        ["SessionAuthenticationFilter", "OncePerRequestFilter；读取 HttpOnly opaque Cookie，哈希后查询 iam.login_session", "校验 token、过期、撤销、auth_version 和账号状态，建立当前请求身份", "不信任 X-User-Id、X-Role、X-Organization-Id 等请求头"],
        ["SecurityContext", "只保存当前请求的 Authentication；不使用 Servlet HttpSession 作为会话真源", "让 Controller、应用服务和审计获得可信 Actor", "不在 SecurityContext 中缓存业务数据、完整权限矩阵或数据范围目标"],
        ["AuthorizationService", "iam::api 公开接口；业务模块应用服务调用", "计算角色默认、个人覆盖、动作授权、数据范围和 AI 门禁", "不通过 hasRole/hasAuthority 单独处理动态数据范围"],
        ["SessionStore", "iam.infrastructure 的数据库会话端口；MyBatis-Plus Repository 实现", "创建、查询、撤销和批量失效会话；只保存 token_hash", "不保存明文 token，不把会话放入前端 localStorage"],
        ["AuditLogFacade", "ops::api；落库到 ops.audit_log", "记录登录失败、登录、退出、撤销、禁用、权限拒绝和高风险允许", "不只写 Logback 文本日志，不记录原始密码或敏感材料正文"],
    ], [1500, 2750, 3000, 2110], font_size=7.9))

    blocks.append(add_heading(doc, "8.2. 本地账号登录流程", 2))
    for text in [
        "客户端 POST /api/v1/auth/login，提交 username、password 和可选的 rememberMe；请求必须经过限流和 CSRF 策略校验。",
        "LoginApplicationService 调用 AuthenticationManager，由 IamAuthenticationProvider 查询启用账号并使用 Argon2PasswordEncoder.matches 校验密码。",
        "校验失败时递增 iam.login_attempt，按安全配置触发短时锁定，返回统一 AUTH_INVALID，不泄露账号是否存在。",
        "校验成功后生成不可预测的 opaque token，只将 token_hash、user_id、auth_version、expires_at、created_at 写入 iam.login_session。",
        "服务端通过 Set-Cookie 写入 JSD_SESSION；Cookie 设置 HttpOnly、Secure（生产）、SameSite=Lax，禁止写入 localStorage。",
        "记录 AUTH_LOGIN_SUCCESS 审计；响应只返回当前用户摘要和权限版本，不返回 token 明文和密码相关信息。",
    ]:
        blocks.append(add_number(doc, text))

    blocks.append(add_heading(doc, "8.3. 请求、退出与强制失效流程", 2))
    lifecycle_rows = [
        ["普通请求", "SessionAuthenticationFilter 读取 JSD_SESSION -> SHA-256/HMAC 计算 token_hash -> 查询有效会话 -> 校验用户 status/auth_version -> 建立 Authentication。", "不存在、过期、撤销、版本不一致或账号禁用时返回 AUTH_REQUIRED/ACCOUNT_DISABLED。"],
        ["业务授权", "应用服务从 SecurityContext 取得 Actor，再调用 AuthorizationService.check/scope；对象操作传 ResourceRef，列表操作传 DataScopeFilter。", "Spring Security 认证成功不等于业务动作允许；未通过返回 PERMISSION_DENIED 或 DATA_SCOPE_DENIED。"],
        ["退出登录", "POST /api/v1/auth/logout 撤销当前 session_id，清除 JSD_SESSION Cookie，记录 AUTH_LOGOUT。", "只撤销当前会话；管理员强制下线使用权限校验后批量撤销目标用户会话。"],
        ["禁用/重置密码", "事务内更新 AppUser.status 或 password_changed_at，并递增 auth_version；撤销该用户全部会话。", "旧 Cookie 下一次请求必须失效，不依赖缓存 TTL。"],
        ["权限/范围变更", "事务内保存角色绑定或个人覆盖，递增 policyVersion；清理主体权限缓存并写审计。", "旧权限不能长期生效；并发保存使用 expectedVersion。"],
    ]
    blocks.append(add_table(doc, ["场景", "处理链路", "强制结果"], lifecycle_rows, [1600, 5000, 2760], font_size=8.0, header_fill=LIGHT_GRAY))

    blocks.append(add_heading(doc, "8.4. SecurityFilterChain 基线配置", 2))
    config_rows = [
        ["会话策略", "SessionCreationPolicy.STATELESS", "这里的 STATELESS 仅表示不使用 Servlet HttpSession；应用仍使用 JSD_SESSION + iam.login_session 的自建会话。"],
        ["身份过滤器", "SessionAuthenticationFilter 放在 AnonymousAuthenticationFilter 之前；无 Cookie 时继续匿名请求", "公开接口由 matcher 放行，受保护接口由认证入口返回 JSON 401。"],
        ["请求授权", "公开：/api/v1/auth/login、/actuator/health；其余 API 至少 authenticated()", "具体 permissionCode、ResourceRef 和 DataScopeFilter 在应用服务中判断。"],
        ["CSRF", "CookieCsrfTokenRepository.withHttpOnlyFalse()；前端读取 XSRF-TOKEN 并发送 X-XSRF-TOKEN", "因为会话 Cookie 会自动发送，状态变更接口不能关闭 CSRF。"],
        ["异常处理", "自定义 AuthenticationEntryPoint 与 AccessDeniedHandler", "返回 {code,message,traceId}；API 不重定向到 HTML 登录页。"],
        ["安全响应头", "默认安全头 + 生产 HSTS + Content-Security-Policy 按部署域名配置", "禁止在配置中开启宽泛 frame-src、script-src 或公开跨域凭证。"],
        ["CORS", "优先前后端同源；开发环境只允许配置白名单 origin，并限制 credentials=true 的来源", "禁止 * 与 credentials=true 同时出现。"],
        ["请求缓存", "requestCache.disable()", "权限 API 不保存登录后重放的任意原始请求，避免把敏感请求放入会话。"],
    ]
    blocks.append(add_table(doc, ["配置项", "选型/配置", "设计要求"], config_rows, [1500, 3600, 4260], font_size=8.0))

    blocks.append(add_heading(doc, "8.5. Spring Security 与 IAM 授权服务的调用规则", 2))
    blocks.append(add_text(doc, "Controller 只负责接收请求、取得当前 Actor 和调用应用用例。应用用例必须在写操作、对象读取、下载、导出、批量处理和 AI 调用前调用 AuthorizationService；列表/搜索先获取 DataScopeFilter 再传入 Repository。"))
    blocks.append(add_table(doc, ["场景", "Spring Security", "IAM AuthorizationService"], [
        ["登录态", "判断是否存在可信 Authentication", "读取 Actor 的 organizationId、userId、authVersion 和 sessionId"],
        ["路由入口", "只做 anonymous/authenticated 等粗粒度拦截", "根据 permissionCode、resourceType、resourceId 进行动作授权"],
        ["列表/搜索", "不直接决定可见行", "解析 ALL/SELF/ASSIGNED/PROJECT/CATEGORY/SELECTED 并产生 DataScopeFilter"],
        ["对象操作", "提供当前用户身份", "校验资源归属、组织隔离和目标对象是否在范围内"],
        ["AI 外发", "提供已登录用户身份", "同时校验 AI 动作权限、数据范围、敏感属性、用途和提供方"],
    ], [1800, 3500, 4060], font_size=8.2, header_fill=LIGHT_GRAY))

    blocks.append(add_heading(doc, "8.6. 认证与安全测试补充", 2))
    test_rows = [
        ["SEC-AUTH-001", "正确账号密码登录", "返回成功并设置 HttpOnly/Secure/SameSite Cookie；写入登录审计"],
        ["SEC-AUTH-002", "错误密码或未知用户", "统一 AUTH_INVALID；递增登录失败记录，不泄露账号存在性"],
        ["SEC-AUTH-003", "缺失、伪造或篡改 JSD_SESSION", "返回 AUTH_REQUIRED；不信任请求头身份"],
        ["SEC-AUTH-004", "禁用用户继续使用旧 Cookie", "返回 ACCOUNT_DISABLED；旧会话不能访问受保护 API"],
        ["SEC-AUTH-005", "密码重置或 auth_version 变化后继续请求", "旧 Cookie 失效；新登录可以建立新会话"],
        ["SEC-AUTH-006", "POST/PUT/PATCH/DELETE 缺少 CSRF Token", "返回 403；补齐 X-XSRF-TOKEN 后才允许进入业务授权"],
        ["SEC-AUTH-007", "直接请求隐藏菜单或受保护 API", "页面隐藏不影响验证；后端返回 AUTH_REQUIRED/PERMISSION_DENIED"],
        ["SEC-AUTH-008", "登录、退出、撤销和权限拒绝审计", "包含 actor、sessionId、path、traceId、reasonCode 和策略版本，不包含密码/token 明文"],
    ]
    blocks.append(add_table(doc, ["测试编号", "场景", "预期"], test_rows, [1500, 3600, 4260], font_size=8.0))

    blocks.append(add_heading(doc, "8.7. 依赖和代码落点", 2))
    blocks.append(add_table(doc, ["项目", "变更"], [
        ["Maven 依赖", "新增 spring-boot-starter-security；版本由 Spring Boot 3.5.16 的 dependency management 管理，不单独覆盖 Spring Security 版本。"],
        ["IAM 安全包", "jsd-aird-api/src/main/java/com/jsd/aird/iam/adapter/in/security：IamSecurityConfiguration、SessionAuthenticationFilter、ApiAuthenticationEntryPoint、ApiAccessDeniedHandler。"],
        ["IAM 应用层", "iam/application：LoginApplicationService、LogoutApplicationService、SessionService、IamAuthenticationProvider。"],
        ["IAM 基础设施", "iam/infrastructure/security 与 persistence：SessionStore、PasswordHasher、LoginAttemptRepository、LoginSessionRepository。"],
        ["共享安全值对象", "shared/security：Actor、ActorContext、SecurityPrincipal；只作为跨模块身份值对象，不承载业务权限判定。"],
        ["前端", "jsd-aird-web/src/stores/app-store.ts 与 services/auth：保存用户摘要和权限版本；Axios 统一 Cookie/CSRF/401/403/409。"],
        ["测试", "后端使用 MockMvc/ Spring Security Test + Testcontainers PostgreSQL；前端使用 Vitest/Testing Library/Playwright。"],
    ], [2200, 7160], font_size=8.2))

    for block in blocks:
        element = block._p if hasattr(block, "_p") else block._tbl
        move_before(anchor, element)


def main():
    doc = Document(str(SOURCE))
    update_version_and_remove_sso(doc)
    insert_spring_security_design(doc)
    doc.save(str(OUT))
    print(OUT)


if __name__ == "__main__":
    main()
