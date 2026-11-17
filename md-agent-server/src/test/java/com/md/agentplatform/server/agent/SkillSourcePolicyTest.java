package com.md.agentplatform.server.agent;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 技能来源策略：只认 {@code skill-names} → MySQL，历史 {@code skills} 目录不再参与。
 */
class SkillSourcePolicyTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void resolvesFromMysqlEvenWhenLegacySkillsDirExists() throws IOException {
        Path legacyDir = workspaceRoot.resolve("single/skills/demo");
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("SKILL.md"), "# local-only");

        AgentDefinition def = new AgentDefinition();
        def.setName("demo");
        def.setSkills("single/skills/demo");
        def.setSkillNames(List.of("db-skill"));

        AgentSkillRepository resolved = new ConfigurableAgentBuilder()
                .resolveSkillRepository(def, new InMemorySkillRepository("db-skill", "file-skill"));

        assertNotNull(resolved);
        assertEquals(List.of("db-skill"), resolved.getAllSkillNames());
        assertNull(resolved.getSkill("file-skill"), "历史目录里的技能不应被加载");
    }

    @Test
    void noSkillNamesMeansNoRepositoryEvenWhenLegacySkillsDirExists() throws IOException {
        Path legacyDir = workspaceRoot.resolve("single/skills/demo");
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("SKILL.md"), "# local-only");

        AgentDefinition def = new AgentDefinition();
        def.setName("demo");
        def.setSkills("single/skills/demo");

        assertNull(new ConfigurableAgentBuilder()
                .resolveSkillRepository(def, new InMemorySkillRepository("file-skill")));
    }

    private static final class InMemorySkillRepository implements AgentSkillRepository {

        private final Map<String, AgentSkill> skills = new LinkedHashMap<>();

        private InMemorySkillRepository(String... names) {
            for (String name : names) {
                skills.put(name, AgentSkill.builder()
                        .name(name)
                        .description("test skill")
                        .skillContent("# " + name)
                        .resources(Map.of())
                        .source("test-mysql")
                        .build());
            }
        }

        @Override
        public AgentSkill getSkill(String name) {
            return skills.get(name);
        }

        @Override
        public List<String> getAllSkillNames() {
            return List.copyOf(skills.keySet());
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return List.copyOf(skills.values());
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean overwrite) {
            return false;
        }

        @Override
        public boolean delete(String name) {
            return skills.remove(name) != null;
        }

        @Override
        public boolean skillExists(String name) {
            return skills.containsKey(name);
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return null;
        }

        @Override
        public String getSource() {
            return "test-mysql";
        }

        @Override
        public void setWriteable(boolean writeable) {
            // no-op
        }

        @Override
        public boolean isWriteable() {
            return true;
        }
    }
}
