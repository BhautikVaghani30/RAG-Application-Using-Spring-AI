package github.ai.backend;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import github.ai.backend.services.ai.ChatPromptBuilder;
import github.ai.backend.services.indexing.CodeFileFilter;

class BackendApplicationTests {

    private final CodeFileFilter fileFilter = new CodeFileFilter();

    @Test
    void fileFilterIncludesSourceAndExcludesGeneratedFiles() {
        assertTrue(fileFilter.isEligible("src/main/App.java", 256, 1024));
        assertFalse(fileFilter.isEligible("target/classes/App.class", 256, 1024));
    }

    @Test
    void promptsIncludeRepositoryContextAndQuestion() {
        ChatPromptBuilder promptBuilder = new ChatPromptBuilder();
        assertTrue(promptBuilder.systemPrompt("owner/repo").contains("owner/repo"));
        assertTrue(promptBuilder.userPrompt("class Example {}", "What does this do?")
                .contains("What does this do?"));
    }
}
