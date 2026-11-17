package com.md.agentplatform.server.environment;

import com.md.agentplatform.server.environment.EnvironmentDefaults.GroupSeed;
import com.md.agentplatform.server.environment.store.EnvironmentStore;
import com.md.agentplatform.server.environment.store.entity.EnvGroupEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupInstanceEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupKeyEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动时补齐内置分组模板与缺失的内置键声明。环境值只从数据库读取，不从配置文件迁移。
 *
 * <h2>一、只补缺失的内置分组与键声明，不做"每次启动同步"</h2>
 * 用户 2026-09-23 的明确决定：<b>不再从配置文件同步值</b>，页面就是维护入口。
 * 如果每次启动都同步值，页面上刚改的值会被配置里的旧值覆盖 —— 那比不做同步更糟，
 * 因为它会在某次重启后悄悄回滚，而没有任何报错。
 * 所以这里只在"分组模板缺失"时补建；已有分组不覆盖已有键定义、不动任何配置值，
 * 但会补齐<b>缺失的内置键声明</b>（只增不删）。这是升级兼容所必需的：
 * 新增的 POJO 字段会被 {@code EnvironmentSchema} 视为保留键，旧库没有对应声明时
 * 页面保存会被校验拦住；补齐声明不会改变任何值，也不会删除页面上的自定义键。
 *
 * <h2>二、为什么不做成一个跨多行的大事务</h2>
 * 整个播种是<b>幂等且可续</b>的：分组存在就跳过、键声明存在就保留。
 * 所以中途失败的正确处理不是回滚一切，
 * 而是"下次启动接着补" —— 反而是大事务会把已经成功的部分一起回滚掉，白干一遍。
 * 这也让这里不需要 {@code @Transactional} 的自我调用陷阱。
 *
 * <h2>三、失败不阻断启动</h2>
 * DDL 在本项目是手工执行的（control-plane / trace 都是这个约定）。没执行就播种失败，
 * 此时正确行为是 WARN 一行然后照常启动；环境目录读取会明确报数据库不可用，
 * 不再回退到 {@code application.yml}。把"少执行一个脚本"升级成"服务起不来"是过度反应。
 *
 * <h2>四、WebFlux 下的一拍延迟为什么可以接受</h2>
 * {@code ApplicationRunner} 在内嵌服务器启动之后才跑，所以理论上存在一个几十毫秒的窗口，
 * 请求可能读到一个"还没播种"的快照。这里不为此引入额外同步机制，理由是这个窗口里
 * 要么没人访问（页面要人去点、消息要人发），要么退一步也只是少显示几行，
 * 不会有任何写入风险 —— 与"为了理论上的竞态给读路径加锁"相比，不值得。
 */
