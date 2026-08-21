package com.archscope.jetbrains.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class FunctionTargetTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsIdentityStableAcrossBranchesWhenOnlySignatureAndLinesChange() {
        FunctionTarget first = new FunctionTarget(
                temporaryDirectory, "apps/chat/service/score.go", "ScoreService.Calculate",
                "func (s *ScoreService) Calculate(ctx context.Context)", 20, 80);
        FunctionTarget second = new FunctionTarget(
                temporaryDirectory, "apps\\chat\\service\\score.go", "ScoreService.Calculate",
                "func (s *ScoreService) Calculate(ctx context.Context, force bool)", 45, 130);

        assertEquals(first.stableId(), second.stableId());
        assertNotEquals(first.stableId(), new FunctionTarget(
                temporaryDirectory, "apps/chat/service/score.go", "ScoreService.Save",
                "func (s *ScoreService) Save()", 20, 40).stableId());
    }
}
