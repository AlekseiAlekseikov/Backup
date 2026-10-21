package com.example.voiceapp3.ui.cx

/**
 * Геометрия интерфейса. Значения перенесены из CSS шелла
 * (`assets/ui/index.html`, блок `<style>`), а не подобраны на глаз: шелл
 * рисуется в окне 1920×1080, где CSS-пиксель соответствует dp.
 *
 * В комментарии у каждой группы указан селектор-источник.
 */
object CxMetrics {

    // .cx-nav — width:340px; flex-shrink:0; flex-direction:column
    const val NAV_WIDTH_DP = 340f

    // .cx-row — gap:18px; min-height:72px; padding:16px 10px 16px 2px
    const val ROW_GAP_DP = 18f
    const val ROW_MIN_HEIGHT_DP = 72f
    const val ROW_PAD_TOP_DP = 16f
    const val ROW_PAD_RIGHT_DP = 10f
    const val ROW_PAD_BOTTOM_DP = 16f
    const val ROW_PAD_LEFT_DP = 2f

    // .cx-rowt-fix — width:170px; .cx-rowt-wide — width:210px
    const val ROW_TITLE_FIX_DP = 170f
    const val ROW_TITLE_WIDE_DP = 210f

    // .cx-sect — height:52px; margin-top:16px; padding:0 2px 10px; font-size:23px
    const val SECTION_HEIGHT_DP = 52f
    const val SECTION_MARGIN_TOP_DP = 16f
    const val SECTION_PAD_BOTTOM_DP = 10f
    const val SECTION_TEXT_SP = 23f

    // .cx-seg — height:48px; padding:4px; gap:4px; border-radius:13px
    const val SEG_HEIGHT_DP = 48f
    const val SEG_PAD_DP = 4f
    const val SEG_GAP_DP = 4f
    const val SEG_RADIUS_DP = 13f

    // .cx-segb — flex:1; min-width:64px; border-radius:12px; font-size:17px; padding:0 14px
    const val SEGB_MIN_WIDTH_DP = 64f
    const val SEGB_RADIUS_DP = 12f
    const val SEGB_TEXT_SP = 17f
    const val SEGB_PAD_H_DP = 14f

    // .cx-btn — height:48px; padding:0 16px; border-radius:12px; font-size:15px
    const val BTN_HEIGHT_DP = 48f
    const val BTN_PAD_H_DP = 16f
    const val BTN_RADIUS_DP = 12f
    const val BTN_TEXT_SP = 15f

    // .cx-themes — gap:10px; padding:4px 2px 14px; .theme — border-radius:16px; padding:10px
    const val THEME_GAP_DP = 10f
    const val THEME_RADIUS_DP = 16f
    const val THEME_PAD_DP = 10f

    // .tabbar — bottom:14px; left:50%; max-width:calc(100vw - 24px);
    //           border-radius:100px; padding:7px; gap:2px
    const val DOCK_BOTTOM_DP = 14f

    /* Область видео CarPlay/Android Auto. Значения — точный порт JS из
       index.html:1024-1033 (`projSyncFrame`):
           var hh = Math.floor(tabbar.getBoundingClientRect().top - 10);
           el.style.height = Math.max(320, hh) + 'px';
       CSS-пиксели шелла соответствуют dp, поэтому числа перенесены как есть. */

    /** Зазор между нижней кромкой видео и верхом плавающего дока. */
    const val PROJ_DOCK_GAP_DP = 10f

    /** Минимальная высота области видео. */
    const val PROJ_MIN_HEIGHT_DP = 320f
    const val DOCK_SIDE_MARGIN_DP = 12f
    const val DOCK_RADIUS_DP = 100f
    const val DOCK_PAD_DP = 7f
    const val DOCK_GAP_DP = 2f

