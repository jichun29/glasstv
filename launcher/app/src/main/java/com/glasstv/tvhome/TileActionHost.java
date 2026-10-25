package com.glasstv.tvhome;

/**
 * Host interface for tile-action dialog callbacks that must run on the Activity
 * (the adapter only holds a Context). K 定制:菜单键弹窗的「移动位置」需要 Activity 进入重排模式。
 */
interface TileActionHost {
    /** Enter reorder mode with the given tile (component flat key) already picked up. */
    void startReorderCarrying(String key);

    /** Hide the app's tile from Home (K 2026-10-22, 长按 OK 菜单)；app 还在，只是不在桌面显示。 */
    void hideTile(String pkg);

    /** The launcher's root view, for dialogs the adapter shows on its own. */
    android.view.ViewGroup launcherRoot();
}
