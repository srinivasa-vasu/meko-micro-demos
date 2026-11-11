package meko.agentic.goap.api;

import meko.agentic.goap.story.StoryCatalog;
import meko.agentic.goap.story.StoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkGateEndpointTest {

    @Test
    void aRunRequestedWhileAnotherRunsIsRefusedWith409AndNeverStarts() throws Exception {
        WorkGate gate = new WorkGate();
        StoryService stories = mock(StoryService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StoryController(mock(StoryCatalog.class), stories, gate, new RunJobs()))
                .setControllerAdvice(new WorkGate.Answer()).build();
        try (var running = gate.enter("a run of organization-learned")) {
            mvc.perform(post("/api/stories/organization-learned/case").param("index", "1"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Wait for the work in progress to finish: a run of organization-learned."));
        }
        verifyNoInteractions(stories);
    }
}
