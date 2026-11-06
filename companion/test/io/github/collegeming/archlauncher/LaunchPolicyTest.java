package io.github.collegeming.archlauncher;

public final class LaunchPolicyTest {
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Launch policy regression");
    }
    public static void main(String[] args) {
        require(LaunchPolicy.activityFlags(true).equals("--activity-clear-top"));
        require(!LaunchPolicy.activityFlags(true).contains("single-top"));
        require(LaunchPolicy.activityFlags(false).contains("single-top"));
        require(LaunchPolicy.activityFlags(false).contains("reorder-to-front"));
        require(!LaunchPolicy.activityFlags(false).contains("clear-top"));
        require(LaunchPolicy.sameTarget(false, 123, 123, "running", "RUNNING"));
        require(!LaunchPolicy.sameTarget(false, 123, 124, "running", "RUNNING"));
        require(!LaunchPolicy.sameTarget(false, -1, -1, "running", "RUNNING"));
        require(!LaunchPolicy.sameTarget(false, 123, 123, "running", "STOPPED"));
        require(LaunchPolicy.sameTarget(true, 0, -1, "stopped", "STOPPED"));
        require(!LaunchPolicy.sameTarget(true, 0, 123, "stopped", "STOPPED"));
        System.out.println("Launch policy checks passed");
    }
}
