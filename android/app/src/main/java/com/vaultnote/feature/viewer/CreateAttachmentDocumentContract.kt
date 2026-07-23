package com.vaultnote.feature.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

internal class CreateAttachmentDocumentContract :
    ActivityResultContract<AttachmentSavePickerRequest, Uri?>() {
    override fun createIntent(context: Context, input: AttachmentSavePickerRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.displayName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}