    // .tab — padding:9px 11px; border-radius:100px; font-size:12.5px; gap:7px
    const val TAB_PAD_V_DP = 9f
    const val TAB_PAD_H_DP = 11f
    const val TAB_RADIUS_DP = 100f
    const val TAB_TEXT_SP = 12.5f
    const val TAB_GAP_DP = 7f

    // .modes и .airGrid — display:grid; grid-template-columns:repeat(3,1fr); gap:8px
    const val GRID_COLUMNS = 3
    const val GRID_GAP_DP = 8f

    // .mode — border-radius:15px; padding:12px 4px; .mode span — font-size:23px
    const val MODE_RADIUS_DP = 15f
    const val MODE_PAD_V_DP = 12f
    const val MODE_PAD_H_DP = 4f
    const val MODE_EMOJI_SP = 23f

    // .air/.heat/.seat — border-radius:15px; padding:11px 4px 10px; gap:6px
    const val TILE_RADIUS_DP = 15f
    const val TILE_PAD_TOP_DP = 11f
    const val TILE_PAD_H_DP = 4f
    const val TILE_PAD_BOTTOM_DP = 10f
    const val TILE_GAP_DP = 6f
    // .air svg / .heat svg / .seat svg — width:26px; height:26px
    const val TILE_ICON_DP = 26f

    // .row — gap:10px; padding:11px 0; font-size:14.5px
    const val PANEL_ROW_PAD_V_DP = 11f
    const val PANEL_ROW_GAP_DP = 10f
    const val PANEL_ROW_TEXT_SP = 14.5f

    // .sect — font-size:11.5px; margin:15px 0 8px; text-transform:uppercase
    const val PANEL_SECT_TEXT_SP = 11.5f
    const val PANEL_SECT_MARGIN_TOP_DP = 15f
    const val PANEL_SECT_MARGIN_BOTTOM_DP = 8f

    // .kv — padding:10px 0; font-size:14px
    const val KV_PAD_V_DP = 10f
    const val KV_TEXT_SP = 14f

    // .sw — width:50px; height:30px
    const val SWITCH_WIDTH_DP = 50f
    const val SWITCH_HEIGHT_DP = 30f

    // .mBack — position:fixed; inset:0; background:rgba(30,25,15,.45);
    //          display:flex; align-items:center; justify-content:center; padding:20px
    const val MODAL_SCRIM = 0x731E190F.toInt()   // rgba(30,25,15,.45) -> #731E190F
    const val MODAL_PAD_DP = 20f

    // .mCard — background:var(--card); border-radius:22px; padding:20px; width:430px
    const val MODAL_RADIUS_DP = 22f
    const val MODAL_CARD_PAD_DP = 20f
    const val MODAL_CARD_WIDTH_DP = 430f

    // .mTitle — font-size:17px; font-weight:800
    const val MODAL_TITLE_SP = 17f

    // .cx-helprow — padding:9px 0; border-bottom:1px solid var(--line)
    const val HELP_ROW_PAD_V_DP = 9f

    // .btnrow — display:flex; gap:8px; margin-top:12px
    const val BTN_ROW_GAP_DP = 8f
    const val BTN_ROW_MARGIN_TOP_DP = 12f

    // .tileBtn — border-radius:12px; padding:12px 14px; margin-top:8px
    const val TILE_BTN_RADIUS_DP = 12f
    const val TILE_BTN_PAD_V_DP = 12f
    const val TILE_BTN_PAD_H_DP = 14f

    // карточка keep-alive: max-width:540px; width:90%; max-height:80vh;
    //                      список max-height:360px
    const val KEEPALIVE_WIDTH_DP = 540f
    const val KEEPALIVE_LIST_MAX_DP = 360f

    // .topbar — gap:14px; padding:10px 22px 8px
    const val TOPBAR_GAP_DP = 14f
    const val TOPBAR_PAD_TOP_DP = 10f
    const val TOPBAR_PAD_H_DP = 22f
    const val TOPBAR_PAD_BOTTOM_DP = 8f
}
