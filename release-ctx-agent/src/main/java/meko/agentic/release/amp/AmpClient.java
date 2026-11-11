package meko.agentic.release.amp;

import meko.agentic.client.MekoMcpClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * YugabyteDB AMP's lifecycle, through Meko's MCP tools ({@code amp_*}). Every call names the datapack in use, so the
 * clusters this demo creates live in the same datapack as the context its agents read and write.
 * <p>
 * Behaviour below was observed against the live service, not inferred from the tool schemas:
 * <ul>
 *   <li>A branch forks in about three seconds; its compute takes one to three minutes to answer.</li>
 *   <li>{@code amp_compute_create} can end in a gateway 504 while the endpoint is still being provisioned: the endpoint
 *       exists (it shows in {@code amp_compute_list} and counts against the core budget), so it is adopted, not
 *       created again.</li>
 *   <li>A fork's endpoint has its own address (host and port); only that address reaches it. Its DNS name can take a
 *       few seconds to resolve after the create returns.</li>
 *   <li>The login user is {@code admin}, reported as {@code login_user}; a fork's writer reuses the parent's password.</li>
 * </ul>
 */
@Component
public class AmpClient {
    /** Endpoints this demo creates: the smallest AMP allows, which fits a free tier's whole budget. */
    public static final String CPU = "500m";
    /** {@link #CPU} in vCPU, as the core budget counts it. */
    public static final double CORES = Integer.parseInt(CPU.replace("m", "")) / 1000.0;

    public record Budget(String tier, double usedCores, double maxCores, boolean atLimit) {}

    public record Cluster(String id, String name, String mainBranchId) {}

    public record Compute(String id, String branchId, String role, String address, String loginUser, String cpu) {
        public boolean addressed() {
            return address != null && !address.isBlank();
        }
    }

    public record Branch(String id, String name, String forkLsn, boolean isDefault) {}

    private final MekoMcpClient meko;

    public AmpClient(MekoMcpClient meko) {
        this.meko = meko;
    }

    public Budget budget() throws Exception {
        JsonNode b = call("amp_core_budget");
        return new Budget(b.path("tier").asString(""), b.path("used_cores").asDouble(), b.path("max_cores").asDouble(),
                b.path("at_limit").asBoolean(false));
    }

    public List<Cluster> clusters() throws Exception {
        List<Cluster> out = new ArrayList<>();
        for (JsonNode c : call("amp_cluster_list").values()) out.add(cluster(c));
        return out;
    }

    /**
     * Creates a cluster with its {@code main} writer, reachable from outside AMP by the given addresses only. With no
     * {@code password}, AMP generates one and returns it once: the control plane keeps only its verifier.
     */
    public record Created(Cluster cluster, String password) {}

    public Created createCluster(String name, List<String> allowedCidrs, String password) throws Exception {
        JsonNode c = call("amp_cluster_create", "name", name, "with_writer", true, "cpu", CPU,
                "connectivity_mode", "ingress_external", "allowed_cidrs", allowedCidrs, "password", password);
        return new Created(cluster(c), c.path("cloud_admin_password").asString(password));
    }

    public void deleteCluster(String clusterId) throws Exception {
        call("amp_cluster_delete", "cluster_id", clusterId);
    }

    public List<Branch> branches(String clusterId) throws Exception {
        List<Branch> out = new ArrayList<>();
        for (JsonNode b : call("amp_branch_list", "cluster_id", clusterId).values()) out.add(branch(b));
        return out;
    }

    /**
     * A zero-copy fork of {@code main}: as of its latest flushed LSN, or as of {@code lsn} (a position recorded earlier).
     * A fork at a given position needs no running compute on {@code main}, and holds exactly the state at that position.
     */
    public Branch fork(String clusterId, String name, String lsn) throws Exception {
        return branch(call("amp_branch_create", "cluster_id", clusterId, "name", name, "fork_point_lsn", lsn));
    }

    /** Deletes a branch with its compute. */
    public void deleteBranch(String clusterId, String branchId) throws Exception {
        call("amp_branch_delete", "cluster_id", clusterId, "branch_id", branchId);
    }

    public List<Compute> computes(String clusterId, String branchId) throws Exception {
        List<Compute> out = new ArrayList<>();
        for (JsonNode c : call("amp_compute_list", "cluster_id", clusterId, "branch_id", branchId).values()) out.add(compute(c));
        return out;
    }

    public Compute compute(String clusterId, String branchId, String computeId) throws Exception {
        return compute(call("amp_compute_get", "cluster_id", clusterId, "branch_id", branchId, "compute_id", computeId));
    }

    /**
     * Adds a writer endpoint to a branch. A create that fails with a gateway error may still have provisioned the
     * endpoint: then the branch's existing writer is returned rather than a second one requested.
     */
    public Compute addWriter(String clusterId, String branchId, List<String> allowedCidrs, Consumer<String> progress) throws Exception {
        try {
            return compute(call("amp_compute_create", "cluster_id", clusterId, "branch_id", branchId, "cpu", CPU,
                    "connectivity_mode", "ingress_external", "allowed_cidrs", allowedCidrs));
        } catch (Exception e) {
            for (Compute c : computes(clusterId, branchId)) {
                if ("read_write".equals(c.role())) {
                    progress.accept("The create call ended with an error (" + abbreviate(e.getMessage())
                            + "), but the endpoint exists and is still being provisioned: using it.");
                    return c;
                }
            }
            throw e;
        }
    }

    private JsonNode call(String tool, Object... args) throws Exception {
        Object[] all = new Object[args.length + 4];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = "datapack_id";
        all[args.length + 1] = meko.datapack();
        all[args.length + 2] = "conversation_id";
        all[args.length + 3] = meko.conversation(meko.conversationOwner() + ":amp", "AMP lifecycle", java.util.Map.of());
        return meko.callTool(tool, meko.args(all));
    }

    private static Cluster cluster(JsonNode c) {
        return new Cluster(c.path("id").asString(""), c.path("name").asString(""),
                c.path("default_branch").path("branch_id").asString(null));
    }

    private static Branch branch(JsonNode b) {
        return new Branch(b.path("branch_id").asString(b.path("id").asString("")), b.path("name").asString(""),
                b.path("fork_lsn").asString(null), b.path("is_default").asBoolean(false));
    }

    private static Compute compute(JsonNode c) {
        return new Compute(c.path("id").asString(""), c.path("branch_id").asString(""), c.path("role").asString(""),
                c.path("address").asString(""), c.path("login_user").asString(""), c.path("resources").path("cpu").asString(""));
    }

    private static String abbreviate(String text) {
        if (text == null) return "";
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }
}
