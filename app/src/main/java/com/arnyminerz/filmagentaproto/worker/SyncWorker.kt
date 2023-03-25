package com.arnyminerz.filmagentaproto.worker

import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteConstraintException
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest.Companion.MIN_BACKOFF_MILLIS
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arnyminerz.filmagentaproto.NotificationChannels
import com.arnyminerz.filmagentaproto.R
import com.arnyminerz.filmagentaproto.account.Authenticator
import com.arnyminerz.filmagentaproto.activity.ShareMessageActivity
import com.arnyminerz.filmagentaproto.database.data.PersonalData
import com.arnyminerz.filmagentaproto.database.data.Transaction
import com.arnyminerz.filmagentaproto.database.data.woo.ROLE_ADMINISTRATOR
import com.arnyminerz.filmagentaproto.database.data.woo.WooClass
import com.arnyminerz.filmagentaproto.database.local.AppDatabase
import com.arnyminerz.filmagentaproto.database.local.PersonalDataDao
import com.arnyminerz.filmagentaproto.database.local.RemoteDatabaseDao
import com.arnyminerz.filmagentaproto.database.local.WooCommerceDao
import com.arnyminerz.filmagentaproto.database.remote.RemoteCommerce
import com.arnyminerz.filmagentaproto.database.remote.RemoteDatabaseInterface
import com.arnyminerz.filmagentaproto.database.remote.RemoteServer
import com.arnyminerz.filmagentaproto.utils.PermissionsUtils
import com.arnyminerz.filmagentaproto.utils.trimmedAndCaps
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SpanStatus
import java.util.concurrent.TimeUnit
import kotlin.random.Random

