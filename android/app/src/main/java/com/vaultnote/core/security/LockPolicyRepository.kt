package com.vaultnote.core.security

import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.Clock
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.database.dao.AppSettingDao
import com.vaultnote.core.database.entity.AppSettingEntity
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface LockPolicyRepository {
    fun observe(): Flow<RepositoryResult<LockPolicy>>
    suspend fun save(policy: LockPolicy): RepositoryResult<Unit>
}

class RoomLockPolicyRepository(
    private val settings: AppSettingDao,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
) : LockPolicyRepository {
    override fun observe(): Flow<RepositoryResult<LockPolicy>> = settings.observe(SETTING_KEY)
        .map { entity ->
            val result: RepositoryResult<LockPolicy> = RepositoryResult.Success(
                entity?.value?.let(::decode) ?: LockPolicy.DEFAULT,
            )
            result
        }
        .catch { failure ->
            if (failure is CancellationException) throw failure
            emit(
                RepositoryResult.Failure(
                    AppError.DatabaseFailure(OPERATION_READ_POLICY, failure as? Exception),
                ),
            )
        }
        .flowOn(dispatchers.io)

    override suspend fun save(policy: LockPolicy): RepositoryResult<Unit> =
        withContext(dispatchers.io) {
            try {
                settings.upsert(
                    AppSettingEntity(
                        key = SETTING_KEY,
                        value = encode(policy),
                        updatedAt = clock.nowEpochMillis(),
                    ),
                )
                RepositoryResult.Success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                RepositoryResult.Failure(AppError.DatabaseFailure(OPERATION_SAVE_POLICY, failure))
            }
        }

    private fun encode(policy: LockPolicy): String = buildString {
        append(FORMAT_VERSION)
        append('|')
        append(if (policy.isLockEnabled) '1' else '0')
        append('|')
        append(policy.backgroundTimeoutMillis)
        append('|')
        append(if (policy.blockScreenshots) '1' else '0')
    }

    private fun decode(value: String): LockPolicy {
        val parts = value.split('|')
        if (parts.size != 4 || parts[0] != FORMAT_VERSION) return LockPolicy.FAIL_CLOSED
        val enabled = when (parts[1]) {
            "1" -> true
            "0" -> false
            else -> return LockPolicy.FAIL_CLOSED
        }
        val timeout = parts[2].toLongOrNull()
            ?.takeIf { it in LockPolicy.SUPPORTED_TIMEOUTS }
            ?: return LockPolicy.FAIL_CLOSED
        val screenshots = when (parts[3]) {
            "1" -> true
            "0" -> false
            else -> return LockPolicy.FAIL_CLOSED
        }
        return LockPolicy(enabled, timeout, screenshots)
    }

    private companion object {
        const val SETTING_KEY = "security.lock_policy"
        const val FORMAT_VERSION = "1"
        const val OPERATION_READ_POLICY = "read_lock_policy"
        const val OPERATION_SAVE_POLICY = "save_lock_policy"
    }
}
