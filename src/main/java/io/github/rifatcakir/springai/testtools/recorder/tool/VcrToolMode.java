package io.github.rifatcakir.springai.testtools.recorder.tool;

/**
 * Whether a real {@code @Tool}/{@code ToolCallback} invocation is isolated from replay,
 * or allowed to actually run.
 *
 * <p>Orthogonal to {@link io.github.rifatcakir.springai.testtools.recorder.VcrMode} and
 * to {@link io.github.rifatcakir.springai.testtools.recorder.VcrScope}, which answer
 * different questions: {@code VcrMode} decides whether a *model* call reaches the real
 * model or replays; {@code VcrScope} decides whether an entire interaction replays as one
 * fixture or each model turn replays individually while the tool loop still runs; this
 * enum decides, whenever the tool loop does run, whether an individual tool invocation
 * itself is real or replayed. See {@code docs/TOOL-ISOLATION-PRD.md} for the full
 * reasoning and the bytecode-verified diagnosis behind this design.
 *
 * @author Rifat Cakir
 */
public enum VcrToolMode {

	/**
	 * Full isolation. On a cassette hit, the recorded arguments/result pair is returned
	 * directly and the real {@code @Tool} method's body never executes — not even once,
	 * not even during a replay that also happens to be re-invoking the tool loop for a
	 * model-turn fixture. On a miss, the real tool is invoked once and the result is
	 * recorded, the same "first run records, every run after replays" behaviour the rest
	 * of this library already has.
	 *
	 * <p>The default. A side-effecting tool (a database write, an outbound API call, an
	 * email) never fires more than once per distinct (tool name, arguments) pair,
	 * regardless of how many times a test suite re-runs.
	 */
	REPLAY_FROM_CASSETTE,

	/**
	 * The real tool always runs, on every call — the result is still recorded (so a later
	 * switch back to {@link #REPLAY_FROM_CASSETTE} has something to replay), but it is
	 * never read back. Maps exactly onto this library's pre-isolation behaviour under
	 * {@link io.github.rifatcakir.springai.testtools.recorder.VcrScope#INSIDE_TOOL_LOOP}:
	 * use this when a test specifically wants to assert that the real {@code @Tool}
	 * method was actually invoked, with the right arguments, the right number of times.
	 */
	EXECUTE_REAL

}
