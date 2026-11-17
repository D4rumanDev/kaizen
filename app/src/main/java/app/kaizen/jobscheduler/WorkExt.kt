package app.kaizen.jobscheduler

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest
import app.kaizen.App

fun WorkRequest.enqueue() = WorkManager.getInstance(App.context).enqueue(this)

fun PeriodicWorkRequest.enqueueUnique(tag: String, type: ExistingPeriodicWorkPolicy) = WorkManager.getInstance(App.context).enqueueUniquePeriodicWork(tag, type, this)