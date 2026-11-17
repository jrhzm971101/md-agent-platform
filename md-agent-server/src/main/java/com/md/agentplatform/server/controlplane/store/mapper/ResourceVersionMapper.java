package com.md.agentplatform.server.controlplane.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.controlplane.store.entity.ResourceVersionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * `ResourceVersionEntity` 的 Mapper —— 版本快照表。按 (resource_type, resource_name, version) 查询用 Wrapper 表达，唯一键 uk_type_name_ver 保证不会重复落同一版本。
 *
 * <p>`@Mapper` 让 MyBatis 自动配置扫描到它（扫描范围 = 启动类所在包 `com.md.agentplatform`
 * 及其子包），因此不需要在启动类上写 `@MapperScan` —— 与 `trace/store/mapper` 下的写法一致。
 */
@Mapper
public interface ResourceVersionMapper extends BaseMapper<ResourceVersionEntity> {

    /**
     * 版本列表的<b>投影查询</b>：只取列表页要显示的四列，<b>刻意不取 snapshot_json</b>。
     *
     * <p>为什么值得单独写一条 SQL：agent 的快照是 6 张表的整行 JSON，
     * 一个 agent 发布过 20 次，用 {@code selectList} 拉版本列表就要把几百 KB 正文读进内存再丢掉。
     * 列表页一列正文都不显示，这笔开销纯属浪费。
     *
     * <p>返回实体但只填了 4 个字段（其余为 null）：MyBatis 只映射 SELECT 出来的列，
     * 调用方（{@code MyBatisControlPlaneStore#listVersions}）拿它转成
     * {@link com.md.agentplatform.server.controlplane.store.ResourceVersionInfo}，
     * 不允许把它当完整实体用。
     */
    @Select("SELECT version, change_note, created_at_ms, created_by FROM md_resource_version "
            + "WHERE resource_type = #{type} AND resource_name = #{name} ORDER BY version DESC")
    List<ResourceVersionEntity> listVersionHeaders(@Param("type") String type, @Param("name") String name);

    /**
     * 全局的最近发布/回滚流水（概览页的「最近变更」用它）。
     *
     * <p>与 {@code listVersionHeaders} 同样是投影查询：不取 {@code snapshot_json} ——
     * 概览页只显示"谁在什么时候发布了第几版"，把整段快照正文拉回来再丢掉没有意义。
     * 这里额外返回 {@code resource_type / resource_name}：跨资源的流水没有这两个列就无法说明"改的是谁"。
     */
    @Select("SELECT resource_type AS resourceType, resource_name AS resourceName, version, change_note,"
            + " created_at_ms, created_by FROM md_resource_version"
            + " ORDER BY created_at_ms DESC LIMIT #{limit}")
    List<ResourceVersionEntity> recentHeaders(@Param("limit") int limit);
}
