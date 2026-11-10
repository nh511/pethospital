package com.pethospital.health;

import com.pethospital.health.HealthStandardDtos.PublicStandardView;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.shared.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicHealthStandardService {
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;

    public PublicHealthStandardService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
    }

    @Transactional(readOnly = true)
    public List<PublicStandardView> list(String speciesCode, Integer ageMonths, String metricCode) {
        requireUser();
        String species = code(speciesCode);
        String metric = code(metricCode);
        if (ageMonths == null) {
            return jdbc().query("""
                SELECT id,name,species_code,age_min_months,age_max_months,metric_code,unit,
                       reference_min,reference_max,description,conditions_note,source_note
                FROM health_standard
                WHERE status='PUBLISHED' AND species_code=? AND metric_code=?
                  AND age_min_months IS NULL AND age_max_months IS NULL
                ORDER BY name ASC,id ASC
                """, (result, rowNumber) -> view(result), species, metric);
        }
        return jdbc().query("""
            SELECT id,name,species_code,age_min_months,age_max_months,metric_code,unit,
                   reference_min,reference_max,description,conditions_note,source_note
            FROM health_standard
            WHERE status='PUBLISHED' AND species_code=? AND metric_code=?
              AND ((age_min_months IS NULL AND age_max_months IS NULL)
                   OR (age_min_months<=? AND age_max_months>=?))
            ORDER BY name ASC,id ASC
            """, (result, rowNumber) -> view(result), species, metric, ageMonths, ageMonths);
    }

    private CurrentIdentity requireUser() {
        CurrentIdentity actor = identities.require();
        if (!"MINI".equals(actor.clientType()) || !actor.roles().contains("USER")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅小程序用户可查询公开健康标准");
        }
        return actor;
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "数据库不可用");
        }
        return new JdbcTemplate(dataSource);
    }

    private static PublicStandardView view(ResultSet result) throws SQLException {
        return new PublicStandardView(
            Long.toString(result.getLong("id")), result.getString("name"),
            result.getString("species_code"), result.getObject("age_min_months", Integer.class),
            result.getObject("age_max_months", Integer.class), result.getString("metric_code"),
            result.getString("unit"), result.getBigDecimal("reference_min"),
            result.getBigDecimal("reference_max"), result.getString("description"),
            result.getString("conditions_note"), result.getString("source_note")
        );
    }

    private static String code(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{2,64}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_HEALTH_STANDARD_QUERY", "查询代码不合法");
        }
        return normalized;
    }
}
