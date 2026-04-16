package com.ibbie.catrec_screenrecorcer.navigation

import androidx.annotation.StringRes
import com.ibbie.catrec_screenrecorcer.R

sealed class Screen(
    val route: String,
    @param:StringRes val titleRes: Int,
) {
    object Screenshots : Screen("screenshots", R.string.tab_screenshots)

    object Recordings : Screen("recordings", R.string.tab_recordings)

    object Settings : Screen("settings", R.string.tab_settings)

    object Support : Screen("support", R.string.tab_support)

    object Faq : Screen("faq", R.string.faq_title)

    object Feedback : Screen("feedback", R.string.feedback_title)

    /** Video tools / editor hub (trim, GIF, etc.). */
    object Editor : Screen("editor", R.string.tab_editor)

    object Crop : Screen("crop/{imageUri}", R.string.app_name)

    object Player : Screen("player?videoUri={videoUri}", R.string.app_name)

    object Trim : Screen("trim?videoUri={videoUri}", R.string.app_name)

    object Compress : Screen("compress?videoUri={videoUri}", R.string.tool_compress)

    object VideoToGif : Screen("video_to_gif?videoUri={videoUri}", R.string.tool_video_to_gif)

    object MergeVideos : Screen("merge_videos", R.string.tool_merge_clips)

    /** Query param [imageUri] is URL-encoded content URI string. */
    object ImageEditor : Screen("image_editor?imageUri={imageUri}", R.string.image_editor_title)
}