enum class ProgressStep(@StringRes val textRes: Int) {
    INITIALIZING(R.string.sync_step_initializing),
    SYNC_CUSTOMERS(R.string.sync_step_customers),
    SYNC_ORDERS(R.string.sync_step_orders),
    SYNC_EVENTS(R.string.sync_step_events),
    SYNC_PAYMENTS(R.string.sync_step_payments),
    SYNC_TRANSACTIONS(R.string.sync_step_transactions),
    SYNC_SOCIOS(R.string.sync_step_socios),
    INTERMEDIATE(R.string.sync_step_intermediate)
}

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "sync_worker"
        const val TAG_PERIODIC = "periodic"

        private const val UNIQUE_WORK_NAME = "sync"

        private const val SYNC_CUSTOMERS = "sync_customers"

        private const val SYNC_ORDERS = "sync_orders"

        private const val SYNC_EVENTS = "sync_events"

        private const val SYNC_PAYMENTS = "sync_payments"

        private const val SYNC_TRANSACTIONS = "sync_transactions"

        private const val SYNC_SOCIOS = "sync_socios"

        const val PROGRESS_STEP = "step"

        const val PROGRESS = "progress"

        const val EXCEPTION_CLASS = "exception_class"
        const val EXCEPTION_MESSAGE = "exception_message"

        private const val NOTIFICATION_ID = 20230315
        private const val ERROR_NOTIFICATION_ID = 20230324

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
                .addTag(TAG_PERIODIC)
                .setConstraints(Constraints(NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
        }

        fun run(
            context: Context,
            syncTransactions: Boolean = true,
            syncSocios: Boolean = true,
            syncCustomers: Boolean = true,
            syncOrders: Boolean = true,
            syncEvents: Boolean = true,
            syncPayments: Boolean = true,
        ): Operation {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .addTag(TAG)
                .setConstraints(Constraints(NetworkType.CONNECTED))
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setInputData(
                    workDataOf(
                        SYNC_TRANSACTIONS to syncTransactions,
                        SYNC_SOCIOS to syncSocios,
                        SYNC_CUSTOMERS to syncCustomers,
                        SYNC_ORDERS to syncOrders,
                        SYNC_EVENTS to syncEvents,
                        SYNC_PAYMENTS to syncPayments,
                    )
                )
                .build()
            return WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun getLiveState(context: Context) = WorkManager
            .getInstance(context)
            .getWorkInfosByTagLiveData(TAG)
    }

    private val am = AccountManager.get(appContext)

    private lateinit var notificationManager: NotificationManagerCompat

    private lateinit var personalDataDao: PersonalDataDao
    private lateinit var remoteDatabaseDao: RemoteDatabaseDao
    private lateinit var wooCommerceDao: WooCommerceDao

    private lateinit var transaction: ITransaction

    private var isFirstSynchronization = false

    override suspend fun doWork(): Result {
        Log.i(TAG, "Running Synchronization...")

        transaction = Sentry.startTransaction("SyncWorker", "synchronization")

        notificationManager = NotificationManagerCompat.from(applicationContext)

        return try {
            synchronize()
        } catch (e: Exception) {
            // Log the exception
            e.printStackTrace()

            // Append the error to the transaction
            transaction.throwable = e
            transaction.status = SpanStatus.INTERNAL_ERROR

            // Notify Sentry about the error
            Sentry.captureException(e)

            // Show the error notification
            showErrorNotification(e)

            Result.failure(
                workDataOf(
                    EXCEPTION_CLASS to e::class.java.name,
                    EXCEPTION_MESSAGE to e.message,
                )
            )
        } finally {
            notificationManager.cancel(NOTIFICATION_ID)
            
            transaction.finish()
        }
    }

    /**
     * Runs the synchronization process for the app.
     */
    private suspend fun synchronize(): Result {
        setProgress(ProgressStep.INITIALIZING)

        // Get access to the database
        val database = AppDatabase.getInstance(applicationContext)
        personalDataDao = database.personalDataDao()
        remoteDatabaseDao = database.remoteDatabaseDao()
        wooCommerceDao = database.wooCommerceDao()

        // Store if this is the first synchronization
        isFirstSynchronization = personalDataDao.getAll().isEmpty()

        val syncTransactions = inputData.getBoolean(SYNC_TRANSACTIONS, true)
        val syncSocios = inputData.getBoolean(SYNC_SOCIOS, true)

        // Synchronize data of all the accounts
        val accounts = am.getAccountsByType(Authenticator.AuthTokenType)
        accounts.forEach { account ->
            val authToken: String? = am.peekAuthToken(account, Authenticator.AuthTokenType)

            if (authToken == null) {
                Log.e(TAG, "Credentials for ${account.name} are not valid, clearing password...")
                am.clearPassword(account)
                return@forEach
            }

            if (syncTransactions) {
                // Fetch the data and update the database
                setProgress(ProgressStep.SYNC_TRANSACTIONS)

                val html = RemoteServer.fetch(authToken)
                val data = PersonalData.fromHtml(html, account)
                val dbData = personalDataDao.getByAccount(account.name, account.type)
                if (dbData == null)
                    personalDataDao.insert(data)
                else
                    personalDataDao.update(dbData)

                // Show notifications for new transactions
                personalDataDao.getByAccount(account.name, account.type)?.let { updatedData ->
                    val newTransactions = updatedData.transactions.map { transaction ->
                        if (transaction.notified)
                            transaction
                        else {
                            // No notifications should be shown during the first synchronization
                            if (!isFirstSynchronization)
                                notifyTransaction(data.accountName, transaction)
                            transaction.copy(notified = true)
                        }
                    }
                    // Update the stored transactions
                    personalDataDao.updateTransactions(account.name, newTransactions)
                }

                setProgress(ProgressStep.INTERMEDIATE)
            }

            // Fetch the data from woo
            fetchAndUpdateWooData(account)
        }

        // Fetch all the data from users in database
        if (syncSocios) {
            setProgress(ProgressStep.SYNC_SOCIOS)
            val socios = RemoteDatabaseInterface.fetchAll()
            for ((index, socio) in socios.withIndex()) {
                setProgress(ProgressStep.SYNC_SOCIOS, index to socios.size)
                try {
                    remoteDatabaseDao.insert(socio)
                } catch (e: SQLiteConstraintException) {
                    remoteDatabaseDao.update(socio)
                }
            }
            setProgress(ProgressStep.INTERMEDIATE)
        }

        // Also fetch the data of all the associated accounts
        if (syncTransactions) {
            setProgress(ProgressStep.SYNC_TRANSACTIONS)
            for ((index, account) in accounts.withIndex()) {
                setProgress(ProgressStep.SYNC_TRANSACTIONS, index to accounts.size)

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
                    val data = PersonalData.fromHtml(
                        html,
                        Account(associated.Nombre, Authenticator.AuthTokenType)
                    )
                    try {
                        personalDataDao.insert(data)
                    } catch (e: SQLiteConstraintException) {
                        personalDataDao.update(data)
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Could not synchronize data for associated: ${associated.idSocio}",
                        e,
                    )
                    continue
                }
            }
            setProgress(ProgressStep.INTERMEDIATE)
        }

        Log.i(TAG, "Finished synchronization")

        return Result.success()
    }

    /**
     * Takes the extra data from [shouldSyncInputKey], and if it's `true` (`true` by default), it
     * fetches the data from the server (using the provided function [remoteFetcher]), and updates
     * the database using [insertMethod] and [updateMethod]. Then, obtains all the stored data from
     * the database using [databaseFetcher], and deletes the entries deleted from the server with
     * [updateMethod]. Also sends progress updates with [setProgress] and [progressStep].
     * @param shouldSyncInputKey The key from [getInputData] which should be a boolean value stating
     * whether this field should be fetched. `true` by default.
     * @param progressStep One of [ProgressStep] for sending progress updates with [setProgress].
     * @param remoteFetcher Should return all the entries from the server.
     * @param databaseFetcher Should return all the entries from the local database.
     * @param insertMethod Should insert the given `item` into the database.
     * @param updateMethod Should update the given `item` in the database.
     * @param deleteMethod Should delete the given `item` from the database.
     * @param listExtraProcessing If some extra processing wants to be done with the entries fetched
     * with [remoteFetcher].
     */
    private suspend inline fun <T : WooClass> fetchAndUpdateDatabase(
        shouldSyncInputKey: String,
        progressStep: ProgressStep,
        remoteFetcher: () -> List<T>,
        databaseFetcher: () -> List<T>,
        insertMethod: (item: T) -> Unit,
        updateMethod: (item: T) -> Unit,
        deleteMethod: (item: T) -> Unit,
        listExtraProcessing: (List<T>) -> Unit = {},
    ) {
        val span = transaction.startChild("fetchAndUpdateDatabase", progressStep.name)
        val shouldSync = inputData.getBoolean(shouldSyncInputKey, true)
        if (shouldSync) {
            setProgress(progressStep)
            Log.d(TAG, "Getting list from remote...")
            val list = remoteFetcher()

            listExtraProcessing(list)

            Log.d(TAG, "Updating database...")
            for ((index, item) in list.withIndex()) {
                setProgress(progressStep, index to list.size)
                try {
                    insertMethod(item)
                } catch (e: SQLiteConstraintException) {
                    updateMethod(item)
                }
            }

            Log.d(TAG, "Synchronizing deletions...")
            val storedList = databaseFetcher()
            for (stored in storedList)
                if (list.find { it.id == stored.id } == null)
                    deleteMethod(stored)

            setProgress(ProgressStep.INTERMEDIATE)
        }
        span.finish()
    }

    /**
     * Fetches all the data from the REST endpoints, and updates the database accordingly.
     */
    private suspend fun fetchAndUpdateWooData(
        account: Account,
    ) {
        val span = transaction.startChild("fetchAndUpdateWooData")
        val dni = am.getPassword(account)

        var customerId: Long? = am.getUserData(account, "customer_id")?.toLongOrNull()
        var isAdmin: Boolean? = am.getUserData(account, "customer_admin")?.toBoolean()

        // Fetch all customers data
        fetchAndUpdateDatabase(
            SYNC_CUSTOMERS,
            ProgressStep.SYNC_CUSTOMERS,
            { RemoteCommerce.customersList() },
            { wooCommerceDao.getAllCustomers() },
            { wooCommerceDao.insert(it) },
            { wooCommerceDao.update(it) },
            { wooCommerceDao.delete(it) },
        ) { customers ->
            val customer = customers.find { it.username.equals(dni, true) }
                ?: throw IndexOutOfBoundsException("Could not find logged in user in the customers database.")
            if (customerId == null) {
                Log.i(TAG, "Customer ID: ${customer.id}")
                customerId = customer.id
                am.setUserData(account, "customer_id", customerId.toString())
            }
            if (isAdmin == null) {
                Log.i(TAG, "Customer role: ${customer.role}")
                isAdmin = customer.role == ROLE_ADMINISTRATOR
                am.setUserData(account, "customer_admin", isAdmin.toString())
            }
        }

        // Fetch all payments available
        fetchAndUpdateDatabase(
            SYNC_PAYMENTS,
            ProgressStep.SYNC_PAYMENTS,
            { RemoteCommerce.paymentsList() },
            { wooCommerceDao.getAllAvailablePayments() },
            { wooCommerceDao.insert(it) },
            { wooCommerceDao.update(it) },
            { wooCommerceDao.delete(it) },
        )

        // Fetch all orders available
        if (customerId != null)
            fetchAndUpdateDatabase(
                SYNC_ORDERS,
                ProgressStep.SYNC_ORDERS,
                { RemoteCommerce.orderList(customerId?.takeIf { isAdmin != true }) },
                { wooCommerceDao.getAllOrders() },
                { wooCommerceDao.insert(it) },
                { wooCommerceDao.update(it) },
                { wooCommerceDao.delete(it) },
            )

        // Fetch all events available
        fetchAndUpdateDatabase(
            SYNC_EVENTS,
            ProgressStep.SYNC_EVENTS,
            {
                RemoteCommerce.eventList { progress ->
                    setProgress(
                        ProgressStep.SYNC_EVENTS,
                        progress
                    )
                }
            },
            { wooCommerceDao.getAllEvents() },
            { wooCommerceDao.insert(it) },
            { wooCommerceDao.update(it) },
            { wooCommerceDao.delete(it) },
        )

        span.finish()
    }

    /**
     * Creates the required notification channels.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannels() {
        NotificationChannels.createSyncGroup(applicationContext)
        NotificationChannels.createSyncProgressChannel(applicationContext)
        NotificationChannels.createSyncErrorChannel(applicationContext)
        NotificationChannels.createTransactionChannel(applicationContext)
    }

    private fun createForegroundInfo(
        step: ProgressStep,
        progress: Pair<Int, Int>?
    ): ForegroundInfo {
        val cancel = applicationContext.getString(R.string.cancel)
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannels()
        }

        val notification =
            NotificationCompat.Builder(applicationContext, NotificationChannels.SYNC_PROGRESS)
                .setContentTitle(applicationContext.getString(R.string.sync_running))
                .setContentText(applicationContext.getString(step.textRes))
                .apply {
                    progress?.let { (current, max) ->
                        setProgress(max, current, false)
                        setTicker("$current / $max")
                    } ?: {
                        setProgress(0, 0, true)
                        setTicker(null)
                    }
                }
                .setSmallIcon(R.drawable.logo_magenta_mono)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_delete, cancel, cancelIntent)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        else
            ForegroundInfo(NOTIFICATION_ID, notification)
    }

    /**
     * Updates the progress of the worker.
     * @param step The step currently being ran.
     * @param progress The current progress reported, if any. Can be null. First is current, second
     * is max.
     */
    private suspend fun setProgress(step: ProgressStep, progress: Pair<Int, Int>? = null) {
        setProgress(
            workDataOf(
                PROGRESS_STEP to step.name,
                PROGRESS to progress?.let { (current, max) -> current.toDouble() / max.toDouble() },
            )
        )
        setForeground(
            createForegroundInfo(step, progress)
        )
    }

    /**
     * Shows a notification when an error occurs during synchronization.
     */
    @SuppressLint("MissingPermission")
    private fun showErrorNotification(exception: java.lang.Exception) {
        if (!PermissionsUtils.hasNotificationPermission(applicationContext)) return

        val message = listOf(
            "Exception: ${exception::class.java.name}",
            "Message: ${exception.message}"
        )

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, ShareMessageActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(ShareMessageActivity.EXTRA_MESSAGE, message.joinToString("\n"))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification =
            NotificationCompat.Builder(applicationContext, NotificationChannels.SYNC_ERROR)
                .setSmallIcon(R.drawable.logo_magenta_mono)
                .setContentTitle(applicationContext.getString(R.string.sync_error_title))
                .setContentText(applicationContext.getString(R.string.sync_error_message))
                .addAction(
                    R.drawable.round_share_24,
                    applicationContext.getString(R.string.share),
                    pendingIntent,
                )
                .build()
        notificationManager.notify(ERROR_NOTIFICATION_ID, notification)
    }

    /**
     * Sends a notification to the user regarding a transaction received or charged to the user's
     * account.
     */
    @SuppressLint("MissingPermission")
    private fun notifyTransaction(accountName: String, transaction: Transaction) {
        if (!PermissionsUtils.hasNotificationPermission(applicationContext)) return

        val message = if (transaction.enters != null)
            applicationContext.getString(
                R.string.notification_transaction_input_message,
                transaction.enters,
                transaction.description,
            )
        else
            applicationContext.getString(
                R.string.notification_transaction_charge_message,
                transaction.exits,
                transaction.description,
            )

        val notification =
            NotificationCompat.Builder(applicationContext, NotificationChannels.TRANSACTION)
                .setSmallIcon(R.drawable.logo_magenta_mono)
                .setContentTitle(applicationContext.getString(R.string.notification_transaction_title))
                .setContentText(message)
                .setContentInfo(accountName)
                .setWhen(transaction.timestamp?.time ?: 0)
                .setShowWhen(transaction.timestamp != null)
                .build()
        notificationManager.notify(Random.nextInt(), notification)
    }
}