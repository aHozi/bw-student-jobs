package de.bwjobs.ai;

import de.bwjobs.common.ApiException;
import de.bwjobs.common.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Per-user daily limit on AI calls to control cost. */
@Component
public class AiQuota {
    private final JdbcClient jdbc;
    private final int limit;

    public AiQuota(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.limit = props.ai().dailyLimitPerUser();
    }

    public void consume(long userId) {
        int calls = jdbc.sql("""
                insert into ai_usage(user_id, day, calls) values (?, current_date, 1)
                on conflict (user_id, day) do update set calls = ai_usage.calls + 1 returning calls""")
                .param(userId).query(Integer.class).single();
        if (calls > limit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Daily AI limit reached (" + limit + "). Try again tomorrow.");
        }
    }

    public int usedToday(long userId) {
        return jdbc.sql("select coalesce((select calls from ai_usage where user_id = ? and day = current_date), 0)")
                .param(userId).query(Integer.class).single();
    }

    public int limit() {
        return limit;
    }
}