@Component
public class EnvironmentSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentSeeder.class);

    /** 本次启动补齐的键声明数量，用于决定是否需要让环境目录快照失效。 */
    private int patchedKeys;

    private final EnvironmentStore store;
    private final EnvironmentCatalog catalog;

    public EnvironmentSeeder(EnvironmentStore store, EnvironmentCatalog catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            patchedKeys = 0;
            int groups = seedGroups();
            if (groups > 0 || patchedKeys > 0) {
                catalog.invalidate();
                log.info("[Env] 初始化完成：新建 {} 个分组模板，补齐 {} 个内置键声明",
                        groups, patchedKeys);
            }
        } catch (RuntimeException e) {
            log.warn("[Env] 环境目录播种跳过（环境表可能还没建）：{}。"
                    + "执行 md-agent-bootstrap/src/main/resources/db/env-schema.sql 后重启即可。", e.toString());
        }
    }

    /** @return 新建的分组数 */
    private int seedGroups() {
        Set<String> existing = new HashSet<>();
        for (EnvGroupEntity group : store.listGroups()) {
            existing.add(group.getGroupCode());
        }
        Map<String, List<EnvGroupKeyEntity>> keysByGroup = new HashMap<>();
        for (EnvGroupKeyEntity key : store.listGroupKeys()) {
            keysByGroup.computeIfAbsent(key.getGroupCode(), ignored -> new ArrayList<>()).add(key);
        }
        List<String> createdCodes = new ArrayList<>();
        for (GroupSeed seed : EnvironmentDefaults.seeds()) {
            EnvGroupEntity group = seed.group();
            String code = group.getGroupCode();
            if (existing.contains(code)) {
                // 已有分组：不覆盖已有键定义和值，只补缺失的内置键声明
                patchedKeys += ensureGroupKeys(code, seed.keys(),
                        keysByGroup.getOrDefault(code, List.of()));
                continue;
            }
            if (!store.createGroup(group)) {
                continue;
            }
            // 键声明整体替换：分组是刚建出来的，这里必然是从零插入
            store.replaceGroupKeys(code, copyKeys(seed.keys()));
            seedDefaultInstance(code);
            createdCodes.add(code);
        }
        if (!createdCodes.isEmpty()) {
            log.info("[Env] 已补齐 {} 个缺失的内置分组模板：{}", createdCodes.size(), createdCodes);
        }
        return createdCodes.size();
    }

    /** 新内置分组需要一个默认实例；只建实例元数据，不写任何环境值。 */
    private void seedDefaultInstance(String groupCode) {
        EnvGroupInstanceEntity instance = new EnvGroupInstanceEntity();
        instance.setGroupCode(groupCode);
        instance.setInstanceCode(EnvironmentCatalog.DEFAULT_INSTANCE);
        instance.setDisplayName("默认");
        instance.setEnabled(Boolean.TRUE);
        instance.setSortOrder(0);
        store.createGroupInstance(instance);
    }

    /**
     * 已有内置分组补齐缺失的键声明。
     *
     * <p>只追加默认模板里存在、库里没有的 key_code；已有键的定义、顺序和所有环境值都保留。
     * 返回本次追加的键数量。
     */
    private int ensureGroupKeys(String groupCode, List<EnvGroupKeyEntity> defaults,
                                List<EnvGroupKeyEntity> existing) {
        Set<String> existingCodes = new HashSet<>();
        for (EnvGroupKeyEntity key : existing) {
            existingCodes.add(key.getKeyCode());
        }
        List<EnvGroupKeyEntity> merged = new ArrayList<>(existing);
        List<String> added = new ArrayList<>();
        for (EnvGroupKeyEntity key : defaults) {
            if (!existingCodes.contains(key.getKeyCode())) {
                merged.add(key);
                added.add(key.getKeyCode());
            }
        }
        if (added.isEmpty()) {
            return 0;
        }
        merged.sort(Comparator
                .comparingInt((EnvGroupKeyEntity key) -> key.getSortOrder() == null ? 0 : key.getSortOrder())
                .thenComparing(EnvGroupKeyEntity::getKeyCode,
                        Comparator.nullsLast(String::compareTo)));
        store.replaceGroupKeys(groupCode, copyKeys(merged));
        log.info("[Env] 内置分组 {} 补齐缺失键声明（不改变值）：{}", groupCode, added);
        return added.size();
    }

    /** 出厂键声明里的 id 必须清掉：它是自增列，带着旧 id 插入会撞唯一键或覆盖别的行。 */
    private static List<EnvGroupKeyEntity> copyKeys(List<EnvGroupKeyEntity> keys) {
        List<EnvGroupKeyEntity> copies = new ArrayList<>(keys.size());
        for (EnvGroupKeyEntity key : keys) {
            EnvGroupKeyEntity copy = new EnvGroupKeyEntity();
            copy.setGroupCode(key.getGroupCode());
            copy.setKeyCode(key.getKeyCode());
            copy.setLabel(key.getLabel());
            copy.setValueType(key.getValueType());
            copy.setRequired(key.getRequired());
            copy.setSecret(key.getSecret());
            copy.setPlaceholder(key.getPlaceholder());
            copy.setExampleValue(key.getExampleValue());
            copy.setDescription(key.getDescription());
            copy.setSortOrder(key.getSortOrder());
            copies.add(copy);
        }
        return copies;
    }
}
