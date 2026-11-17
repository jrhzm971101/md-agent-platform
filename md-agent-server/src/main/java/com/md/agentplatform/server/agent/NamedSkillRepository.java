package com.md.agentplatform.server.agent;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 技能仓库装饰器 —— 把「后端一共有哪些技能」收窄成「这个 agent 该看到哪些技能」。
 *
 * <h2>一、为什么必须有这一层</h2>
 * 框架的 {@code DynamicSkillMiddleware} 在装配期会对传进来的每个
 * {@link AgentSkillRepository} 调一次 {@link #getAllSkills()}，把结果拼成
 * {@code <available_skills>} 段塞进系统提示词。所以如果直接把
 * 「面向全库」的 MySQL 技能仓库交给 builder，<b>每个 agent 都会在提示词里看到全部技能</b>——
 * 既浪费 token，也会让模型去加载不属于自己职责的技能。
 *
 * <p>换句话说：<b>「按名过滤」这件事不能靠框架，必须由仓库自己收窄</b>。
 * 本类就是这个收窄点：内部仍持有全库仓库（读、写、存在性判断全部委托给它），
 * 只把<b>读出口</b>（{@link #getAllSkills()} / {@link #getAllSkillNames()} / {@link #getSkill(String)}）
 * 限制在构造时给定的名字集合内。
 *
 * <h2>二、为什么不做成「按需懒加载」</h2>
 * 一度考虑过 getAllSkills 里逐个 getSkill 从库里现拉，省内存。放弃原因：
 * 装配是启动期动作、技能总量只有几十个，而按需拉取会把「一次全表查询」变成
 * 「N 次点查 + N 次网络往返」——官方仓库实测单次点查 30~60ms（Hikari），
 * 技能一多启动反而更慢。所以这里的策略是<b>一次性全量读，之后内存里过滤</b>。
 *
 * <h2>三、写操作不设限</h2>
 * {@link #save} / {@link #delete} 原样透传：装饰器的职责是「让模型少看见」，
 * 不是「让管理员少改」。控制面写技能时用的是原始仓库，不走本类。
 *
 * <h2>四、名字集合的语义</h2>
 * 存进 {@link LinkedHashSet}：去重 + 保持声明顺序（顺序会影响提示词里技能的呈现先后）。
 * 名字在库里不存在时 <b>静默跳过</b>而非报错 —— 装配期不该因为一个写错的名字失败，
 * 缺失由 {@code ControlPlaneValidationException} 在<b>保存</b>时先拦住（保存才是该报错的地方）。
 */
class NamedSkillRepository implements AgentSkillRepository {

    /** 全库仓库：读写都委托给它，本类只收窄读出口。 */
    private final AgentSkillRepository delegate;

    /** 本 agent 允许看到的技能名（去重、保序）。 */
    private final Set<String> allowedNames;

    /**
     * @param delegate     全库技能仓库（官方 MySQL 仓库）
     * @param allowedNames 本 agent 声明的技能名；空集合表示「一个技能都不挂」——
     *                     调用方应据此返回 null 而不是建一个空仓库，
     *                     因为框架对「有仓库但没技能」与「没仓库」的处理不同
     */
    NamedSkillRepository(AgentSkillRepository delegate, List<String> allowedNames) {
        this.delegate = delegate;
        this.allowedNames = new LinkedHashSet<>(allowedNames == null ? List.of() : allowedNames);
    }

    /** 单点读：不在白名单里的名字一律当作「不存在」（返回 null），不暴露「库里有但你没声明」这个事实。 */
    @Override
    public AgentSkill getSkill(String name) {
        if (!allowedNames.contains(name)) {
            return null;
        }
        return delegate.getSkill(name);
    }

    /** 全量读：只回白名单内的名字，且剔除库里已经不存在的（避免提示词里出现解析不到的技能）。 */
    @Override
    public List<AgentSkill> getAllSkills() {
        List<AgentSkill> result = new ArrayList<>(allowedNames.size());
        for (String name : allowedNames) {
            AgentSkill skill = delegate.skillExists(name) ? delegate.getSkill(name) : null;
            if (skill != null) {
                result.add(skill);
            }
        }
        return result;
    }

    /** 名字清单：只回白名单内的名字（不查库，框架拿到名字后还会调 getSkill）。 */
    @Override
    public List<String> getAllSkillNames() {
        List<String> result = new ArrayList<>(allowedNames.size());
        for (String name : allowedNames) {
            if (delegate.skillExists(name)) {
                result.add(name);
            }
        }
        return result;
    }

    // ==================== 以下全部原样透传 ====================

    @Override
    public boolean save(List<AgentSkill> skills, boolean overwrite) {
        return delegate.save(skills, overwrite);
    }

    @Override
    public boolean delete(String name) {
        return delegate.delete(name);
    }

    @Override
    public boolean skillExists(String name) {
        return allowedNames.contains(name) && delegate.skillExists(name);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return delegate.getRepositoryInfo();
    }

    /**
     * 来源串沿用底层仓库，只在末尾标注被收窄，方便日志里看出这不是全库视图。
     *
     * <p><b>必须路径安全</b>：框架会把这个串当成工作区里的目录名用
     * （见官方 {@code MarketplaceStager#stageAll}：{@code scopeRoot.resolve(source).resolve(skillName)}），
     * 里面一旦出现冒号这类 Windows 非法字符，装配期就直接抛 {@link java.nio.file.InvalidPathException}，
     * 表现为「agent 一对话就报错」。实测 2026-09-28 就是这么炸的：
     * {@code "mysql_agentscope_agentscope_skills (named:1)"} 里的冒号。
     * 官方 {@code GitSkillRepository} 也只用连字符，注释写着
     * "Use hyphen instead of colon to avoid Windows path issues"。故本方法只用字母、数字与连字符。
     */
    @Override
    public String getSource() {
        return delegate.getSource() + "-named-" + allowedNames.size();
    }

    @Override
    public void setWriteable(boolean writeable) {
        delegate.setWriteable(writeable);
    }

    @Override
    public boolean isWriteable() {
        return delegate.isWriteable();
    }

    /**
     * <b>不关底层仓库</b>：底层是全局单例，被所有 agent 共享
     * （同 {@code DbMcpClientFactory} 里 client 的复用语义）。
     * 一个 agent 被重建而关掉全局仓库，会让其他 agent 的技能全部失效。
     */
    @Override
    public void close() {
        // 有意为空：生命周期归全局单例所有
    }
}
