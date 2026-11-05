package local.byd.arrival;

/** Android-free update logic. Release tags look like v42, where 42 is that build's versionCode. */
public final class UpdateCheck {
    /** The build number in a release tag from GitHub. Anything else in the tag is rejected, not guessed at. */
    public static int buildOf(String tag) {
        if (!tag.matches("v[0-9]{1,9}")) throw new IllegalArgumentException("Unexpected release tag: " + tag);
        return Integer.parseInt(tag.substring(1));
    }
    /** The newer build number, or 0 when the installed build is already current. */
    public static int newerBuild(String latestTag, int installedBuild) {
        int latest = buildOf(latestTag);
        return latest > installedBuild ? latest : 0;
    }
}
