package meko.agentic.goap.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** With the workshop profile, the start page is the workshop; the demo stays at {@code /index.html}. */
@Controller
@ConditionalOnProperty(name = "workshop.enabled", havingValue = "true")
public class WorkshopHome {
    @GetMapping("/")
    public String home() {
        return "redirect:/workshop.html";
    }
}
