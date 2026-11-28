package jp.noppy.lumamusic.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import jp.noppy.lumamusic.LumaApp

class UpdateCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val updates = (applicationContext as LumaApp).updates
        return if (updates.check(manual = false)) Result.success() else Result.retry()
    }

    companion object {
        internal const val UniqueName = "aurio_update_check"
    }
}
