package com.arnyminerz.filmagentaproto

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest.Companion.MIN_BACKOFF_MILLIS
import androidx.work.WorkerParameters
import com.arnyminerz.filmagentaproto.account.Authenticator
import com.arnyminerz.filmagentaproto.account.credentials.WCCredentials
import com.arnyminerz.filmagentaproto.database.data.PersonalData
import com.arnyminerz.filmagentaproto.database.local.AppDatabase
import com.arnyminerz.filmagentaproto.database.remote.RemoteDatabaseInterface
import com.arnyminerz.filmagentaproto.database.remote.RemoteServer
import com.arnyminerz.filmagentaproto.utils.trimmedAndCaps
import java.util.concurrent.TimeUnit

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
    companion object {
        private const val TAG = "sync_worker"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequest
                .Builder(
                    SyncWorker::class.java,
                    8,
                    TimeUnit.HOURS,
                    15,
                    TimeUnit.MINUTES,
                )
                .addTag(TAG)
                .setConstraints(Constraints(NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun run(context: Context): Operation {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .addTag(TAG)
                .setConstraints(Constraints(NetworkType.CONNECTED))
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
            return WorkManager.getInstance(context)
                .enqueue(request)
        }

        fun getLiveState(context: Context) = WorkManager
            .getInstance(context)
            .getWorkInfosByTagLiveData(TAG)
    }

    override suspend fun doWork(): Result {
        // Get access to the database
        val database = AppDatabase.getInstance(applicationContext)
        val personalDataDao = database.personalDataDao()
        val remoteDatabaseDao = database.remoteDatabaseDao()

        // Synchronize data of all the accounts
        val am = AccountManager.get(applicationContext)
        val accounts = am.getAccountsByType(Authenticator.AuthTokenType)
        accounts.forEach { account ->
            val authToken: String? = am.peekAuthToken(account, Authenticator.AuthTokenType)
            val wcCredentials = WCCredentials.fromAccount(am, account)

            if (authToken == null || wcCredentials == null) {
                Log.e(TAG, "Credentials for ${account.name} are not valid, clearing password...")
                am.clearPassword(account)
                return@forEach
            }

            // Fetch the data and update the database
            val html = RemoteServer.fetch(authToken)
            val data = PersonalData.fromHtml(html, account)
            val dbData = personalDataDao.getByAccount(account.name, account.type)
            if (dbData == null)
                personalDataDao.insert(data)
            else
                personalDataDao.update(dbData)
        }

        // Fetch all the data from users in database
        RemoteDatabaseInterface.fetchAll().forEach { socio ->
            try {
                remoteDatabaseDao.insert(socio)
            } catch (e: SQLiteConstraintException) {
                remoteDatabaseDao.update(socio)
            }
        }

        // Also fetch the data of all the associated accounts
        for (account in accounts) {
            val dni = am.getPassword(account).trimmedAndCaps
            val socios = remoteDatabaseDao.getAll()
            val socio = socios.find { it.Dni?.trimmedAndCaps == dni } ?: continue
            val associateds = remoteDatabaseDao.getAllAssociatedWith(socio.idSocio)
            if (associateds.isEmpty()) continue

            // Iterate each associated, and log in with their credentials to fetch the data
            for (associated in associateds) try {
                // Log in with the user's credentials
                val associatedDni = associated.Dni ?: continue
                Log.d(TAG, "Logging in with \"${associated.Nombre}\" and $associatedDni")
                val authToken = RemoteServer.login(associated.Nombre, associatedDni)
                // Fetch the data for the associated
                val html = RemoteServer.fetch(authToken)
                val data = PersonalData.fromHtml(html, Account(associated.Nombre, Authenticator.AuthTokenType))
                try {
                    personalDataDao.insert(data)
                } catch (e: SQLiteConstraintException) {
                    personalDataDao.update(data)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not synchronize data for associated: ${associated.idSocio}", e)
                continue
            }
        }

        return Result.success()
    }
}