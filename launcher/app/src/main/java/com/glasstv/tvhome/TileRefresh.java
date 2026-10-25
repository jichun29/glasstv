package com.glasstv.tvhome;

/**
 * 回到 Home 时要不要重建磁贴（上游 v2.6.0 移植，K 2026-10-22）。
 *
 * 重建要重新问 PackageManager 所有 App、重新加载每个横幅/图标/文字（TCL C755 上约 30ms，每次按 Home 都跑），
 * 所以只在期间真的装了/卸了/更新/启停了 App 时才做；否则只把焦点放回去。
 * 这就是上游说的「Instant return to Home」：从 App 回来不再黑半秒 + 重播入场动画。
 */
final class TileRefresh {

    enum Action { NOTHING, REFOCUS, REBUILD }

    private boolean started;
    /** 磁贴可能和实际安装的 App 不一致：第一次构建前一直为 true。 */
    private boolean stale = true;
    private boolean refocus;

    void onStart() {
        started = true;
    }

    /** 别的 App 占了屏幕：磁贴留着，回来时只把焦点放回去。 */
    void onStop() {
        started = false;
        refocus = true;
    }

    /** Home 已经在最前时再按 Home：回到第一个磁贴。 */
    void onHomeAgain() {
        refocus = true;
    }

    /** 磁贴被丢了（onTrimMemory）：回来时重建。 */
    void invalidate() {
        stale = true;
    }

    /**
     * 收到包变化广播。Home 在前台时立刻重建并返回 true；在后台时只标记 stale，
     * 等 onResume 一次性重建，免得一串 App 更新触发一串重建。
     */
    boolean onPackagesChanged() {
        if (started) return true;
        stale = true;
        return false;
    }

    /** @param changedMeanwhile PackageManager 报告自上次构建后有变化（漏掉的广播）。 */
    Action onResume(boolean changedMeanwhile) {
        Action action = stale || changedMeanwhile ? Action.REBUILD : refocus ? Action.REFOCUS : Action.NOTHING;
        stale = false;
        refocus = false;
        return action;
    }
}
