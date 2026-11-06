package io.github.collegeming.archlauncher;

/** Pure decisions shared by the launcher and its host-side CI checks. */
final class LaunchPolicy {
    static String activityFlags(boolean stopped) {
        return stopped ? "--activity-clear-top"
            : "--activity-single-top --activity-reorder-to-front";
    }

    static boolean sameTarget(boolean stopped, long expectedPid, long actualPid,
                              String expectedState, String actualState) {
        return expectedState.equalsIgnoreCase(actualState)
            && (stopped ? actualPid <= 0 : expectedPid > 1 && actualPid == expectedPid);
    }
}
