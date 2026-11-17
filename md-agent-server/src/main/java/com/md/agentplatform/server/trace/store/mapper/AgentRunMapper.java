package com.md.agentplatform.server.trace.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.trace.store.entity.AgentRunEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code md_agent_run} 的 Mapper。
 *
 * <p>只扩展出一个方法（保留期清理），其余全部走 {@link BaseMapper} 的默认实现 ——
 * 单行插入 / 按主键更新 / 按主键查询都是声明式的，手写 SQL 只会多一份需要同步维护的拼写。
 *
 * <p>{@code @Mapper} 是让 MyBatis 自动配置扫描到它（扫描范围 = 启动类所在包 {@code com.md.agentplatform}
 * 及其子包），因此不需要在启动类上再写 {@code @MapperScan}。
 */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {

    /**
     * 删除开始时间早于 cutoff 的 run，返回删除行数。
     *
     * <p>SQL 里直接写 {@code <}：MyBatis 只在注解值以 {@code <script>} 开头时才当 XML 解析
     * （见 XMLLanguageDriver#createSqlSource），这里没有 script 标签，所以 {@code <} 是普通字符。
     * 反过来说，<b>谁往这条 SQL 前面加了 {@code <script>}，谁就得把 {@code <} 改成 {@code &lt;}</b>。
     */
    @Delete("DELETE FROM md_agent_run WHERE started_at_ms < #{cutoffMs}")
    int deleteStartedBefore(@Param("cutoffMs") long cutoffMs);
}
