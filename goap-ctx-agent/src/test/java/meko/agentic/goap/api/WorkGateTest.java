package meko.agentic.goap.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkGateTest {

    @Test
    void aSecondPieceOfWorkIsRefusedWithWhatIsRunning() {
        WorkGate gate = new WorkGate();
        try (var first = gate.enter("a run of what-changed")) {
            assertThat(gate.running()).isEqualTo("a run of what-changed");
            assertThatThrownBy(() -> gate.enter("switching datapacks"))
                    .isInstanceOf(WorkGate.Busy.class)
                    .hasMessageContaining("a run of what-changed");
        }
        // Released when the first ends: the next one enters.
        assertThat(gate.running()).isNull();
        try (var next = gate.enter("switching datapacks")) {
            assertThat(gate.running()).isEqualTo("switching datapacks");
        }
    }

    @Test
    void closingTwiceReleasesOnce() {
        WorkGate gate = new WorkGate();
        WorkGate.Pass pass = gate.enter("a run");
        pass.close();
        try (var other = gate.enter("another run")) {
            pass.close();   // the first pass again: must not release the second's hold
            assertThat(gate.running()).isEqualTo("another run");
        }
    }
}
