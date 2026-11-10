package com.pethospital.health;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.pethospital.health.HealthGuideDtos.PublicGuideDetail;
import com.pethospital.health.HealthGuideDtos.PublicGuidePage;
import com.pethospital.health.HealthGuideDtos.PublicGuideSummary;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.shared.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicHealthGuideService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;
    private final GuideHtmlSanitizer html;
    private final Clock clock;

    public PublicHealthGuideService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities,
        GuideHtmlSanitizer html,
        Clock clock
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
        this.html = html;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PublicGuidePage list(
        int page,
        int pageSize,
        String speciesCode,
        String categoryCode,
        String keyword
    ) {
        requireUser();
        List<Object> arguments = new ArrayList<>();
        String where = publicFilters(speciesCode, categoryCode, keyword, arguments);
        JdbcTemplate jdbc = jdbc();
        Long total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM health_guide" + where,
            Long.class,
            arguments.toArray()
        );
        arguments.add(pageSize);
        arguments.add(Math.multiplyExact((long) page - 1L, pageSize));
        List<PublicGuideSummary> items = jdbc.query("""
            SELECT id,title,species_code,category_code,cover_file_id,source_note,published_at
            FROM health_guide
            """ + where + " ORDER BY published_at DESC,id DESC LIMIT ? OFFSET ?",
            (result, rowNumber) -> summary(result), arguments.toArray());
        return new PublicGuidePage(items, page, pageSize, total == null ? 0 : total);
    }

    @Transactional
    public PublicGuideDetail get(long guideId) {
        CurrentIdentity actor = requireUser();
        JdbcTemplate jdbc = jdbc();
        PublicGuideDetail detail = jdbc.query("""
            SELECT id,title,species_code,category_code,body,cover_file_id,source_note,published_at
            FROM health_guide WHERE id=? AND status='PUBLISHED'
            """, result -> result.next() ? detail(result) : null, guideId);
        if (detail == null) {
            throw new BusinessException(
                HttpStatus.NOT_FOUND,
                "HEALTH_GUIDE_NOT_FOUND",
                "健康指南不存在或未发布"
            );
        }
        LocalDate viewDate = LocalDate.now(clock.withZone(SHANGHAI));
        jdbc.update("""
            INSERT IGNORE INTO guide_view_event(id,guide_id,user_id,view_date)
            VALUES (?,?,?,?)
            """, IdWorker.getId(), guideId, actor.userId(), viewDate);
        return new PublicGuideDetail(
            detail.guideId(), detail.title(), detail.speciesCode(), detail.categoryCode(),
            html.sanitize(detail.body()), detail.coverFileId(), detail.sourceNote(), detail.publishedAt()
        );
    }

    private CurrentIdentity requireUser() {
        CurrentIdentity actor = identities.require();
        if (!"MINI".equals(actor.clientType()) || !actor.roles().contains("USER")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅小程序用户可浏览健康指南");
        }
        return actor;
    }

    private static String publicFilters(
        String speciesCode,
        String categoryCode,
        String keyword,
        List<Object> arguments
    ) {
        StringBuilder where = new StringBuilder(" WHERE status='PUBLISHED'");
        if (speciesCode != null && !speciesCode.isBlank()) {
            where.append(" AND species_code=?");
            arguments.add(code(speciesCode));
        }
        if (categoryCode != null && !categoryCode.isBlank()) {
            where.append(" AND category_code=?");
            arguments.add(code(categoryCode));
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND title LIKE ? ESCAPE '\\\\'");
            arguments.add("%" + like(keyword.strip()) + "%");
        }
        return where.toString();
    }

    private static PublicGuideSummary summary(ResultSet result) throws SQLException {
        Long coverId = result.getObject("cover_file_id", Long.class);
        return new PublicGuideSummary(
            Long.toString(result.getLong("id")), result.getString("title"),
            result.getString("species_code"), result.getString("category_code"),
            coverId == null ? null : Long.toString(coverId), result.getString("source_note"),
            instant(result.getTimestamp("published_at"))
        );
    }

    private static PublicGuideDetail detail(ResultSet result) throws SQLException {
        Long coverId = result.getObject("cover_file_id", Long.class);
        return new PublicGuideDetail(
            Long.toString(result.getLong("id")), result.getString("title"),
            result.getString("species_code"), result.getString("category_code"),
            result.getString("body"), coverId == null ? null : Long.toString(coverId),
            result.getString("source_note"), instant(result.getTimestamp("published_at"))
        );
    }

    private static String code(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(java.util.Locale.ROOT);
        if (normalized.isEmpty() || !normalized.matches("[A-Z0-9_]{2,64}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_HEALTH_GUIDE_QUERY", "查询代码不合法");
        }
        return normalized;
    }

    private static String like(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String instant(Timestamp value) {
        return value == null ? null : value.toInstant().toString();
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "数据库不可用");
        }
        return new JdbcTemplate(dataSource);
    }
}
