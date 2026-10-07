package com.signalframe.http;

import com.signalframe.ai.application.ModelRouter;
import com.signalframe.ai.domain.ModelRunRepository;
import com.signalframe.contract.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ModelController {

  private final ModelRouter router;
  private final ModelRunRepository runs;

  public ModelController(ModelRouter router, ModelRunRepository runs) {
    this.router = router;
    this.runs = runs;
  }

  @GetMapping("/model-runs")
  List<ModelRun> runs(@RequestParam(required = false) UUID jobId) {
    return runs.recent(jobId);
  }

  @GetMapping("/settings/model-profiles")
  List<NamedModelProfile> profiles() {
    return router.list();
  }

  @PutMapping("/settings/model-profiles/{profile}")
  NamedModelProfile update(
    @PathVariable String profile,
    @Valid @RequestBody ModelProfile input
  ) {
    return router.update(profile, input);
  }
}
